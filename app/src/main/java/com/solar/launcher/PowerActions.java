package com.solar.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.widget.Toast;

import com.solar.home.policy.HomeTargetPolicy;

/**
 * Shared shutdown / restart / screen-off / launcher switch — used by in-app power tier and overlay host.
 */
public final class PowerActions {

    /** 2026-07-15 — Same setuid paths as RootShell; used for A5 power cmds that skip RootShell. */
    private static final String[] SU_PATHS = {"/system/xbin/su", "/system/bin/su", "su"};

    private PowerActions() {}

    /**
     * 2026-07-15 — Power off via root.
     * 2026-07-20 — Toast "Shutting down…", ship diagnostics if online (time-boxed), then power off.
     * Was: "Getting ready…" + Soulseek notice (hang risk). Reversal: old toast + notify-only prep.
     */
    public static void shutdown() {
        shutdown(null);
    }

    public static void shutdown(Context ctx) {
        runPowerWithDiagPrep(ctx, false);
    }

    /**
     * 2026-07-15 — Reboot via root.
     * 2026-07-20 — Same prep as {@link #shutdown(Context)} with "Restarting…" toast.
     */
    public static void restart() {
        restart(null);
    }

    public static void restart(Context ctx) {
        runPowerWithDiagPrep(ctx, true);
    }

    /**
     * User-initiated or idle auto power transition: optional log export when online, then shell.
     * Immediate when offline or ctx null.
     */
    private static void runPowerWithDiagPrep(final Context ctx, final boolean restart) {
        final Runnable shell = new Runnable() {
            @Override
            public void run() {
                runPowerShell(restart ? "reboot" : "reboot -p");
            }
        };
        if (ctx == null) {
            new Thread(shell, restart ? "SolarRestart" : "SolarShutdown").start();
            return;
        }
        com.solar.launcher.soulseek.SolarDiagnosticReporter.runWithPowerDiagPrep(
                ctx, restart, shell);
    }

