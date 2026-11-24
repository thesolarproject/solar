package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.solar.home.policy.HomeTargetPolicy;

import java.io.File;
import java.util.List;

/**
 * 2026-07-05 — No-reboot Solar ↔ Rockbox handoff via /data/data/switch-to-stock.sh.
 * Rockbox APK invokes this script directly — must stay rebootless with no .kl swap on return.
 * When changing: assets/y1/switch-to-stock.sh + ROM /system/etc/solar/ copy; Y1RomPrep seeds.
 * Reversal: revert to stock rockbox-y1 script that reboots and swaps keylayouts.
 */
public final class LauncherSwitch {

    private static final String SWITCH_SCRIPT = "sh /data/data/switch-to-stock.sh";
    private static final String SWITCH_TO_JJ = SWITCH_SCRIPT + " --jj";


    /** Switch back to Solar from Rockbox — preferred HOME + start MainActivity. */
    public static boolean switchToSolar(Context context) {
        boolean ok = RootShell.run(SWITCH_SCRIPT);
        DebugSessionLog.logAlways("LauncherSwitch.switchToSolar", ok ? "ok" : "failed", "H-D",
                debugState(context));
        return ok;
    }


    /** Switch to JJ Launcher — enable JJ, disable Rockbox, start JJ. */
    public static boolean switchToJj(Context context) {
        boolean ok = RootShell.run(SWITCH_TO_JJ);
        DebugSessionLog.logAlways("LauncherSwitch.switchToJj", ok ? "ok" : "failed", "H-D",
                debugState(context));
        return ok;
    }

    /**
     * 2026-07-08 — Switch to Stock Innioasis HOME — MODE_JJ inject while foreground.
     * Reversal: delete; use TARGET_CUSTOM + applyCustomHome only.
     */
    public static boolean switchToStock(Context context) {
        boolean ok = RootShell.run(SWITCH_TO_INNIOASIS);
        DebugSessionLog.logAlways("LauncherSwitch.switchToStock", ok ? "ok" : "failed", "H-D",
                debugState(context));
        return ok;
    }

    // #region agent log
    private static org.json.JSONObject debugState(Context context) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("canRunSu", RootShell.canRun());
            o.put("switchScript", isSwitchScriptAvailable());
            o.put("xbinSu", new File("/system/xbin/su").exists());
            o.put("binSu", new File("/system/bin/su").exists());
        } catch (Exception ignored) {}
        return o;
    }
    // #endregion
}
