package com.solar.launcher.youtube.official;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Private-app token persistence for API 17.
 *
 * Android 4.2 predates the modern AES Android Keystore. MODE_PRIVATE is the
 * strongest platform facility available on the target, but it cannot protect
 * credentials from root or offline firmware access. Tokens are intentionally
 * excluded from logs and diagnostic bundles.
 */
public final class YouTubeTokenStore {

    private static final String PREFS = "solar_youtube_oauth_private";
    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";
    private static final String EXPIRES_AT = "expires_at";
    private static final String SCOPE = "scope";

    private final SharedPreferences prefs;

    public YouTubeTokenStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(String accessToken, String refreshToken,
            long expiresAtMs, String scope) {
        SharedPreferences.Editor edit = prefs.edit()
                .putString(ACCESS, nonNull(accessToken))
                .putLong(EXPIRES_AT, expiresAtMs)
                .putString(SCOPE, nonNull(scope));
        if (refreshToken != null && refreshToken.length() > 0) {
            edit.putString(REFRESH, refreshToken);
        }
        edit.commit();
    }

    public synchronized String validAccessToken(long nowMs) {
        String token = prefs.getString(ACCESS, "");
        long expiresAt = prefs.getLong(EXPIRES_AT, 0L);
        return token != null && token.length() > 0 && expiresAt - nowMs > 60_000L
                ? token : "";
    }

    public synchronized String refreshToken() {
        String token = prefs.getString(REFRESH, "");
        return token != null ? token : "";
    }

    public synchronized boolean hasAccount() {
        return refreshToken().length() > 0 || validAccessToken(System.currentTimeMillis()).length() > 0;
    }

    public synchronized void clear() {
        prefs.edit().clear().commit();
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }
}
