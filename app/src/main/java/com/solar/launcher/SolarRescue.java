package com.solar.launcher;

import android.content.Context;

import java.io.File;
import android.content.Intent;

/**
 * 2026-07-05 — Ultra-long BACK/power rescue: kill user apps, disable alternates, reset HOME, reboot OS.
 * Layman: hold Back or Power ~10s to escape any stuck app — Linux resets Solar home and reboots.
 * Technical: root solar-rescue-exec.sh; allowlist for system + Xposed deps; pm disable org.rockbox until GUI switch-back.
 */
public final class SolarRescue {

    private static final String PROP_HOME_TARGET = "persist.solar.home.target";
    private static final String SOLAR_PKG = "com.solar.launcher";

    private SolarRescue() {}

    /** True when package must not be force-stopped during rescue. */
    public static boolean isRescueAllowlisted(String pkg) {
        if (pkg == null || pkg.length() == 0) return true;
        if (SOLAR_PKG.equals(pkg)) return true;
        if ("android".equals(pkg)) return true;
        if (pkg.startsWith("com.android.systemui")) return true;
        if (pkg.startsWith("com.android.phone")) return true;
        if (pkg.startsWith("com.android.bluetooth")) return true;
        if (pkg.startsWith("com.solar.launcher.xposed.")) return true;
        if (ExternalInputHandoff.FM_RADIO_PACKAGE.equals(pkg)) return true;
        if (pkg.startsWith("com.innioasis.")) return true;
        if (GlobalOverlayPolicy.isSystemShellPackage(pkg)) return true;
        return false;
    }

    /** Build root rescue shell — dismiss layers, stop fg, disable Rockbox, restart Solar HOME. */
    public static String buildRescueCommand(String foregroundPkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("setprop ").append(SolarImeRouteArbiter.ACTIVE_PROPERTY).append(" 0; ");
        sb.append("setprop ").append(OverlayKeyGate.ACTIVE_PROPERTY).append(" 0; ");
        sb.append("setprop ").append(ExternalInputHandoff.HANDOFF_ACTIVE_PROPERTY).append(" 0; ");
        if (foregroundPkg != null && foregroundPkg.length() > 0
                && !isRescueAllowlisted(foregroundPkg)
                && !LauncherDefault.JJ_PACKAGE.equals(foregroundPkg)) {
            sb.append("am force-stop ").append(shellQuote(foregroundPkg)).append("; ");
        }
        sb.append("am force-stop ").append(LauncherDefault.JJ_PACKAGE).append("; ");
        sb.append("pm disable ").append(LauncherDefault.JJ_PACKAGE).append("; ");
        sb.append("setprop ").append(PROP_HOME_TARGET).append(" solar; ");
        sb.append("am broadcast -a ").append(LauncherDefault.ACTION_SET_PREFERRED_HOME)
                .append(" -n com.solar.launcher/.LauncherHomeReceiver --es target solar; ");
        sb.append("setprop ").append(SolarRescueHoldState.FULLSCREEN_PROPERTY).append(" 1; ");
        sb.append("setprop ").append(SolarRescueHoldState.HUD_SECOND_PROPERTY).append(" ")
                .append(SolarRescueHoldState.HUD_RESTARTING).append("; ");
        sb.append("sync; reboot");
        return sb.toString();
    }

    /** Dismiss overlay, show "Restarting" flash, then run rescue script on a worker thread. */
    public static void execute(final Context ctx, final String foregroundPkg) {
        executeAfterHudFlash(ctx, foregroundPkg, null);
    }

    /**
     * 2026-07-05 — Fire rescue after brief HUD "Restarting" flash (hold need not release).
     * Layman: show the final label, then relaunch Solar.
     */
    public static void executeAfterHudFlash(final Context ctx, final String foregroundPkg,
            android.os.Handler handler) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        SolarRescueHoldState.signalRestarting();
        SolarRescueHoldHost.ping(ctx);
        Runnable fire = new Runnable() {
            @Override
            public void run() {
                SolarRescueHoldState.disarm();
                prepDismissLayers(app);
                final String fg = foregroundPkg != null ? foregroundPkg : "";
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (new File("/system/etc/solar/solar-rescue-exec.sh").isFile()) {
                            String q = fg.length() > 0 ? (" '" + fg.replace("'", "'\\''") + "'") : "";
                            RootShell.run("sh /system/etc/solar/solar-rescue-exec.sh" + q);
                        } else {
                            RootShell.run(buildRescueCommand(fg));
                        }
                    }
                }, "SolarRescue").start();
            }
        };
        if (handler != null) {
            handler.postDelayed(fire, SolarRescueHoldState.FIRE_FLASH_MS);
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    fire, SolarRescueHoldState.FIRE_FLASH_MS);
        }
    }

    /** @deprecated internal — prep layers before root script. */
    static void executeImmediate(final Context ctx, final String foregroundPkg) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        prepDismissLayers(app);
        final String fg = foregroundPkg != null ? foregroundPkg : "";
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (new File("/system/etc/solar/solar-rescue-exec.sh").isFile()) {
                    String q = fg.length() > 0 ? (" '" + fg.replace("'", "'\\''") + "'") : "";
                    RootShell.run("sh /system/etc/solar/solar-rescue-exec.sh" + q);
                } else {
                    RootShell.run(buildRescueCommand(fg));
                }
            }
        }, "SolarRescue").start();
    }

    /** Best-effort layer teardown before root script — safe from any process. */
    static void prepDismissLayers(Context app) {
        OverlayKeyGate.disarm();
        SolarImeRouteArbiter.disarm();
        ExternalInputHandoff.restoreAfterOverlayDismiss(app);
        try {
            Intent dismiss = new Intent(app, SolarOverlayService.class);
            dismiss.setAction(OverlayTriggers.ACTION_DISMISS_OVERLAY);
            app.startService(dismiss);
        } catch (Exception ignored) {}
        try {
            Intent hideIme = new Intent(app, SolarInputMethodService.class);
            hideIme.setAction(OverlayTriggers.ACTION_IME_DISMISS);
            app.startService(hideIme);
        } catch (Exception ignored) {}
    }

    private static String shellQuote(String pkg) {
        return "'" + pkg.replace("'", "'\\''") + "'";
    }

    /** Test hook — rescue must always disable Rockbox and JJ when installed. */
    static boolean willDisableAlternateLaunchers(String fg) {
        String cmd = buildRescueCommand(fg);
        return cmd.contains("pm disable " + LauncherDefault.JJ_PACKAGE);
    }

    /** @deprecated use {@link #willDisableAlternateLaunchers(String)} */
    static boolean willDisableRockbox(String fg) {
        return willDisableAlternateLaunchers(fg);
    }
}
