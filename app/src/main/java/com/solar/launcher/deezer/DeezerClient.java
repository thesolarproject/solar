package com.solar.launcher.deezer;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.net.TlsHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Authenticated Deezer session via ARL cookie impersonation. */
public final class DeezerClient {
    public static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux i686; rv:135.0) Gecko/20100101 Firefox/135.0";

    private static final String GW_USER_DATA =
            "https://www.deezer.com/ajax/gw-light.php?method=deezer.getUserData"
                    + "&input=3&api_version=1.0&api_token=";

    private static final String GW_LIGHT = "https://www.deezer.com/ajax/gw-light.php";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final SharedPreferences prefs;
    private String arl = "";
    /** ponytail: transient download fallback — does not mutate prefs ARL. */
    private String arlOverride = null;
    private String licenseToken = "";
    private String apiToken = "";
    private String soundFormat = "MP3_128";
    private boolean premium = false;
    private String userName = "";
    private String userId = "";
    private volatile boolean sessionValid = false;

    public DeezerClient(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public boolean isSessionValid() {
        return sessionValid && licenseToken != null && !licenseToken.isEmpty();
    }

    public String soundFormat() {
        return soundFormat;
    }

    public boolean isPremium() {
        return premium;
    }

    public String userName() {
        return userName;
    }

    public String licenseToken() {
        return licenseToken;
    }

    public String arl() {
        return arl;
    }

    /** Use bundled/demo ARL for this session only — cleared via {@link #clearArlOverride()}. */
    public void setArlOverride(String arl) {
        arlOverride = arl != null && arl.trim().length() >= 64 ? arl.trim() : null;
        sessionValid = false;
    }

    public void clearArlOverride() {
        arlOverride = null;
        sessionValid = false;
    }

    public boolean isUsingArlOverride() {
        return arlOverride != null;
    }

    /** Initialize session from prefs; returns true if license token obtained. */
    public boolean initSession() throws IOException {
        arl = arlOverride != null ? arlOverride : DeezerAccount.defaultSessionArl(prefs);
        if (arl.isEmpty()) {
            sessionValid = false;
            return false;
        }
        try {
            String quality = DeezerAccount.loadQuality(prefs);
            byte[] body = getAuthenticated(GW_USER_DATA);
            JSONObject root = new JSONObject(new String(body, "UTF-8"));
            JSONObject results = root.getJSONObject("results");
            JSONObject user = results.getJSONObject("USER");
            JSONObject options = user.getJSONObject("OPTIONS");
            licenseToken = options.getString("license_token");
            JSONObject webQuality = options.getJSONObject("web_sound_quality");
            premium = webQuality.optBoolean("lossless", false);
            userName = user.optString("BLOG_NAME", user.optString("USER_ID", ""));
            userId = String.valueOf(user.optLong("USER_ID", 0));
            apiToken = results.optString("checkForm", "");
            if (apiToken == null || apiToken.isEmpty()) {
                apiToken = results.optString("check_form", "");
            }
            setSongQuality(quality, premium);
            if (arlOverride == null) {
                DeezerAccount.saveSessionInfo(prefs, userName, userId, premium);
            }
            sessionValid = true;
            return true;
        } catch (Exception e) {
            sessionValid = false;
            throw new IOException(e.getMessage() != null ? e.getMessage() : "Session init failed");
        }
    }

    public boolean testSession() {
        return testSession(null);
    }

    public boolean testSession(Context logCtx) {
        String failPhase = "unknown";
        try {
            failPhase = "initSession";
            if (!initSession()) {
                // #region agent log
                try {
                    JSONObject d = new JSONObject();
                    d.put("phase", failPhase);
                    d.put("arlLen", arl != null ? arl.length() : 0);
                    DeezerDebugLog.log(logCtx, "DeezerClient.testSession", "failed", "B", d);
                } catch (Exception ignored) {}
                // #endregion
                return false;
            }
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("phase", "initSession");
                d.put("ok", true);
                d.put("hasLicense", licenseToken != null && !licenseToken.isEmpty());
                DeezerDebugLog.log(logCtx, "DeezerClient.testSession", "init ok", "B", d);
            } catch (Exception ignored) {}
            // #endregion
            failPhase = "resolveTrack";
            DeezerTrackResolver resolver = new DeezerTrackResolver(this);
            DeezerTrackData track = resolver.resolveTrack(917265L);
            boolean ok = track != null && track.trackToken != null && !track.trackToken.isEmpty();
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("phase", "resolveTrack");
                d.put("ok", ok);
                d.put("hasToken", track != null && track.trackToken != null && !track.trackToken.isEmpty());
                DeezerDebugLog.log(logCtx, "DeezerClient.testSession", ok ? "track ok" : "track fail", "C", d);
            } catch (Exception ignored) {}
            // #endregion
            return ok;
        } catch (Exception e) {
            sessionValid = false;
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("phase", failPhase);
                d.put("error", e.getClass().getSimpleName());
                String msg = e.getMessage();
                if (msg != null && msg.length() > 200) msg = msg.substring(0, 200);
                d.put("msg", msg != null ? msg : "");
                DeezerDebugLog.log(logCtx, "DeezerClient.testSession", "exception", "A", d);
            } catch (Exception ignored) {}
            // #endregion
            return false;
        }
    }

    private void setSongQuality(String qualityConfig, boolean flacSupported) {
        if (flacSupported) {
            soundFormat = DeezerAccount.QUALITY_FLAC.equals(qualityConfig) ? "FLAC" : "MP3_320";
        } else {
            soundFormat = "MP3_128";
        }
    }

    public String fileExtension() {
        return "FLAC".equals(soundFormat) ? "flac" : "mp3";
    }

    public byte[] getAuthenticated(String url) throws IOException {
        return execute(buildAuthRequest(url).build());
    }

    public String getAuthenticatedText(String url) throws IOException {
        return new String(getAuthenticated(url), "UTF-8");
    }

    public Response executeAuthenticated(Request.Builder builder) throws IOException {
        applyAuthHeaders(builder);
        return executeRaw(builder.build());
    }

    public Request.Builder newAuthRequest(String url) {
        return applyAuthHeaders(new Request.Builder().url(url));
    }

    private Request.Builder buildAuthRequest(String url) {
        return applyAuthHeaders(new Request.Builder().url(url));
    }

    private Request.Builder applyAuthHeaders(Request.Builder b) {
        b.header("User-Agent", USER_AGENT);
        b.header("Pragma", "no-cache");
        b.header("Origin", "https://www.deezer.com");
        b.header("Accept", "*/*");
        b.header("Accept-Language", "en-US,en;q=0.9");
        b.header("Referer", "https://www.deezer.com/login");
        b.header("Cookie", "arl=" + arl + "; comeback=1");
        return b;
    }

    public byte[] getPublic(String url) throws IOException {
        TlsHelper.ensureSecurityProvider();
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();
        return execute(req);
    }

    private byte[] execute(Request req) throws IOException {
        Response resp = executeRaw(req);
        try {
            if (resp.body() == null) throw new IOException("Empty body");
            return resp.body().bytes();
        } finally {
            if (resp.body() != null) resp.body().close();
        }
    }

    Response executeRaw(Request req) throws IOException {
        TlsHelper.ensureSecurityProvider();
        OkHttpClient client = TlsHelper.client();
        Response resp = client.newCall(req).execute();
        if (!resp.isSuccessful()) {
            int code = resp.code();
            resp.close();
            throw new IOException("HTTP " + code + " for " + req.url());
        }
        return resp;
    }

    public static String formatDuration(int sec) {
        if (sec <= 0) return "";
        int m = sec / 60;
        int s = sec % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    public static String formatQualityLabel(String soundFormat) {
        if ("FLAC".equals(soundFormat)) return "FLAC";
        if ("MP3_320".equals(soundFormat)) return "320";
        return "128";
    }

    /** Authenticated gw-light POST — requires {@link #initSession()} first. */
    public JSONObject postGwLight(String method, JSONObject payload) throws IOException {
        if (!sessionValid && !initSession()) {
            throw new IOException("Deezer session unavailable");
        }
        int cid = (int) (Math.random() * 1000000000L);
        String url = GW_LIGHT + "?method=" + method + "&input=3&api_version=1.0&api_token="
                + (apiToken != null ? apiToken : "") + "&cid=" + cid;
        String json = payload != null ? payload.toString() : "{}";
        RequestBody body = RequestBody.create(JSON_MEDIA, json);
        Request req = applyAuthHeaders(new Request.Builder().url(url).post(body)).build();
        try {
            JSONObject root = new JSONObject(new String(execute(req), "UTF-8"));
            if (root.has("error") && !root.isNull("error")) {
                JSONObject err = root.optJSONObject("error");
                String msg = "Deezer API error";
                if (err != null) {
                    java.util.Iterator<String> keys = err.keys();
                    if (keys.hasNext()) {
                        String k = keys.next();
                        msg = err.optString(k, msg);
                    }
                }
                throw new IOException(msg);
            }
            return root;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() != null ? e.getMessage() : "gw-light failed");
        }
    }

    /** Permanently delete a playlist from the user's Deezer account. */
    public void deletePlaylist(long playlistId) throws IOException {
        if (playlistId <= 0) throw new IOException("Invalid playlist");
        try {
            JSONObject payload = new JSONObject();
            payload.put("playlist_id", playlistId);
            postGwLight("playlist.delete", payload);
        } catch (IOException first) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("PLAYLIST_ID", playlistId);
                postGwLight("playlist.delete", payload);
            } catch (Exception e) {
                throw first;
            }
        } catch (Exception e) {
            throw new IOException(e.getMessage() != null ? e.getMessage() : "Delete failed");
        }
    }

    /** Add one track to a Deezer playlist. */
    public void addTrackToPlaylist(long playlistId, long trackId) throws IOException {
        if (playlistId <= 0 || trackId <= 0) throw new IOException("Invalid playlist or track");
        JSONObject payload = new JSONObject();
        try {
            payload.put("PLAYLIST_ID", playlistId);
            JSONArray songs = new JSONArray();
            JSONArray row = new JSONArray();
            row.put(String.valueOf(trackId));
            songs.put(row);
            payload.put("songs", songs);
            postGwLight("song.addToPlaylist", payload);
        } catch (Exception e) {
            throw new IOException(e.getMessage() != null ? e.getMessage() : "Add to playlist failed");
        }
    }
}
