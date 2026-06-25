package com.solar.launcher;

import android.content.SharedPreferences;

import com.solar.launcher.deezer.DeezerAccount;
import com.solar.launcher.soulseek.SoulseekAccount;

/** Reach master + per-service enable flags (Soulseek / Deezer). */
public final class ReachPolicy {
    private ReachPolicy() {}

    public static boolean isMasterEnabled(SharedPreferences prefs) {
        if (prefs == null) return true;
        return prefs.getBoolean(SoulseekAccount.PREF_REACH_ENABLED, true);
    }

    public static boolean isSoulseekPrefEnabled(SharedPreferences prefs) {
        if (prefs == null) return true;
        return prefs.getBoolean(SoulseekAccount.PREF_SOULSEEK_ENABLED, true);
    }

    public static boolean isDeezerPrefEnabled(SharedPreferences prefs) {
        return DeezerAccount.isEnabled(prefs);
    }

    /** Master on and Soulseek service on — client, messaging, Get Music Reach. */
    public static boolean isSoulseekActive(SharedPreferences prefs) {
        return isMasterEnabled(prefs) && isSoulseekPrefEnabled(prefs);
    }

    /** Master on and Deezer service on — session, Get Music Deezer. */
    public static boolean isDeezerActive(SharedPreferences prefs) {
        return isMasterEnabled(prefs) && isDeezerPrefEnabled(prefs);
    }
}
