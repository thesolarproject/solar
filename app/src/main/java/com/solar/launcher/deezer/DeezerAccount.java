package com.solar.launcher.deezer;

import android.content.SharedPreferences;

/** Stores Deezer ARL cookie and quality preferences (impersonation auth). */
public final class DeezerAccount {
    /** Must match MainActivity {@code PREFS} / {@link com.solar.launcher.LibraryBrowsePrefs}. */
    public static final String PREFS_NAME = "SOLAR_SETTINGS";
    /** Legacy web-setup bug wrote here — migrated on read. */
    private static final String LEGACY_PREFS_NAME = "solar_prefs";

    public static final String PREF_ARL = "deezer_arl";
    public static final String PREF_ENABLED = "deezer_enabled";
    public static final String PREF_QUALITY = "deezer_quality";
    public static final String PREF_USER_NAME = "deezer_user_name";
    public static final String PREF_USER_ID = "deezer_user_id";
    public static final String PREF_PREMIUM = "deezer_premium";
    public static final String PREF_INCLUDE_IN_GET_MUSIC = "deezer_include_in_get_music";

    public static final String QUALITY_MP3 = "mp3";
    public static final String QUALITY_FLAC = "flac";

    private static final int MIN_ARL_LEN = 64;

    /**
     * ponytail: REMOVE before public launch — shared tester Deezer ARL.
     * Users can replace it anytime via Settings → Deezer → Set up on PC.
     */
    private static final String BUNDLED_DEMO_ARL =
            "69e3ba43fab2a4adc88dbcfc0cb5b5b7eee40cc6fbd4f935d9c58768202f43e3dc994087cfdcc5c7fedd0d9e0d0188da256184ac3a22f726db7e1b77ff2bb657f57270ff58e705da7f7ace0e2de2b7d22f67d3e3980a0da9b221ee65c3b5a33a";

    private DeezerAccount() {}

    public static boolean hasBundledDemoArl() {
        return BUNDLED_DEMO_ARL.length() >= MIN_ARL_LEN;
    }

    /** Pre-fill prefs when no user ARL was saved yet (test builds only). */
    public static void seedBundledDemoArlIfNeeded(SharedPreferences prefs) {
        if (prefs == null || hasArl(prefs) || !hasBundledDemoArl()) return;
        saveArl(prefs, BUNDLED_DEMO_ARL);
    }

    public static boolean isUsingDemoArl(SharedPreferences prefs) {
        if (prefs == null || !hasBundledDemoArl()) return false;
        return BUNDLED_DEMO_ARL.equals(loadArl(prefs));
    }

    public static boolean hasArl(SharedPreferences prefs) {
        if (prefs == null) return false;
        String arl = prefs.getString(PREF_ARL, "");
        return arl != null && arl.trim().length() >= MIN_ARL_LEN;
    }

    public static String loadArl(SharedPreferences prefs) {
        if (prefs == null) return "";
        String arl = prefs.getString(PREF_ARL, "");
        return arl != null ? arl.trim() : "";
    }

    /** Copy ARL from legacy {@code solar_prefs}; then seed bundled demo if still empty. */
    public static void migrateLegacyArl(android.content.Context context, SharedPreferences prefs) {
        if (context == null || prefs == null) return;
        if (!hasArl(prefs)) {
            SharedPreferences legacy = context.getApplicationContext()
                    .getSharedPreferences(LEGACY_PREFS_NAME, android.content.Context.MODE_PRIVATE);
            String legacyArl = legacy.getString(PREF_ARL, "");
            if (legacyArl != null && legacyArl.trim().length() >= MIN_ARL_LEN) {
                saveArl(prefs, legacyArl.trim());
                String user = legacy.getString(PREF_USER_NAME, "");
                if (user != null && !user.isEmpty()) {
                    saveSessionInfo(prefs, user,
                            legacy.getString(PREF_USER_ID, ""),
                            legacy.getBoolean(PREF_PREMIUM, false));
                }
            }
        }
        seedBundledDemoArlIfNeeded(prefs);
    }

    public static boolean isEnabled(SharedPreferences prefs) {
        if (prefs == null) return false;
        return prefs.getBoolean(PREF_ENABLED, true);
    }

    public static String loadQuality(SharedPreferences prefs) {
        if (prefs == null) return QUALITY_MP3;
        String q = prefs.getString(PREF_QUALITY, QUALITY_MP3);
        return QUALITY_FLAC.equals(q) ? QUALITY_FLAC : QUALITY_MP3;
    }

    public static void saveArl(SharedPreferences prefs, String arl) {
        if (prefs == null) return;
        prefs.edit().putString(PREF_ARL, arl != null ? arl.trim() : "").commit();
    }

    /** User-visible account name; masks the bundled demo session. */
    public static String displayLabel(SharedPreferences prefs, String demoAccountLabel) {
        if (isUsingDemoArl(prefs)) {
            return demoAccountLabel != null ? demoAccountLabel : "Test/Demo Account";
        }
        return displayUser(prefs);
    }

    public static void saveSessionInfo(SharedPreferences prefs, String userName, String userId,
            boolean premium) {
        if (prefs == null) return;
        // Never persist the real Deezer display name while the bundled tester ARL is active.
        if (isUsingDemoArl(prefs)) {
            userName = "";
        }
        prefs.edit()
                .putString(PREF_USER_NAME, userName != null ? userName : "")
                .putString(PREF_USER_ID, userId != null ? userId : "")
                .putBoolean(PREF_PREMIUM, premium)
                .commit();
    }

    public static void clear(SharedPreferences prefs) {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit()
                .remove(PREF_USER_NAME)
                .remove(PREF_USER_ID)
                .putBoolean(PREF_PREMIUM, false);
        if (hasBundledDemoArl()) {
            e.putString(PREF_ARL, BUNDLED_DEMO_ARL);
        } else {
            e.remove(PREF_ARL);
        }
        e.commit();
    }

    public static String displayUser(SharedPreferences prefs) {
        if (prefs == null) return "";
        String name = prefs.getString(PREF_USER_NAME, "");
        return name != null && !name.isEmpty() ? name : "";
    }

    public static long loadUserId(SharedPreferences prefs) {
        if (prefs == null) return 0;
        try {
            return Long.parseLong(prefs.getString(PREF_USER_ID, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean includeInGetMusic(SharedPreferences prefs) {
        if (prefs == null) return true;
        return prefs.getBoolean(PREF_INCLUDE_IN_GET_MUSIC, true);
    }
}