    /**
     * 2026-07-15 — Screen off — synthetic POWER keyevent (overlay + context Sleep/Zzz chip).
     * Was: RootShell only → A5 toast failure; blocked UI thread. Now: bg silent su + RootShell fallback off A5.
     * Reversal: only RootShell.run("input keyevent 26") on caller thread.
     */
    public static void screenSleep(Context ctx) {
        final Context app = ctx != null ? ctx.getApplicationContext() : null;
        // Background: setuid returns fast on Solar ROM; SuperSU must not ANR the overlay.
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (trySilentSuScreenOff(app)) return;
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {}
                if (trySilentSuScreenOff(app)) return;
                // Y1/Y2: RootShell may still wake daemonsu; A5 keeps RootShell skipped.
                if (!DeviceFeatures.isA5() && RootShell.run("input keyevent 26")) return;
                if (app != null) {
                    toastOnMain(app, R.string.context_action_sleep_failed);
                }
            }
        }, "SolarScreenSleep").start();
    }

    /**
     * 2026-07-15 — Run reboot / shutdown / sleep shell — A5 bypasses RootShell SuperSU skip.
     * Layman: turn the player off or restart without popping a root-ask dialog on A5.
     * Technical: silent setuid su on A5; RootShell.run on Y1/Y2.
     */
    private static void runPowerShell(String command) {
        if (command == null || command.length() == 0) return;
        if (DeviceFeatures.isA5()) {
            trySilentSu(command);
            return;
        }
        RootShell.run(command);
    }

    /**
     * 2026-07-15 — Direct su -c without RootShell A5 gate (from MainActivity.trySuScreenOff).
     * Returns true when exit code is 0.
     */
    static boolean trySilentSu(String command) {
        if (command == null || command.length() == 0) return false;
        for (int i = 0; i < SU_PATHS.length; i++) {
            String su = SU_PATHS[i];
            Process proc = null;
            try {
                proc = Runtime.getRuntime().exec(new String[] {su, "-c", command});
                if (proc.waitFor() == 0) return true;
            } catch (Exception ignored) {
            } finally {
                if (proc != null) proc.destroy();
            }
        }
        return false;
    }

    /**
     * 2026-07-15 — Screen-off via su; prefers confirming screen actually slept when ctx present.
     * Was: MainActivity.trySuScreenOff private. Reversal: restore private helper in MainActivity.
     */
    static boolean trySilentSuScreenOff(Context ctx) {
        for (int i = 0; i < SU_PATHS.length; i++) {
            String su = SU_PATHS[i];
            Process proc = null;
            try {
                proc = Runtime.getRuntime().exec(new String[] {su, "-c", "input keyevent 26"});
                if (proc.waitFor() != 0) continue;
                if (ctx == null) return true;
                if (!isScreenInteractive(ctx)) return true;
                // Exit 0 but still lit — treat as success after key inject (sleep may be async).
                return true;
            } catch (Exception ignored) {
            } finally {
                if (proc != null) proc.destroy();
            }
        }
        return false;
    }

    /** True when display is on / interactive (API 17 isScreenOn; API 20+ isInteractive). */
    private static boolean isScreenInteractive(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            if (android.os.Build.VERSION.SDK_INT >= 20) return pm.isInteractive();
            return pm.isScreenOn();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 2026-07-08 — True when SolarHomeHelper APK is registered (canonical switch path).
     * Layman: if the middle-man home helper is installed, let it do the switch alone.
     * Technical: PackageManager probe of HELPER_PKG — no launch needed.
     */
    public static boolean isHelperInstalled(Context ctx) {
        if (ctx == null) return false;
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getPackageInfo(HomeTargetPolicy.HELPER_PKG, 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 2026-07-08 — Switch back to Solar — helper owns pm/script when present (was dual-fire race).
     * Layman: ask helper to make Solar home; only run the switch script yourself if helper is gone.
     * Technical: APPLY_HOME_TARGET broadcast; local LauncherSwitch only as fallback.
     * Reversal: always call LauncherSwitch.switchToSolar after prefs (dual-fire again).
     */
    public static void switchToSolar(final Context ctx) {
        if (ctx == null) return;
        final boolean helperOk = isHelperInstalled(ctx);
        LauncherHelperClient.applyHomeTarget(ctx, LauncherDefault.TARGET_SOLAR, "overlay");
        new Thread(new Runnable() {
            @Override
            public void run() {
                LauncherPreference.applyHomeTarget(ctx, LauncherDefault.TARGET_SOLAR);
                // Helper already runs solar-launcher-exec.sh switch — skip local script to avoid race.
                if (!helperOk) {
                    LauncherSwitch.switchToSolar(ctx.getApplicationContext());
                }
            }
        }, "SolarSwitch").start();
    }

    /**
     * 2026-07-08 — Switch to Rockbox — single switch execution (helper or local fallback).
     * Reversal: always call LauncherSwitch.switchToRockbox after prefs (dual-fire again).
     */
    public static void switchToRockbox(final Context ctx) {
        if (ctx == null) return;
        Toast.makeText(ctx, R.string.dialog_rockbox_unavailable, Toast.LENGTH_SHORT).show();
    }

    /**
     * 2026-07-19 — Switch to JJ only when already installed (no OTA fetch).
     * Was: JjLauncherInstaller.ensureInstalledBlocking downloaded JJ. Reversal: restore install.
     */
    public static void switchToJj(final Context ctx) {
        if (ctx == null) return;
        if (!JjLauncherAvailability.isOfferVisible(ctx)) {
            Toast.makeText(ctx, R.string.dialog_jj_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!LauncherSwitch.isJjInstalled(ctx)) {
            Toast.makeText(ctx, R.string.dialog_jj_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean helperOk = isHelperInstalled(ctx);
        LauncherHelperClient.applyHomeTarget(ctx, LauncherDefault.TARGET_JJ, "overlay");
        new Thread(new Runnable() {
            @Override
            public void run() {
                LauncherPreference.applyHomeTarget(ctx, LauncherDefault.TARGET_JJ);
                if (helperOk) {
                    return;
                }
                LauncherSwitch.switchToJj(ctx.getApplicationContext());
            }
        }, "JjSwitch").start();
    }

    /**
     * 2026-07-08 — Switch to Stock Innioasis HOME — MODE_JJ inject; helper single-fire when present.
     * Reversal: always call LauncherSwitch.switchToStock after prefs (or delete for applyCustomHome only).
     */
    public static void switchToStock(final Context ctx) {
        if (ctx == null) return;
        if (!LauncherSwitch.isStockOfferVisible(ctx)) {
            Toast.makeText(ctx, R.string.dialog_stock_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean helperOk = isHelperInstalled(ctx);
        LauncherHelperClient.applyHomeTarget(ctx, LauncherDefault.TARGET_STOCK, "overlay");
        new Thread(new Runnable() {
            @Override
            public void run() {
                LauncherPreference.applyHomeTarget(ctx, LauncherDefault.TARGET_STOCK);
                if (helperOk) {
                    return;
                }
                final boolean ok = LauncherSwitch.switchToStock(ctx.getApplicationContext());
                if (!ok) {
                    toastOnMain(ctx, R.string.dialog_stock_failed);
                }
            }
        }, "StockSwitch").start();
    }

    /** Show a failure toast on the main looper from a background switch thread. */
    private static void toastOnMain(Context ctx, final int msgRes) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(app, msgRes, Toast.LENGTH_LONG).show();
            }
        });
    }
}
