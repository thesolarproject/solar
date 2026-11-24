package com.solar.launcher.xposed.bridge;

import android.content.Context;

/**
 * 2026-07-05 — Ultra-long BACK/power rescue from system_server (kill apps + restart Solar).
 */
public final class SolarRescueClient {

    private static final String SOLAR_PKG = "com.solar.launcher";
    private static final String JJ_PKG = "com.themoon.y1";
    private static final String PROP_HOME = "persist.solar.home.target";
    private static final String SET_HOME_ACTION = "com.solar.launcher.action.SET_PREFERRED_HOME";
    private static final String HOME_RECEIVER = "com.solar.launcher/.LauncherHomeReceiver";
    private static final String IME_ACTIVE = "sys.solar.ime.active";
    private static final String OVERLAY_ACTIVE = "sys.solar.overlay.active";
    private static final String HANDOFF_ACTIVE = "sys.solar.handoff.active";

    private SolarRescueClient() {}

    /** 6s hold — dismiss layers then root rescue script (works when Solar process is dead). */
    public static void execute(final Context ctx, final String foregroundPkg) {
        if (ctx == null) return;
        try {
            android.content.Intent dismiss = new android.content.Intent(
                    SolarRestartClient.ACTION_DISMISS_OVERLAY);
            dismiss.setComponent(new android.content.ComponentName(SOLAR_PKG,
                    SOLAR_PKG + ".SolarOverlayService"));
            ctx.startService(dismiss);
        } catch (Throwable ignored) {}
        final String fg = foregroundPkg != null ? foregroundPkg : "";
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String q = fg.length() > 0 ? (" '" + quote(fg) + "'") : "";
                    Runtime.getRuntime().exec(new String[]{"sh", "-c",
                            "sh /system/etc/solar/solar-rescue-exec.sh" + q});
                    SolarContextBridge.log("SolarRescue exec fg=" + fg);
                } catch (Throwable t) {
                    SolarContextBridge.log("SolarRescue failed: " + t.getClass().getSimpleName());
                }
            }
        }, "SolarRescue").start();
    }

    static String buildRescueCommand(String foregroundPkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("setprop ").append(IME_ACTIVE).append(" 0; ");
        sb.append("setprop ").append(OVERLAY_ACTIVE).append(" 0; ");
        sb.append("setprop ").append(HANDOFF_ACTIVE).append(" 0; ");
        if (foregroundPkg != null && foregroundPkg.length() > 0
                && !isAllowlisted(foregroundPkg)
                && !JJ_PKG.equals(foregroundPkg)) {
            sb.append("am force-stop ").append(quote(foregroundPkg)).append("; ");
        }
        sb.append("am force-stop ").append(JJ_PKG).append("; ");
        sb.append("pm disable ").append(JJ_PKG).append("; ");
        sb.append("setprop ").append(PROP_HOME).append(" solar; ");
        sb.append("am broadcast -a ").append(SET_HOME_ACTION)
                .append(" -n ").append(HOME_RECEIVER).append(" --es target solar; ");
        sb.append("am force-stop ").append(SOLAR_PKG).append("; ");
        sb.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME ");
        sb.append("-n ").append(SOLAR_PKG).append("/.MainActivity -f 0x34000000");
        return sb.toString();
    }

    private static boolean isAllowlisted(String pkg) {
        if (SOLAR_PKG.equals(pkg)) return true;
        if ("android".equals(pkg)) return true;
        if (pkg.startsWith("com.android.systemui")) return true;
        if (pkg.startsWith("com.android.phone")) return true;
        if (pkg.startsWith("com.android.bluetooth")) return true;
        if (pkg.startsWith("com.solar.launcher.xposed.")) return true;
        if ("com.mediatek.FMRadio".equals(pkg)) return true;
        if (pkg.startsWith("com.innioasis.")) return true;
        if (pkg.startsWith("com.android.keyguard")) return true;
        if (pkg.startsWith("com.android.inputmethod")) return true;
        return false;
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
