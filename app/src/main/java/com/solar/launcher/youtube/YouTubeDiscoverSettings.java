package com.solar.launcher.youtube;

import android.content.SharedPreferences;

import java.util.Locale;

/** Small preset policy for wheel-friendly Discover and metadata settings. */
public final class YouTubeDiscoverSettings {

    public static final String PREFS_NAME = "SOLAR_SETTINGS";
    public static final String PREF_REGION = "youtube_metadata_region";
    public static final String PREF_CACHE_BYTES = "youtube_metadata_cache_bytes";

    public static final long DEFAULT_CACHE_BYTES = 1024L * 1024L;
    private static final long[] CACHE_BYTES = {
            512L * 1024L,
            DEFAULT_CACHE_BYTES,
            2L * 1024L * 1024L,
            4L * 1024L * 1024L
    };
    private static final String[] REGIONS = {
            "", "US", "CA", "GB", "AU", "DE", "FR", "JP", "KR"
    };
    private static final int[][] DURATIONS = {
            {0, 0},
            {60, 0},
            {120, 20 * 60},
            {20 * 60, 0}
    };

    private YouTubeDiscoverSettings() {}

    public static String effectiveRegion(SharedPreferences prefs,
            String localeCountry) {
        String configured = prefs != null ? prefs.getString(PREF_REGION, "") : "";
        String clean = normalizeRegion(configured);
        if (clean.length() > 0) return clean;
        clean = normalizeRegion(localeCountry);
        return clean.length() > 0 ? clean : "US";
    }

    public static String configuredRegion(SharedPreferences prefs) {
        return normalizeRegion(prefs != null ? prefs.getString(PREF_REGION, "") : "");
    }

    public static String nextRegion(String current) {
        String clean = normalizeRegion(current);
        for (int i = 0; i < REGIONS.length; i++) {
            if (REGIONS[i].equals(clean)) return REGIONS[(i + 1) % REGIONS.length];
        }
        return REGIONS[0];
    }

    public static long nextCacheBytes(long current) {
        for (int i = 0; i < CACHE_BYTES.length; i++) {
            if (CACHE_BYTES[i] == current) {
                return CACHE_BYTES[(i + 1) % CACHE_BYTES.length];
            }
        }
        return DEFAULT_CACHE_BYTES;
    }

    public static long cacheBytes(SharedPreferences prefs) {
        long configured = prefs != null
                ? prefs.getLong(PREF_CACHE_BYTES, DEFAULT_CACHE_BYTES)
                : DEFAULT_CACHE_BYTES;
        for (long allowed : CACHE_BYTES) {
            if (configured == allowed) return configured;
        }
        return DEFAULT_CACHE_BYTES;
    }

    public static int durationPreset(int minSeconds, int maxSeconds) {
        for (int i = 0; i < DURATIONS.length; i++) {
            if (DURATIONS[i][0] == minSeconds && DURATIONS[i][1] == maxSeconds) {
                return i;
            }
        }
        return 0;
    }

    public static int nextDurationPreset(int current) {
        return (Math.max(0, current) + 1) % DURATIONS.length;
    }

    public static int minDurationSeconds(int preset) {
        int safe = preset >= 0 && preset < DURATIONS.length ? preset : 0;
        return DURATIONS[safe][0];
    }

    public static int maxDurationSeconds(int preset) {
        int safe = preset >= 0 && preset < DURATIONS.length ? preset : 0;
        return DURATIONS[safe][1];
    }

    private static String normalizeRegion(String value) {
        String clean = value != null ? value.trim().toUpperCase(Locale.US) : "";
        return clean.matches("[A-Z]{2}") ? clean : "";
    }
}
