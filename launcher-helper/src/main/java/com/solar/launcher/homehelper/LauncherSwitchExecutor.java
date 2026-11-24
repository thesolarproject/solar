package com.solar.launcher.homehelper;

import android.content.Context;
import android.util.Log;

import com.solar.home.policy.HomeTargetPolicy;

/**
 * 2026-07-06 — Runs solar-launcher-exec.sh via su for switch/restart/disable.
 * Layman: helper asks root shell to pm-disable competitors and start the right home app.
 * Technical: delegates to /system/etc/solar/solar-launcher-exec.sh or /data/data copy.
 * Reversal: broadcast SET_PREFERRED_HOME to Solar only; no root script.
 */
public final class LauncherSwitchExecutor {

    private static final String TAG = "LauncherSwitchExec";
    private static final String[] SCRIPT_PATHS = {
            "/system/etc/solar/solar-launcher-exec.sh",
            "/data/data/com.solar.launcher/solar-launcher-exec.sh",
            "/data/data/solar-launcher-exec.sh"
    };

    private LauncherSwitchExecutor() {}

    /** Full HOME switch — disable competitors, setprop, start target. */
    public static boolean switchToTarget(String target) {
        String mode = normalizeSwitchArg(target);
        return runScript("switch " + mode);
    }

    /** Force-stop active target and cold-start — never pm-disable active package. */
    public static boolean restartActive() {
        return runScript("restart-active");
    }

    /** Re-apply pm disable for non-active competitors only. */
    public static boolean disableCompetitors() {
        return runScript("disable-competitors");
    }

    /** Watchdog poll — relaunch saved target when alternate launchers stole foreground. */
    public static boolean enforceForeground() {
        return runScript("enforce-foreground");
    }

    /**
     * 2026-07-08 — Map HOME target tokens for solar-launcher-exec.sh (stock was missing → solar).
     * Layman: "Stock" must stay Stock when the helper asks the switch script — not silently Solar.
     * Technical: TARGET_STOCK → "stock"; unknown → solar fail-open.
     * Reversal: drop TARGET_STOCK branch (stock fell through to solar).
     */
    static String normalizeSwitchArg(String target) {
        if (HomeTargetPolicy.TARGET_JJ.equals(target)) return "jj";
        // 2026-07-08 — Factory Innioasis HOME; without this, helper ran "switch solar" and undid Stock.
        if (HomeTargetPolicy.TARGET_STOCK.equals(target)) return "stock";
        if (HomeTargetPolicy.TARGET_CUSTOM.equals(target)) return "custom";
        return "solar";
    }

    private static boolean runScript(String args) {
        String script = resolveScriptPath();
        if (script == null) {
            Log.w(TAG, "solar-launcher-exec.sh missing for: " + args);
            return false;
        }
        String cmd = "sh " + script + " " + args;
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });
            int code = p.waitFor();
            Log.i(TAG, cmd + " exit=" + code);
            return code == 0;
        } catch (Exception e) {
            Log.w(TAG, cmd + " failed: " + e.getMessage());
            try {
                Process p = Runtime.getRuntime().exec(new String[] { "sh", "-c", cmd });
                int code = p.waitFor();
                return code == 0;
            } catch (Exception e2) {
                Log.w(TAG, "non-root fallback failed: " + e2.getMessage());
                return false;
            }
        }
    }

    private static String resolveScriptPath() {
        for (int i = 0; i < SCRIPT_PATHS.length; i++) {
            if (new java.io.File(SCRIPT_PATHS[i]).canRead()) {
                return SCRIPT_PATHS[i];
            }
        }
        return null;
    }

    /** Start platform supervisor if ROM-baked script exists. */
    public static void ensurePlatformDaemon(Context context) {
        if (context == null) return;
        String daemon = "/system/etc/solar/solar-platform-daemon.sh";
        if (!new java.io.File(daemon).canRead()) return;
        try {
            Runtime.getRuntime().exec(new String[] { "su", "-c", "sh " + daemon + " &" });
        } catch (Exception ignored) {}
    }
}
