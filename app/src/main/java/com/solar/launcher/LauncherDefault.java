package com.solar.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONObject;

/**
 * Makes Solar the default HOME launcher on Solar ROM (platform app).
 * ponytail: first flash disables org.rockbox via 99SolarInit.sh; this sets preferred activity in PM.
 */
public final class LauncherDefault {
    private static final String TAG = "LauncherDefault";

    private LauncherDefault() {}

    /** Call on boot and app start when Solar is the active launcher. */
    public static void ensureDefaultHome(Context context) {
        if (context == null) return;
        if (!isSolarEnabled(context)) {
            // #region agent log
            JSONObject d = new JSONObject();
            try { d.put("solarEnabled", false); } catch (Exception ignored) {}
            DebugAgentLog.log(context, "LauncherDefault.ensureDefaultHome", "skip disabled", "H-L1", d);
            // #endregion
            return;
        }
        try {
            setPreferredHome(context);
            Log.i(TAG, "Solar set as preferred HOME");
            // #region agent log
            JSONObject d = new JSONObject();
            try { d.put("preferredSet", true); } catch (Exception ignored) {}
            DebugAgentLog.log(context, "LauncherDefault.ensureDefaultHome", "preferred HOME set", "H-L2", d);
            // #endregion
        } catch (Exception e) {
            Log.w(TAG, "set preferred HOME failed: " + e.getMessage());
            // #region agent log
            JSONObject d = new JSONObject();
            try { d.put("error", e.getMessage()); } catch (Exception ignored) {}
            DebugAgentLog.log(context, "LauncherDefault.ensureDefaultHome", "preferred HOME failed", "H-L2", d);
            // #endregion
        }
    }

    private static boolean isSolarEnabled(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0);
            return info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void setPreferredHome(Context context) {
        PackageManager pm = context.getPackageManager();
        ComponentName home = new ComponentName(context, MainActivity.class);
        pm.clearPackagePreferredActivities(context.getPackageName());
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addPreferredActivity(filter, IntentFilter.MATCH_CATEGORY_EMPTY,
                new ComponentName[] { home }, home);
    }
}
