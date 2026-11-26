package com.solar.launcher.ebook;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 2026-11-26 — Z-Library session for the E-Book tab.
 * Layman: the two session cookies Z-Library sets when you are logged in.
 * Tech: EAPI auth is `remix_userid` / `remix_userkey`; the mobile login endpoint is
 *   refused for many accounts, so cookies are pasted from a PC browser instead
 *   (same idea as Deezer's arl). Treat them like a password.
 */
public final class ZlibAccount {
    public static final String PREFS_NAME = "SOLAR_ZLIB";
    public static final String PREF_USER_ID = "zlib_user_id";
    public static final String PREF_USER_KEY = "zlib_user_key";
    public static final String PREF_DOMAIN = "zlib_domain";

    /** Mirrors that answered EAPI as of 2026-Q4; the first healthy one is used. */
    public static final String[] DOMAINS = {
            "https://z-lib.gd",
            "https://z-lib.gl",
            "https://z-library.ec",
            "https://zlib.bz",
            "https://z-lib.by",
            "https://z-library.qa",
    };

    private ZlibAccount() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isConfigured(SharedPreferences p) {
        return p != null
                && nonEmpty(p.getString(PREF_USER_ID, null))
                && nonEmpty(p.getString(PREF_USER_KEY, null));
    }

    public static boolean isConfigured(Context ctx) {
        return ctx != null && isConfigured(prefs(ctx));
    }

    public static void save(SharedPreferences p, String userId, String userKey, String domain) {
        if (p == null) return;
        SharedPreferences.Editor e = p.edit();
        e.putString(PREF_USER_ID, userId != null ? userId.trim() : "");
        e.putString(PREF_USER_KEY, userKey != null ? userKey.trim() : "");
        if (domain != null && domain.trim().length() > 0) {
            e.putString(PREF_DOMAIN, normalizeDomain(domain));
        }
        e.commit();
    }

    public static void clear(SharedPreferences p) {
        if (p == null) return;
        p.edit().remove(PREF_USER_ID).remove(PREF_USER_KEY).commit();
    }

    public static String userId(SharedPreferences p) {
        return p != null ? p.getString(PREF_USER_ID, "") : "";
    }

    public static String userKey(SharedPreferences p) {
        return p != null ? p.getString(PREF_USER_KEY, "") : "";
    }

    public static String domain(SharedPreferences p) {
        String d = p != null ? p.getString(PREF_DOMAIN, null) : null;
        return d != null && d.length() > 0 ? d : DOMAINS[0];
    }

    public static void saveDomain(SharedPreferences p, String domain) {
        if (p == null || domain == null) return;
        p.edit().putString(PREF_DOMAIN, normalizeDomain(domain)).commit();
    }

    /** https origin, no trailing slash. */
    static String normalizeDomain(String domain) {
        String d = domain.trim();
        if (!d.startsWith("http")) d = "https://" + d;
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        return d;
    }

    private static boolean nonEmpty(String s) {
        return s != null && s.trim().length() > 0;
    }
}
