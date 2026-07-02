package com.solar.launcher.radio;

import android.content.SharedPreferences;

import com.solar.launcher.media.MediaSuiteHost;

/** Debug experiment gate — Radio/FM/internet radio stay hidden until enabled. */
public final class RadioExperiment {
    public static final String PREF_RADIO_EXPERIMENT = "solar_radio_experiment";

    private RadioExperiment() {}

    /** Off by default; enable from Settings → Debug and Experiments. */
    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_RADIO_EXPERIMENT, false);
    }

    /** Hub + FM browse + internet browse screens. */
    public static boolean isRadioScreenState(int state) {
        return state >= MediaSuiteHost.STATE_RADIO && state <= MediaSuiteHost.STATE_RADIO_NET_BROWSE;
    }
}
