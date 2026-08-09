package com.solar.launcher.globalcontext;

import android.content.Context;
import android.content.Intent;

/**
 * 2026-07-05 — Emergency Rockbox / crash-loop HOME routing.
 * Layman: when Solar cannot start, HOME opens recovery screen instead of crashing again.
 * Technical: reads persist.solar.emergency_mode; Solar explicitly forwards to {@link EmergencyRecoveryActivity}.
 * Reversal: delete; Solar EmergencyRecoveryActivity in the main APK remains the fallback.
 */
public final class EmergencyRockboxMode {

    private EmergencyRockboxMode() {}

    public static boolean isEmergencyMode() {
        return SysPropHelper.isTrue(SolarCrashWatchdog.PROP_EMERGENCY_MODE);
    }

    /** Boot hook — no-op unless emergency flag already set from prior session. */
    public static void onBoot(Context ctx) {
        if (!isEmergencyMode() || ctx == null) return;
        // Solar launches EmergencyRecoveryActivity explicitly; it is not registered as a HOME app.
    }

    /** Launch package-picker tier from recovery activity. */
    public static void openPackageLauncher(Context ctx) {
        if (ctx == null) return;
        Intent pick = new Intent(Intent.ACTION_MAIN);
        pick.addCategory(Intent.CATEGORY_HOME);
        pick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(pick);
        } catch (Exception ignored) {}
    }

    /** User cleared streak — exit emergency and retry Solar. */
    public static void clearEmergencyAndRetrySolar(Context ctx) {
        SysPropHelper.set(SolarCrashWatchdog.PROP_EMERGENCY_MODE, "0");
        SysPropHelper.set(SolarCrashWatchdog.PROP_CRASH_STREAK, "0");
        if (ctx == null) return;
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(
                com.solar.input.policy.GlobalInputPolicy.SOLAR_PKG);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                ctx.startActivity(launch);
            } catch (Exception ignored) {}
        }
    }
}
