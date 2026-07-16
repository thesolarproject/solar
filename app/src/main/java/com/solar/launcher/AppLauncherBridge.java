package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Bridge so feature modules avoid duplicating launch logic. */
final class AppLauncherBridge {
    private AppLauncherBridge() {}

    static boolean launch(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        PackageManager pm = context.getPackageManager();
        Intent launch = pm != null ? pm.getLaunchIntentForPackage(packageName) : null;
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        return true;
    }
}
