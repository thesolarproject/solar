package com.solar.launcher.xposed.bridge;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/**
 * Debug session 050a40 — stock UsbStorageActivity vs Xposed replace on Y1.
 * Pull: adb shell cat /storage/sdcard0/.solar/debug-050a40.log
 * 2026-07-19
 */
final class Bridge050a40DebugLog {

    private static final String SESSION = "050a40";
    private static final String FILE = "debug-050a40.log";
    /** 2026-07-19 — on for stock-USB diagnosis; flip false after verified fix. */
    private static final boolean ENABLED = false;

    private Bridge050a40DebugLog() {}

    /** Append one NDJSON line from SystemUI Xposed USB hooks. */
    static void log(String location, String message, String hypothesisId, JSONObject data) {
        if (!ENABLED) return;
        // #region agent log
        try {
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("runId", "xposed");
            o.put("timestamp", System.currentTimeMillis());
            o.put("location", location != null ? location : "");
            o.put("message", message != null ? message : "");
            o.put("hypothesisId", hypothesisId != null ? hypothesisId : "");
            if (data != null) o.put("data", data);
            String line = o.toString();
            SolarContextBridge.log("050a40 " + line);
            append(new File("/storage/sdcard0/.solar", FILE), line);
            append(new File("/data/local/tmp", FILE), line);
        } catch (Throwable ignored) {}
        // #endregion
    }

    private static void append(File f, String line) {
        FileWriter w = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            w = new FileWriter(f, true);
            w.write(line);
            w.write('\n');
        } catch (Throwable ignored) {
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {}
            }
        }
    }
}
