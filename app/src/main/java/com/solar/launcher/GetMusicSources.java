package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.deezer.DeezerAccount;
import com.solar.launcher.soulseek.SoulseekAccount;

/** Which backends participate in Get Music / Get more / Find like this. */
public final class GetMusicSources {
    public static final int MODE_REACH_ONLY = 0;
    public static final int MODE_UNIFIED = 1;
    public static final int MODE_DEEZER_ONLY = 2;

    /** Subtitle kind for the Get Music status / hint strip. */
    public static final int SUBTITLE_BOTH = 0;
    public static final int SUBTITLE_SOULSEEK = 1;
    public static final int SUBTITLE_DEEZER = 2;
    public static final int SUBTITLE_NONE = 3;

    private GetMusicSources() {}

    public static boolean reachInGetMusic(SharedPreferences prefs, boolean soulseekActive) {
        return soulseekActive && ConnectivityHelper.isReachPeerOk();
    }

    /** Alias — Reach search in unified Get Music. */
    public static boolean reachSearchInGetMusic(SharedPreferences prefs, boolean soulseekActive) {
        return reachInGetMusic(prefs, soulseekActive);
    }

    /**
     * Deezer opted in for Get Music search (public API — no live session required).
     */
    public static boolean deezerSearchInGetMusic(SharedPreferences prefs, boolean deezerActive) {
        return deezerActive;
    }

    /** Deezer opted in and ARL saved — session may still be warming up. */
    public static boolean deezerConfiguredForGetMusic(SharedPreferences prefs, boolean deezerActive) {
        return deezerActive && DeezerAccount.hasArl(prefs);
    }

    /** Deezer download / play / queue in Get Music (needs valid session). */
    public static boolean deezerPlayInGetMusic(SharedPreferences prefs, boolean deezerActive) {
        return deezerConfiguredForGetMusic(prefs, deezerActive)
                && ConnectivityHelper.isDeezerLoginOk();
    }

    /** @deprecated use {@link #deezerPlayInGetMusic} */
    public static boolean deezerInGetMusic(SharedPreferences prefs, boolean deezerActive) {
        return deezerPlayInGetMusic(prefs, deezerActive);
    }

    public static boolean anyAvailable(SharedPreferences prefs, boolean soulseekActive, boolean deezerActive) {
        return reachSearchInGetMusic(prefs, soulseekActive)
                || deezerSearchInGetMusic(prefs, deezerActive);
    }

    public static boolean anyAvailable(Context context, SharedPreferences prefs,
            boolean soulseekActive, boolean deezerActive) {
        if (context != null && !ConnectivityHelper.isOnline(context)) return false;
        return anyAvailable(prefs, soulseekActive, deezerActive);
    }

    public static int resolveMode(SharedPreferences prefs, boolean soulseekActive, boolean deezerActive) {
        return resolveGetMusicUiMode(prefs, soulseekActive, deezerActive);
    }

    /** Which backends to query for a Get Music search. */
    public static int resolveGetMusicUiMode(SharedPreferences prefs, boolean soulseekActive,
            boolean deezerActive) {
        boolean reach = reachSearchInGetMusic(prefs, soulseekActive);
        boolean deezer = deezerSearchInGetMusic(prefs, deezerActive);
        if (reach && deezer) return MODE_UNIFIED;
        if (deezer) return MODE_DEEZER_ONLY;
        if (reach) return MODE_REACH_ONLY;
        return MODE_REACH_ONLY;
    }

    /** Dynamic subtitle for Get Music screens based on active search sources. */
    public static int activeSourceSubtitle(SharedPreferences prefs, boolean soulseekActive,
            boolean deezerActive) {
        boolean reach = reachSearchInGetMusic(prefs, soulseekActive);
        boolean deezer = deezerSearchInGetMusic(prefs, deezerActive);
        if (reach && deezer) return SUBTITLE_BOTH;
        if (reach) return SUBTITLE_SOULSEEK;
        if (deezer) return SUBTITLE_DEEZER;
        return SUBTITLE_NONE;
    }

    public static int activeSourceSubtitleRes(SharedPreferences prefs, boolean soulseekActive,
            boolean deezerActive) {
        switch (activeSourceSubtitle(prefs, soulseekActive, deezerActive)) {
            case SUBTITLE_BOTH: return R.string.get_music_sources_both;
            case SUBTITLE_SOULSEEK: return R.string.get_music_sources_soulseek;
            case SUBTITLE_DEEZER: return R.string.get_music_sources_deezer;
            default: return R.string.get_music_sources_none;
        }
    }
}
