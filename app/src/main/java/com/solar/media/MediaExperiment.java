package com.solar.media;

import android.content.SharedPreferences;

/**
 * 2026-07-29: Consolidated feature flag for media-server experiments.
 *
 * Both Plex and Jellyfin had identical Experiment classes differing only in the
 * SharedPreferences key.  Callers pass their own key — no per-server class needed.
 */
public final class MediaExperiment {

    private MediaExperiment() {}

    public static boolean isEnabled(SharedPreferences prefs, String prefKey) {
        return prefs != null && prefs.getBoolean(prefKey, false);
    }

    public static void setEnabled(SharedPreferences prefs, String prefKey, boolean enabled) {
        if (prefs != null) {
            prefs.edit().putBoolean(prefKey, enabled).apply();
        }
    }
}
