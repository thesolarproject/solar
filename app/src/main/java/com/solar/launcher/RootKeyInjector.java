package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * App-side client for the session-scoped {@link KeyInjectorMain} root daemon.
 *
 * <p>Boots one {@code su} shell that {@code exec}s app_process into KeyInjectorMain, then streams
 * keycodes over its stdin. The VM is paid for once (~0.5-1s hidden behind the stock-app launch
 * transition) so subsequent injects are ~ms — Rockbox-Y1-level responsiveness on Y2 dev-signed
 * builds where INJECT_EVENTS is denied. Single static instance; prewarmed at app boot and kept
 * alive for the process lifetime (not torn down per handoff — that caused first-app wheel lag).
 */
public final class RootKeyInjector {
    // Debug session a4dee8 marker — grep SolarDbga4dee8 for daemon lifecycle + per-key evidence.
    private static final String TAG = "SolarDbga4dee8";

    private static final Object LOCK = new Object();
    private static Process process;          // the su shell hosting app_process
    private static OutputStream stdin;        // pipe into KeyInjectorMain's System.in
    private static volatile boolean running;  // true once claimed, cleared on stop/death
    private static volatile boolean ready;    // true after KeyInjectorMain prints READY

    private RootKeyInjector() {}

    /** Fast per-key gate — true once the daemon has been started (not yet dead). */
    public static boolean isRunning() {
        return running;
    }

    /** True once KeyInjectorMain confirmed boot — inject() is safe to stream keycodes. */
    public static boolean isReady() {
        return ready;
    }

