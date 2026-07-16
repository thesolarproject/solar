package com.solar.launcher;

import android.content.Intent;

import com.solar.launcher.contracts.SolarIntents;

/** Routes hold-Back quick menu to overlay APK when installed. */
public final class QuickMenuBridge {
    private QuickMenuBridge() {}

    public static void show(android.content.Context context, String tier) {
        if (context == null) return;
        Intent intent = new Intent(SolarIntents.ACTION_QUICKMENU_SHOW);
        intent.setPackage(SolarIntents.PKG_QUICKMENU);
        intent.putExtra(SolarIntents.EXTRA_QUICKMENU_TIER, tier != null ? tier : "root");
        context.sendBroadcast(intent);
    }

    public static void dismiss(android.content.Context context) {
        if (context == null) return;
        Intent intent = new Intent(SolarIntents.ACTION_QUICKMENU_DISMISS);
        intent.setPackage(SolarIntents.PKG_QUICKMENU);
        context.sendBroadcast(intent);
    }
}
