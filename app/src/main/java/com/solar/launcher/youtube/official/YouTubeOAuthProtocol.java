package com.solar.launcher.youtube.official;

import org.json.JSONObject;

/**
 * Pure parser/state policy for Google's limited-input OAuth flow.
 *
 * Kept Android-free so every server response and polling transition is covered
 * by local unit tests.
 */
public final class YouTubeOAuthProtocol {

    public static final String READ_ONLY_SCOPE =
            "https://www.googleapis.com/auth/youtube.readonly";
    public static final String DEVICE_GRANT_TYPE =
            "urn:ietf:params:oauth:grant-type:device_code";

    public enum PollState {
        AUTHORIZATION_PENDING,
        SLOW_DOWN,
        GRANTED,
        ACCESS_DENIED,
        EXPIRED,
        INVALID_CLIENT,
        FAILED
    }

    public static final class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUrl;
        public final int expiresInSec;
        public final int intervalSec;

        DeviceCode(String deviceCode, String userCode, String verificationUrl,
                int expiresInSec, int intervalSec) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.expiresInSec = expiresInSec;
            this.intervalSec = intervalSec;
        }
    }

    public static final class PollResult {
        public final PollState state;
        public final String accessToken;
        public final String refreshToken;
        public final String scope;
        public final int expiresInSec;
        public final String safeReason;

        PollResult(PollState state, String accessToken, String refreshToken,
                String scope, int expiresInSec, String safeReason) {
            this.state = state;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.scope = scope;
            this.expiresInSec = expiresInSec;
            this.safeReason = safeReason;
        }
    }

    private YouTubeOAuthProtocol() {}

    public static DeviceCode parseDeviceCode(String json) throws Exception {
        JSONObject object = new JSONObject(nonNull(json));
        String deviceCode = object.optString("device_code", "");
        String userCode = object.optString("user_code", "");
        String verificationUrl = object.optString("verification_url",
                object.optString("verification_uri", ""));
        int expiresIn = object.optInt("expires_in", 0);
        int interval = object.optInt("interval", 5);
        if (deviceCode.length() == 0 || userCode.length() == 0
                || verificationUrl.length() == 0 || expiresIn <= 0) {
            throw new IllegalArgumentException("invalid_device_code_response");
        }
        if (interval < 1) interval = 5;
        return new DeviceCode(deviceCode, userCode, verificationUrl, expiresIn, interval);
    }

    public static PollResult parsePollResponse(int httpCode, String json) {
        JSONObject object;
        try {
            object = new JSONObject(nonNull(json));
        } catch (Exception e) {
            return new PollResult(PollState.FAILED, "", "", "", 0,
                    "invalid_oauth_response");
        }
        String accessToken = object.optString("access_token", "");
        if (httpCode >= 200 && httpCode < 300 && accessToken.length() > 0) {
            return new PollResult(
                    PollState.GRANTED,
                    accessToken,
                    object.optString("refresh_token", ""),
                    object.optString("scope", ""),
                    object.optInt("expires_in", 3600),
                    "");
        }
        String error = object.optString("error",
                object.optString("error_code", ""));
        if ("authorization_pending".equals(error)) {
            return failure(PollState.AUTHORIZATION_PENDING, error);
        }
        if ("slow_down".equals(error) || "rate_limit_exceeded".equals(error)) {
            return failure(PollState.SLOW_DOWN, error);
        }
        if ("access_denied".equals(error)) {
            return failure(PollState.ACCESS_DENIED, error);
        }
        if ("expired_token".equals(error) || "invalid_grant".equals(error)) {
            return failure(PollState.EXPIRED, error);
        }
        if ("invalid_client".equals(error)) {
            return failure(PollState.INVALID_CLIENT, error);
        }
        return failure(PollState.FAILED,
                error.length() > 0 ? error : "oauth_http_" + httpCode);
    }

    public static int nextPollIntervalSec(int currentIntervalSec, PollState state) {
        int current = Math.max(1, currentIntervalSec);
        if (state == PollState.SLOW_DOWN) {
            return Math.min(60, current + 5);
        }
        return current;
    }

    public static long expirationEpochMs(long nowMs, int expiresInSec) {
        return nowMs + Math.max(0, expiresInSec) * 1000L;
    }

    private static PollResult failure(PollState state, String reason) {
        return new PollResult(state, "", "", "", 0, reason);
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }
}
