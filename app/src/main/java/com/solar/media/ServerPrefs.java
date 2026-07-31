package com.solar.media;

import android.content.SharedPreferences;

/**
 * 2026-07-30: Shared SharedPreferences helpers for Navidrome/Jellyfin/Plex Prefs.
 * Consolidates null-check + editor-flush boilerplate that was triplicated.
 */
public final class ServerPrefs {
    private ServerPrefs() {}

    /** Null-safe: returns false if prefs is null or any key's value is blank. */
    public static boolean isConfigured(SharedPreferences prefs, String... keys) {
        if (prefs == null) return false;
        for (String key : keys) {
            String v = prefs.getString(key, "");
            if (v == null || v.trim().isEmpty()) return false;
        }
        return true;
    }

    /** Commit trimmed key-value pairs in a single editor transaction. */
    public static void putTrimmed(SharedPreferences prefs, String... keyValuePairs) {
        if ((keyValuePairs.length & 1) != 0) return; // defensive: require even pairs
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String val = keyValuePairs[i + 1];
            e.putString(keyValuePairs[i], val != null ? val.trim() : "");
        }
        e.commit();
    }
}
