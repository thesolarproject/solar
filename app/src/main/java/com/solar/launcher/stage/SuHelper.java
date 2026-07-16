package com.solar.launcher.stage;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 2026-07-16 — timed su -c with graceful false when no root. */
public final class SuHelper {
    private static final String TAG = "SolarStageSu";
    private static final int DEFAULT_TIMEOUT_SEC = 25;
    private static final AtomicInteger cachedRoot = new AtomicInteger(-1); // -1 unknown, 0 no, 1 yes

    private SuHelper() {}

    public static boolean hasRoot() {
        int c = cachedRoot.get();
        if (c >= 0) return c == 1;
        boolean ok = run("true", 8);
        cachedRoot.set(ok ? 1 : 0);
        return ok;
    }

    public static void clearRootCache() {
        cachedRoot.set(-1);
    }

    public static boolean run(String command) {
        return run(command, DEFAULT_TIMEOUT_SEC);
    }

    public static boolean run(String command, int timeoutSec) {
        if (command == null || command.isEmpty()) return false;
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[] { "su", "-c", command });
            final Process p = proc;
            Thread gobbler = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(p.getErrorStream()));
                        while (r.readLine() != null) { /* drain */ }
                    } catch (Exception ignored) {}
                }
            }, "SuErrDrain");
            gobbler.setDaemon(true);
            gobbler.start();
            boolean finished;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            } else {
                finished = waitForLegacy(proc, timeoutSec * 1000L);
            }
            if (!finished) {
                proc.destroy();
                Log.w(TAG, "su timeout: " + command);
                return false;
            }
            return proc.exitValue() == 0;
        } catch (Exception e) {
            Log.w(TAG, "su failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean waitForLegacy(Process proc, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                proc.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                Thread.sleep(50);
            }
        }
        return false;
    }

    public static String shellQuote(String path) {
        if (path == null) return "''";
        return "'" + path.replace("'", "'\\''") + "'";
    }
}
