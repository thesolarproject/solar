package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Y1/A5: UMS on by default. Y2 historically MTP-only experiment flag.
 */
public final class UsbMassStorageExperiment {
    public static final String PREF = "usb_mass_storage_experiment";

    private UsbMassStorageExperiment() {}

    public static boolean isEnabled(Context context) {
        if (context == null) return true;
        // Y1 product always supports disk mode.
        SharedPreferences p = context.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        // Default true for non-Y2. Only explicit false disables.
        return p.getBoolean(PREF, true);
    }
}
