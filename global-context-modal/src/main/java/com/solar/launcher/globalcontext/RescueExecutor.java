package com.solar.launcher.globalcontext;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.solar.input.policy.GlobalInputPolicy;

/**
 * 2026-07-05 — 10s hold rescue execution (companion-owned).
 * Layman: force-stops stuck apps, relaunches Solar, may disable Rockbox if it was foreground.
 * Technical: minimal shell exec — ROM solar-rescue-exec.sh remains canonical on rooted devices.
 * Reversal: delete; root script + SolarRescue.java own rescue again.
 */
public final class RescueExecutor {

    private static volatile long lastExecAt;

    private RescueExecutor() {}

    /** Run once per hold — debounced 2s so duplicate ticks do not double-fire. */
    public static void execute(Context ctx, String foregroundPkg) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastExecAt < 2000L) return;
        lastExecAt = now;

        CompanionRescueHoldState.signalRestarting();
        String solar = GlobalInputPolicy.SOLAR_PKG;
        if (runShell("sh /system/etc/solar/solar-rescue-exec.sh")) {
            return;
        }
        if (runShell("sh /system/xbin/solar-rescue-exec.sh")) {
            return;
        }

        // APK fallback — best-effort without root.
        if (ctx != null) {
            runShell("am force-stop " + solar);
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(solar);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                try {
                    ctx.startActivity(launch);
                } catch (Exception ignored) {}
            }
        }
        CompanionRescueHoldState.disarm();
    }

    private static boolean runShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
