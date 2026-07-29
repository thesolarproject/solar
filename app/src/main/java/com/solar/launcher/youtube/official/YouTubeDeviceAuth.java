package com.solar.launcher.youtube.official;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.BuildConfig;
import com.solar.launcher.net.TlsHelper;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Google OAuth device authorization for the Y1's limited-input UI.
 *
 * Polls only at the server-provided interval, backs off on slow_down, and uses
 * generation cancellation so there is never more than one active flow.
 */
public final class YouTubeDeviceAuth {

    private static final String DEVICE_ENDPOINT =
            "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_ENDPOINT =
            "https://oauth2.googleapis.com/token";
    private static final String REVOKE_ENDPOINT =
            "https://oauth2.googleapis.com/revoke";
    private static volatile YouTubeDeviceAuth instance;

    public enum State {
        IDLE,
        REQUESTING_CODE,
        WAITING_FOR_USER,
        SLOW_DOWN,
        AUTHORIZED,
        DENIED,
        EXPIRED,
        NETWORK_ERROR,
        SETUP_REQUIRED
    }

    public static final class Snapshot {
        public final State state;
        public final String userCode;
        public final String verificationUrl;
        public final long expiresAtMs;
        public final int pollIntervalSec;
        public final String safeReason;

        Snapshot(State state, String userCode, String verificationUrl,
                long expiresAtMs, int pollIntervalSec, String safeReason) {
            this.state = state;
            this.userCode = nonNull(userCode);
            this.verificationUrl = nonNull(verificationUrl);
            this.expiresAtMs = expiresAtMs;
            this.pollIntervalSec = pollIntervalSec;
            this.safeReason = nonNull(safeReason);
        }

        public int remainingSeconds(long nowMs) {
            if (expiresAtMs <= nowMs) return 0;
            return (int) Math.min(Integer.MAX_VALUE,
                    (expiresAtMs - nowMs + 999L) / 1000L);
        }

        public boolean isActive() {
            return state == State.REQUESTING_CODE || state == State.WAITING_FOR_USER
                    || state == State.SLOW_DOWN;
        }
    }

    public interface Listener {
        void onAuthStateChanged(Snapshot snapshot);
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final YouTubeTokenStore tokens;
    private volatile int generation;
    private volatile Snapshot snapshot =
            new Snapshot(State.IDLE, "", "", 0L, 5, "");
    private volatile Listener listener;

    private YouTubeDeviceAuth(Context context) {
        appContext = context.getApplicationContext();
        tokens = new YouTubeTokenStore(appContext);
    }

    public static YouTubeDeviceAuth getInstance(Context context) {
        if (instance == null) {
            synchronized (YouTubeDeviceAuth.class) {
                if (instance == null) instance = new YouTubeDeviceAuth(context);
            }
        }
        return instance;
    }

