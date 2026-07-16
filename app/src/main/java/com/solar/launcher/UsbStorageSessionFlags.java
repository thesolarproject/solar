package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Prefs for Settings → USB (auto-connect, suppress prompt). */
public final class UsbStorageSessionFlags {
    public static final String PREF_AUTO_CONNECT = "usb_auto_connect";
    public static final String PREF_SUPPRESS_PROMPT = "usb_suppress_connect_prompt";
    public static final String PREF_MANUAL_DISABLE = "usb_manual_disable";
    private static final String PREFS = "SOLAR_SETTINGS";

    private UsbStorageSessionFlags() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAutoConnectEnabled(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences p = prefs(ctx);
        return p.getBoolean(PREF_AUTO_CONNECT, false) && !p.getBoolean(PREF_MANUAL_DISABLE, false);
    }

    public static boolean isSuppressPrompt(Context ctx) {
        return ctx != null && prefs(ctx).getBoolean(PREF_SUPPRESS_PROMPT, false);
    }

    public static boolean shouldOfferUsbConnectPromptAfterBootSettle(Context ctx) {
        if (ctx == null) return false;
        if (isSuppressPrompt(ctx)) return false;
        return UsbMassStorageExperiment.isEnabled(ctx);
    }
}
