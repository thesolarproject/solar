package com.solar.launcher.globalcontext;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 2026-07-14 — Session 083511 overlay-input debug (host pull + logcat).
 * Layman: crumbs so we see if Back/wheel reach the global menu handler.
 * Technical: Log.i + append /sdcard/solar/debug-083511.log NDJSON.
 * Reversal: delete class + call sites after input fix verified.
 */
public final class DebugSession083511 {

    private static final String TAG = "Debug083511";
    private static final Object LOCK = new Object();
    /** Keep on for adb pull during this overlay input debug session. */
    public static final boolean ENABLED = false;

    private DebugSession083511() {}

    /** Write one NDJSON line for hypothesis tracking. */
    public static void log(String hypothesisId, String location, String message, String dataJson) {
        if (!ENABLED) return;
        String line = "{\"sessionId\":\"083511\",\"runId\":\"pre-fix\",\"hypothesisId\":\""
                + esc(hypothesisId) + "\",\"location\":\"" + esc(location)
                + "\",\"message\":\"" + esc(message) + "\",\"data\":"
                + (dataJson != null ? dataJson : "{}")
                + ",\"timestamp\":" + System.currentTimeMillis() + "}";
        Log.i(TAG, hypothesisId + " " + location + " " + message + " " + dataJson);
        synchronized (LOCK) {
            FileOutputStream fos = null;
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "solar");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, "debug-083511.log");
                fos = new FileOutputStream(f, true);
                fos.write(line.getBytes("UTF-8"));
                fos.write('\n');
                fos.flush();
            } catch (Throwable ignored) {
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