    public void setListener(Listener next) {
        listener = next;
        if (next != null) postSnapshot(snapshot);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean isConfigured() {
        return clientId().length() > 0 && clientSecret().length() > 0;
    }

    public boolean hasAccount() {
        return tokens.hasAccount();
    }

    public synchronized void start() {
        final int flow = ++generation;
        if (!isConfigured()) {
            emit(new Snapshot(State.SETUP_REQUIRED, "", "", 0L, 5,
                    "youtube_oauth_setup_required"));
            return;
        }
        emit(new Snapshot(State.REQUESTING_CODE, "", "", 0L, 5, ""));
        network.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    FormBody body = new FormBody.Builder()
                            .add("client_id", clientId())
                            .add("scope", YouTubeOAuthProtocol.READ_ONLY_SCOPE)
                            .build();
                    HttpResult response = postForm(DEVICE_ENDPOINT, body);
                    if (flow != generation) return;
                    if (response.code < 200 || response.code >= 300) {
                        emit(new Snapshot(State.NETWORK_ERROR, "", "", 0L, 5,
                                safeOAuthError(response.code, response.body)));
                        return;
                    }
                    YouTubeOAuthProtocol.DeviceCode code =
                            YouTubeOAuthProtocol.parseDeviceCode(response.body);
                    long expiresAt = YouTubeOAuthProtocol.expirationEpochMs(
                            System.currentTimeMillis(), code.expiresInSec);
                    Snapshot waiting = new Snapshot(State.WAITING_FOR_USER,
                            code.userCode, code.verificationUrl, expiresAt,
                            code.intervalSec, "");
                    emit(waiting);
                    schedulePoll(flow, code.deviceCode, waiting);
                } catch (Exception e) {
                    if (flow != generation) return;
                    emit(new Snapshot(State.NETWORK_ERROR, "", "", 0L, 5,
                            safeException(e)));
                }
            }
        });
    }

    public synchronized void cancel() {
        generation++;
        emit(new Snapshot(State.IDLE, "", "", 0L, 5, ""));
    }

    public void signOut(final Runnable completion) {
        final int flow = ++generation;
        final String token = tokens.refreshToken().length() > 0
                ? tokens.refreshToken()
                : tokens.validAccessToken(System.currentTimeMillis());
        tokens.clear();
        emit(new Snapshot(State.IDLE, "", "", 0L, 5, ""));
        if (token.length() == 0) {
            if (completion != null) main.post(completion);
            return;
        }
        network.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    FormBody body = new FormBody.Builder().add("token", token).build();
                    postForm(REVOKE_ENDPOINT, body);
                } catch (Exception ignored) {
                    // Local sign-out is complete even when revocation is offline.
                }
                if (flow == generation && completion != null) main.post(completion);
            }
        });
    }

    /**
     * Called only from YouTubeClient's worker threads.
     * Returns an empty string when no account exists; refresh failures clear
     * revoked/invalid credentials and never expose token text.
     */
    public synchronized String accessTokenForApi() throws IOException {
        String current = tokens.validAccessToken(System.currentTimeMillis());
        if (current.length() > 0) return current;
        String refresh = tokens.refreshToken();
        if (refresh.length() == 0 || clientId().length() == 0) return "";
        FormBody.Builder form = new FormBody.Builder()
                .add("client_id", clientId())
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token");
        if (clientSecret().length() > 0) form.add("client_secret", clientSecret());
        HttpResult response = postForm(TOKEN_ENDPOINT, form.build());
        YouTubeOAuthProtocol.PollResult result =
                YouTubeOAuthProtocol.parsePollResponse(response.code, response.body);
        if (result.state != YouTubeOAuthProtocol.PollState.GRANTED) {
            if (result.state == YouTubeOAuthProtocol.PollState.EXPIRED
                    || result.state == YouTubeOAuthProtocol.PollState.INVALID_CLIENT
                    || result.state == YouTubeOAuthProtocol.PollState.ACCESS_DENIED) {
                tokens.clear();
            }
            throw new IOException(result.safeReason);
        }
        tokens.save(result.accessToken, refresh,
                YouTubeOAuthProtocol.expirationEpochMs(
                        System.currentTimeMillis(), result.expiresInSec),
                result.scope);
        return result.accessToken;
    }

    private void schedulePoll(final int flow, final String deviceCode,
            final Snapshot waiting) {
        if (flow != generation) return;
        if (waiting.expiresAtMs <= System.currentTimeMillis()) {
            emit(new Snapshot(State.EXPIRED, waiting.userCode, waiting.verificationUrl,
                    waiting.expiresAtMs, waiting.pollIntervalSec, "expired_token"));
            return;
        }
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                pollOnce(flow, deviceCode, waiting);
            }
        }, Math.max(1, waiting.pollIntervalSec), TimeUnit.SECONDS);
    }

    private void pollOnce(final int flow, final String deviceCode,
            final Snapshot prior) {
        if (flow != generation) return;
        try {
            FormBody body = new FormBody.Builder()
                    .add("client_id", clientId())
                    .add("client_secret", clientSecret())
                    .add("device_code", deviceCode)
                    .add("grant_type", YouTubeOAuthProtocol.DEVICE_GRANT_TYPE)
                    .build();
            HttpResult response = postForm(TOKEN_ENDPOINT, body);
            if (flow != generation) return;
            YouTubeOAuthProtocol.PollResult result =
                    YouTubeOAuthProtocol.parsePollResponse(response.code, response.body);
            if (result.state == YouTubeOAuthProtocol.PollState.GRANTED) {
                tokens.save(result.accessToken, result.refreshToken,
                        YouTubeOAuthProtocol.expirationEpochMs(
                                System.currentTimeMillis(), result.expiresInSec),
                        result.scope);
                emit(new Snapshot(State.AUTHORIZED, "", "", 0L,
                        prior.pollIntervalSec, ""));
                return;
            }
            if (result.state == YouTubeOAuthProtocol.PollState.AUTHORIZATION_PENDING
                    || result.state == YouTubeOAuthProtocol.PollState.SLOW_DOWN) {
                int interval = YouTubeOAuthProtocol.nextPollIntervalSec(
                        prior.pollIntervalSec, result.state);
                State state = result.state == YouTubeOAuthProtocol.PollState.SLOW_DOWN
                        ? State.SLOW_DOWN : State.WAITING_FOR_USER;
                Snapshot next = new Snapshot(state, prior.userCode,
                        prior.verificationUrl, prior.expiresAtMs, interval,
                        result.safeReason);
                emit(next);
                schedulePoll(flow, deviceCode, next);
                return;
            }
            State end = result.state == YouTubeOAuthProtocol.PollState.ACCESS_DENIED
                    ? State.DENIED
                    : (result.state == YouTubeOAuthProtocol.PollState.EXPIRED
                    ? State.EXPIRED : State.NETWORK_ERROR);
            emit(new Snapshot(end, prior.userCode, prior.verificationUrl,
                    prior.expiresAtMs, prior.pollIntervalSec, result.safeReason));
        } catch (Exception e) {
            if (flow != generation) return;
            Snapshot retry = new Snapshot(State.NETWORK_ERROR, prior.userCode,
                    prior.verificationUrl, prior.expiresAtMs,
                    Math.min(60, prior.pollIntervalSec + 5), safeException(e));
            emit(retry);
            if (retry.expiresAtMs > System.currentTimeMillis()) {
                schedulePoll(flow, deviceCode, retry);
            }
        }
    }

    private void emit(Snapshot next) {
        snapshot = next;
        postSnapshot(next);
    }

    private void postSnapshot(final Snapshot next) {
        final Listener target = listener;
        if (target == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (target == listener) target.onAuthStateChanged(next);
            }
        });
    }

    private static HttpResult postForm(String url, FormBody body) throws IOException {
        TlsHelper.ensureSecurityProvider();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "SolarLauncher/1.0")
                .post(body)
                .build();
        Response response = TlsHelper.client().newCall(request).execute();
        try {
            return new HttpResult(response.code(),
                    response.body() != null ? response.body().string() : "");
        } finally {
            response.close();
        }
    }

    private static String safeOAuthError(int httpCode, String body) {
        try {
            JSONObject object = new JSONObject(nonNull(body));
            String error = object.optString("error",
                    object.optString("error_code", ""));
            if (error.length() > 0) return error;
        } catch (Exception ignored) {}
        return "oauth_http_" + httpCode;
    }

    private static String safeException(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "network_timeout";
        if (error instanceof java.net.UnknownHostException) return "network_unavailable";
        if (error instanceof javax.net.ssl.SSLException) return "tls_error";
        return "network_error";
    }

    private static String clientId() {
        return nonNull(BuildConfig.YOUTUBE_OAUTH_CLIENT_ID).trim();
    }

    private static String clientSecret() {
        return nonNull(BuildConfig.YOUTUBE_OAUTH_CLIENT_SECRET).trim();
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = nonNull(body);
        }
    }
}
