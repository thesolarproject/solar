package com.solar.launcher;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Run shell commands as root via Y1/Y2 ROM setuid su (see install-y1-su-system.sh).
 * Tries explicit paths first — plain "su" alone can fail from app Runtime.exec on some builds.
 */
public final class RootShell {
    /** One-shot: setuid daemonsu can be started from app context when daemon was not booted yet. */
    private static volatile boolean daemonsuBootstrapped = false;

    /** 2026-07-16 — Cache su probe; canRun() used to spawn {@code su -c id} every volume tick. */
    private static volatile Boolean canRunCached;

    private RootShell() {}

    /**
     * Probe whether any su path grants root from this app process.
     * 2026-07-16 — Result is cached after first probe (volume hot path must not re-spawn su).
     */
    public static boolean canRun() {
        Boolean cached = canRunCached;
        if (cached != null) return cached.booleanValue();
        boolean ok = run("id");
        canRunCached = Boolean.valueOf(ok);
        return ok;
    }

    /** 2026-07-16 — Forget cached su result after ROM / root changes (rare). */
    public static void invalidateCanRunCache() {
        canRunCached = null;
    }

    /** Run one root shell command; returns true when exit code is 0. */
    public static boolean run(String command) {
        return run(command, false);
    }

    /**
     * 2026-07-15 — Root shell; optional A5 allow for FM airplane / platform prep.
     * Layman: normal Solar still avoids SuperSU popups on A5; FM flight-mode must use su.
     * Tech: allowA5=true skips the A5 early return (Solar ROM setuid su).
     * Was: A5 always no-op. Reversal: run(command) only with A5 block.
     */
    public static boolean run(String command, boolean allowA5) {
        if (command == null || command.isEmpty()) return false;
        // 2026-07-14 — A5 stock su prompts SuperSU over Solar and can stall; skip by default.
        if (DeviceFeatures.isA5() && !allowA5) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("cmdPrefix", command.length() > 48 ? command.substring(0, 48) : command);
                d.put("a5", true);
                Debug1fc727Log.log(null, "RootShell.run", "A5 skip — no su", "H1", d);
            } catch (Exception ignored) {}
            // #endregion
            return false;
        }
        if (trySuPaths(command)) return true;
        // Y2 ROMs before 99SuperSUDaemon may ship su without a running daemonsu — bootstrap once.
        if (bootstrapDaemonsu() && trySuPaths(command)) return true;
        return false;
    }

    private static android.os.Handler asyncHandler;
    private static volatile String pendingAsyncCmd;

    private static volatile boolean pendingAsyncAllowA5;

    /** Coalesced background su — one thread, skip duplicate pending commands (2026-07-05). */
    public static void runAsync(String command) {
        runAsync(command, false);
    }

    /**
     * 2026-07-15 — Async root with optional A5 allow (FM chip free / airplane).
     * Layman: background “free the radio” without blocking the UI.
     */
    public static void runAsyncAllowA5(String command) {
        runAsync(command, true);
    }

    public static void runAsync(String command, boolean allowA5) {
        if (command == null || command.isEmpty()) return;
        pendingAsyncCmd = command;
        pendingAsyncAllowA5 = allowA5;
        ensureAsyncHandler();
        asyncHandler.removeCallbacks(asyncFlushRunnable);
        asyncHandler.post(asyncFlushRunnable);
    }

    private static final Runnable asyncFlushRunnable = new Runnable() {
        @Override
        public void run() {
            String cmd = pendingAsyncCmd;
            boolean a5 = pendingAsyncAllowA5;
            pendingAsyncCmd = null;
            pendingAsyncAllowA5 = false;
            if (cmd != null) RootShell.run(cmd, a5);
        }
    };

    private static synchronized void ensureAsyncHandler() {
        if (asyncHandler != null) return;
        android.os.HandlerThread ht = new android.os.HandlerThread("RootShellAsync");
        ht.start();
        asyncHandler = new android.os.Handler(ht.getLooper());
    }

    private static boolean trySuPaths(String command) {
        for (String su : SuPaths.available()) {
            Process proc = null;
            try {
                proc = Runtime.getRuntime().exec(new String[] {su, "-c", command});
                String err = drainStream(proc.getErrorStream());
                int code = proc.waitFor();
                // #region agent log
                debugLog("RootShell.run", "su attempt", "H-A-H-B",
                        su, command, code, err.isEmpty() ? null : err);
                // #endregion
                if (code == 0) return true;
            } catch (Exception e) {
                // #region agent log
                debugLog("RootShell.run", "su exception", "H-A",
                        su, command, -1, e.getMessage());
                // #endregion
            } finally {
                if (proc != null) proc.destroy();
            }
        }
        return false;
    }

    private static String drainStream(java.io.InputStream in) {
        if (in == null) return "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (out.length() > 0) out.append(' ');
                out.append(line);
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Start SuperSU daemon (setuid binary — no prior root needed). */
    private static boolean bootstrapDaemonsu() {
        if (daemonsuBootstrapped) return false;
        daemonsuBootstrapped = true;
        File daemon = new File("/system/xbin/daemonsu");
        if (!daemon.canExecute()) return false;
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[] {daemon.getAbsolutePath(), "--auto-daemon"});
            proc.waitFor();
            Thread.sleep(1500L);
            // #region agent log
            debugLog("RootShell.bootstrapDaemonsu", "daemonsu started", "H-C",
                    daemon.getAbsolutePath(), "--auto-daemon", 0, null);
            // #endregion
            return true;
        } catch (Exception e) {
            // #region agent log
            debugLog("RootShell.bootstrapDaemonsu", "daemonsu failed", "H-C",
                    daemon.getAbsolutePath(), "--auto-daemon", -1, e.getMessage());
            // #endregion
            return false;
        } finally {
            if (proc != null) proc.destroy();
        }
    }

    /** Run root command and capture combined stdout/stderr (for diagnostics). */
    public static String runCapture(String command) {
        return runCapture(command, false);
    }

    /**
     * 2026-07-15 — Capture stdout with optional A5 allow (FM claim diagnostics).
     * Also drains stderr into the result when stdout is empty (su noise).
     */
    public static String runCapture(String command, boolean allowA5) {
        if (command == null || command.isEmpty()) return "";
        if (DeviceFeatures.isA5() && !allowA5) return "";
        for (String su : SuPaths.available()) {
            Process proc = null;
            try {
                proc = Runtime.getRuntime().exec(new String[] {su, "-c", command});
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()));
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
                String err = drainStream(proc.getErrorStream());
                int code = proc.waitFor();
                // #region agent log
                debugLog("RootShell.runCapture", "su capture", "H-C",
                        su, command, code, out.length() > 120 ? out.substring(0, 120) : out.toString());
                // #endregion
                if (code == 0) {
                    return out.toString();
                }
                // Non-zero but we may still have useful CLAIM_DONE lines.
                if (out.length() > 0) return out.toString();
                if (err.length() > 0) return err;
            } catch (Exception e) {
                // #region agent log
                debugLog("RootShell.runCapture", "su capture exception", "H-A",
                        su, command, -1, e.getMessage());
                // #endregion
            } finally {
                if (proc != null) proc.destroy();
            }
        }
        return "";
    }

    // #region agent log
    private static void debugLog(String location, String message, String hypothesisId,
            String suPath, String command, int exitCode, String extra) {
        if (!DebugSessionLog.ENABLED) return;
        try {
            JSONObject d = new JSONObject();
            d.put("suPath", suPath);
            d.put("cmd", command.length() > 200 ? command.substring(0, 200) + "…" : command);
            d.put("exitCode", exitCode);
            if (extra != null) d.put("extra", extra);
            DebugSessionLog.logAlways(location, message, hypothesisId, d);
        } catch (Exception ignored) {}
    }
    // #endregion
}
