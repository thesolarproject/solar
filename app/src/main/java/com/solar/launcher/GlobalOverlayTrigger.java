package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * 2026-07-05 — Root evdev fallback tier for global overlay when Xposed PWM misses BACK-long.
 * Read-only: checks SolarImeRouteArbiter and OverlayKeyGate before acting; arms only on miss.
 * When changing: GlobalOverlayPolicy package rules; Y2 adds POWER scancode 116 watch.
 * Reversal: stop bootstrap ensureStarted; overlay only opens from Xposed tier.
 */
public final class GlobalOverlayTrigger {

    private static final String TAG = "GlobalOverlayTrigger";
    private static volatile boolean started;

    private GlobalOverlayTrigger() {}

    /** Start once on boot — root daemon forwards overlay keys when Xposed PWM hooks miss events. */
    public static void ensureStarted(Context context) {
        if (context == null) return;
        if (started) return;
        synchronized (GlobalOverlayTrigger.class) {
            if (started) return;
            // 2026-07-06 — 99SolarInit solar-rescue-daemon.sh already owns evdev tier; skip duplicate spawn.
            if (isRescueDaemonRunning()) {
                Log.i(TAG, "GlobalOverlayTriggerMain already running — skip Java boot spawn");
                started = true;
                return;
            }
            started = true;
        }
        final String apkPath = context.getPackageCodePath();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                bootDaemon(app, apkPath);
            }
        }, "GlobalOverlayTriggerBoot").start();
    }

    private static void bootDaemon(Context context, String apkPath) {
        if (!RootShell.canRun()) {
            Log.w(TAG, "su unavailable — BACK-long overlay daemon disabled");
            started = false;
            return;
        }
        if (isRescueDaemonRunning()) {
            Log.i(TAG, "rescue daemon present — not spawning second GlobalOverlayTriggerMain");
            return;
        }
        String companionApk = resolveCompanionApk(context);
        String sysApk = "/system/app/com.solar.launcher.apk";
        final String solarCp = new File(sysApk).isFile() ? sysApk : apkPath;
        final String classpath = (companionApk != null && companionApk.length() > 0)
                ? companionApk + ":" + solarCp : solarCp;
        final String mainClass = (companionApk != null && companionApk.length() > 0)
                ? "com.solar.launcher.globalcontext.CompanionRootInputDaemon"
                : "com.solar.launcher.GlobalOverlayTriggerMain";
        for (String suPath : SuPaths.available()) {
            if (tryBoot(suPath, classpath, mainClass)) return;
        }
        Log.w(TAG, "daemon boot failed");
        started = false;
    }

    private static String resolveCompanionApk(Context context) {
        String sys = "/system/app/SolarGlobalContextModal.apk";
        if (new File(sys).isFile()) return sys;
        if (context == null) return null;
        try {
            return context.getPackageManager()
                    .getApplicationInfo("com.solar.launcher.globalcontext", 0).sourceDir;
        } catch (Exception e) {
            return null;
        }
    }

    /** Long-lived su shell → app_process companion/Solar evdev daemon. */
    private static boolean tryBoot(String suPath, String classpath, String mainClass) {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{suPath});
            OutputStream stdin = proc.getOutputStream();
            String cmd = "export CLASSPATH='" + classpath.replace("'", "'\\''") + "'\n"
                    + "exec app_process /system/bin " + mainClass + "\n";
            stdin.write(cmd.getBytes("UTF-8"));
            stdin.flush();
            InputStream out = proc.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(out, "UTF-8"));
            String line;
            long deadline = System.currentTimeMillis() + 8000L;
            while ((line = reader.readLine()) != null) {
                if (line.contains("READY")) {
                    Log.i(TAG, "BACK-long overlay daemon ready");
                    drainQuietly(proc);
                    return true;
                }
                if (System.currentTimeMillis() > deadline) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "boot via " + suPath + ": " + e.getMessage());
        } finally {
            if (proc != null) proc.destroy();
        }
        return false;
    }

    private static void drainQuietly(final Process proc) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), "UTF-8"));
                    while (r.readLine() != null) {}
                } catch (Exception ignored) {}
            }
        }, "GlobalOverlayTriggerDrain").start();
    }

    /** cmdline scan — matches solar-rescue-daemon.sh daemon_running() (2026-07-06). */
    private static boolean isRescueDaemonRunning() {
        if (!RootShell.canRun()) return false;
        String out = RootShell.runCapture(
                "for f in /proc/*/cmdline; do tr '\\0' ' ' < \"$f\" 2>/dev/null "
                        + "| grep -q GlobalOverlayTriggerMain && echo yes && exit 0; done");
        return out != null && out.contains("yes");
    }
}