    /**
     * Boot the daemon on a background thread (VM startup must never touch the UI thread).
     * Idempotent: a second call while one is live/booting is a no-op.
     */
    public static void start(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (running || process != null) return; // single instance — don't double-boot
            running = true;
            ready = false;
        }
        // getPackageCodePath() is the installed APK — same CLASSPATH trick UmsEnabler uses.
        final String apkPath = context.getPackageCodePath();
        new Thread(new Runnable() {
            @Override
            public void run() {
                bootDaemon(apkPath);
            }
        }, "RootKeyInjectorBoot").start();
    }

    private static void bootDaemon(String apkPath) {
        Log.i(TAG, "RootKeyInjector START requested apk=" + apkPath);
        // Ensure SuperSU daemonsu is up before we spawn the long-lived root VM (canRun bootstraps it).
        boolean canRun = RootShell.canRun();
        // #region agent log
        debugLog("RootKeyInjector.bootDaemon", "boot begin", "H-C",
                null, apkPath, -1, "canRun=" + canRun);
        // #endregion

        // Interactive su (stdin stays wired for streaming) is preferred; su -c is the fallback.
        for (String suPath : SuPaths.available()) {
            if (tryInteractiveBoot(suPath, apkPath)) return;
        }
        for (String suPath : SuPaths.available()) {
            if (trySuCBoot(suPath, apkPath)) return;
        }

        synchronized (LOCK) {
            running = false;
            ready = false;
            process = null;
            stdin = null;
        }
        // #region agent log
        debugLog("RootKeyInjector.bootDaemon", "all su paths failed", "H-A",
                null, apkPath, -1, null);
        // #endregion
        Log.w(TAG, "RootKeyInjector boot failed — no su path worked");
    }

    /**
     * Interactive su → export CLASSPATH → exec app_process. Stdin stays wired to KeyInjectorMain
     * for streaming keycodes. Preferred over su -c on SuperSU Y2 builds.
     */
    private static boolean tryInteractiveBoot(String suPath, String apkPath) {
        Process p = null;
        Thread errDrain = null;
        try {
            p = Runtime.getRuntime().exec(new String[] {suPath});
            errDrain = startErrDrain(p, suPath, "interactive");
            OutputStream os = p.getOutputStream();
            // Feed a tiny shell script over su stdin — after exec, os feeds KeyInjectorMain.
            String script = "export CLASSPATH=" + apkPath + "\n"
                    + "exec app_process /system/bin com.solar.launcher.KeyInjectorMain\n";
            os.write(script.getBytes("UTF-8"));
            os.flush();
            synchronized (LOCK) {
                process = p;
                stdin = os;
            }
            if (drainUntilExit(p, suPath, "interactive")) return true;
        } catch (Exception e) {
            Log.w(TAG, "RootKeyInjector interactive boot failed su=" + suPath, e);
        } finally {
            if (errDrain != null) errDrain.interrupt();
            clearIfOwned(p);
        }
        return false;
    }

    /** Fallback: su -c with exec app_process — stdin may not always wire on all su builds. */
    private static boolean trySuCBoot(String suPath, String apkPath) {
        Process p = null;
        Thread errDrain = null;
        try {
            String cmd = "export CLASSPATH=" + apkPath
                    + "; exec app_process /system/bin com.solar.launcher.KeyInjectorMain";
            p = Runtime.getRuntime().exec(new String[] {suPath, "-c", cmd});
            errDrain = startErrDrain(p, suPath, "su-c");
            synchronized (LOCK) {
                process = p;
                stdin = p.getOutputStream();
            }
            if (drainUntilExit(p, suPath, "su-c")) return true;
        } catch (Exception e) {
            Log.w(TAG, "RootKeyInjector su -c boot failed su=" + suPath, e);
        } finally {
            if (errDrain != null) errDrain.interrupt();
            clearIfOwned(p);
        }
        return false;
    }

    /** Read stdout until EOF; return true if we saw READY (daemon ran successfully). */
    private static boolean drainUntilExit(Process p, String suPath, String mode) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), "UTF-8"));
        String line;
        boolean sawReady = false;
        while ((line = reader.readLine()) != null) {
            if ("READY".equals(line)) {
                sawReady = true;
                ready = true; // inject() may now stream keycodes
                Log.i(TAG, "RootKeyInjector READY su=" + suPath + " mode=" + mode);
            } else {
                Log.i(TAG, "RootKeyInjector daemon: " + line);
            }
        }
        int exit = p.waitFor();
        if (sawReady) {
            // Normal path: we only reach here after stop() closes stdin / destroy kills the VM.
            Log.i(TAG, "RootKeyInjector daemon exited exit=" + exit);
            return true;
        }
        return false;
    }

    /** Background stderr drain — an unread stderr pipe can wedge su before READY. */
    private static Thread startErrDrain(final Process p, final String suPath, final String mode) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                drainStreamTagged(p.getErrorStream());
            }
        }, "RootKeyInjectorErr");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void drainStreamTagged(InputStream in) {
        if (in == null) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                Log.i(TAG, "RootKeyInjector stderr: " + line);
            }
        } catch (Exception ignored) {}
    }

    /** Clear daemon state only if we still own this process handle (avoids racing a newer boot). */
    private static void clearIfOwned(Process p) {
        synchronized (LOCK) {
            if (process == p) {
                running = false;
                ready = false;
                process = null;
                stdin = null;
            }
        }
    }

    /**
     * Stream one keycode to the daemon. Safe from any thread; returns false (so the caller falls
     * back to the one-shot su path) if the daemon is not up/ready or the pipe is dead.
     */
    public static boolean inject(int keyCode) {
        OutputStream os;
        synchronized (LOCK) {
            os = stdin;
            if (os == null || !running || !ready) return false;
        }
        try {
            // One decimal keycode per line — matches KeyInjectorMain's line protocol.
            os.write((Integer.toString(keyCode) + "\n").getBytes("UTF-8"));
            os.flush();
            Log.i(TAG, "RootKeyInjector inject ok key=" + keyCode);
            return true;
        } catch (Exception e) {
            // Broken pipe: mark dead so callers stop trying until re-armed.
            synchronized (LOCK) {
                running = false;
                ready = false;
            }
            Log.w(TAG, "RootKeyInjector inject FAILED key=" + keyCode + " (" + e.getMessage() + ")");
            return false;
        }
    }

    /** Tear the daemon down: closing stdin gives KeyInjectorMain EOF; destroy backs it up. Idempotent. */
    public static void stop() {
        Process p;
        OutputStream os;
        synchronized (LOCK) {
            p = process;
            os = stdin;
            process = null;
            stdin = null;
            running = false;
            ready = false;
        }
        if (os == null && p == null) return; // already stopped
        if (os != null) {
            try {
                os.close(); // EOF → KeyInjectorMain exits its read loop cleanly
            } catch (Exception ignored) {}
        }
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {}
        }
        Log.i(TAG, "RootKeyInjector STOP");
    }

    // #region agent log
    private static void debugLog(String location, String message, String hypothesisId,
            String suPath, String detail, int exitCode, String extra) {
        try {
            JSONObject d = new JSONObject();
            if (suPath != null) d.put("suPath", suPath);
            if (detail != null) d.put("detail", detail);
            if (exitCode >= 0) d.put("exitCode", exitCode);
            if (extra != null) d.put("extra", extra);
            DebugSessionLog.logAlways(location, message, hypothesisId, d);
        } catch (Exception ignored) {}
    }
    // #endregion
}
