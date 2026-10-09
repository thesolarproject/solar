package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 2026-07-20 — Debug session e0de2e: Back does not close context menus (user stuck).
 * Layman: breadcrumbs when Back fails to dismiss Options / quick menu.
 * Tech: NDJSON → logcat + SD .solar + host .cursor + HTTP ingest (adb reverse tcp:7652).
 * Pull: adb pull /storage/sdcard0/.solar/debug-e0de2e.log .cursor/
 * Reversal: delete this class and #region agent log call sites after fix confirmed.
 */
public final class DebugE0de2eLog {
    private static final String TAG = "SolarDbgE0de2e";
    private static final String SESSION = "e0de2e";
    private static final String FILE = "debug-e0de2e.log";
    private static final String INGEST =
            "http://127.0.0.1:7652/ingest/a52e4428-848e-4c3a-b047-de416047f443";
    private static final String HOST_PATH =
            "/home/deck/Documents/Cursor Workspaces/TheSolarProject/solar/.cursor/debug-e0de2e.log";

    /** Flip false after Back-dismiss fix is verified. */
    public static final boolean ENABLED = false;

    private DebugE0de2eLog() {}

    /**
     * 2026-07-20 — One NDJSON breadcrumb for Back/context-menu dismiss hypotheses.
     * Layman: writes what Back did so we can see why the popup stayed open.
     * Technical: never throws; logcat + SD + host + ingest.
     */
    public static void log(Context ctx, String location, String message, String hypothesisId,
            JSONObject data) {
        if (!ENABLED) return;
        try {
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("timestamp", System.currentTimeMillis());
            o.put("location", location != null ? location : "");
            o.put("message", message != null ? message : "");
            o.put("hypothesisId", hypothesisId != null ? hypothesisId : "");
            o.put("runId", "ctx-back-stuck");
            o.put("data", data != null ? data : new JSONObject());
            String line = o.toString();
            Log.e(TAG, line);
            append(new File(HOST_PATH), line);
            append(new File("/storage/sdcard0/.solar", FILE), line);
            append(new File("/storage/sdcard1/.solar", FILE), line);
            if (ctx != null) {
                try {
                    append(new File(ctx.getFilesDir(), FILE), line);
                } catch (Exception ignored) {}
            }
            postIngest(line);
        } catch (Exception ignored) {}
    }

    /** 2026-07-20 — No Context (overlay / static) — same sinks minus filesDir. */
    public static void log(String location, String message, String hypothesisId, JSONObject data) {
        log(null, location, message, hypothesisId, data);
    }

    private static void append(File f, String line) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileWriter w = new FileWriter(f, true);
            w.write(line);
            w.write('\n');
            w.close();
        } catch (Exception ignored) {}
    }

    private static void postIngest(final String line) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(INGEST).openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(400);
                    c.setReadTimeout(400);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("X-Debug-Session-Id", SESSION);
                    OutputStream os = c.getOutputStream();
                    os.write(line.getBytes("UTF-8"));
                    os.close();
                    c.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }, "dbgE0de2e").start();
    }
}
