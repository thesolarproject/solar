package com.solar.launcher.video;

import android.content.Context;
import android.content.SharedPreferences;

/** Video playback user prefs. */
public final class VideoSettings {
    public static final String PREF_SLEEP_DURING_PLAYBACK = "sleep_during_playback";

    private static final String PREFS = "video_settings";

    private VideoSettings() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** When true, center-hold and context lock may turn the screen off during video playback. */
    public static boolean getSleepDuringPlayback(Context ctx) {
        return prefs(ctx).getBoolean(PREF_SLEEP_DURING_PLAYBACK, true);
    }

    public static void setSleepDuringPlayback(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_SLEEP_DURING_PLAYBACK, enabled).commit();
    }
}
