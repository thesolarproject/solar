package com.solar.launcher;

import android.content.SharedPreferences;

/** Flow master toggle — kept separate so tests avoid MainActivity. */
public final class FlowSettings {
    /** Same storage key as legacy debug Flow experiment. */
    public static final String PREF_FLOW_ENABLED = "debug_flow_enabled";

    private FlowSettings() {}

    /** Default on for new installs; explicit false is preserved. */
    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs == null || prefs.getBoolean(PREF_FLOW_ENABLED, true);
    }
}
