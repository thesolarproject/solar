package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 2026-07-20 — Debug session 2b0d3b: Solar completely non-responsive on Y1.
 * Layman: timed notes when keys are ignored so we can see which gate is stuck.
 * Technical: NDJSON → logcat + filesDir + SD + HTTP ingest (session 2b0d3b).
 * Pull: adb logcat -d -s SolarDbg2b0d3b:I ; or .cursor/debug-2b0d3b.log via ingest.
 * Reversal: ENABLED=false; delete class + #region agent log call sites after verify.
 */
public final class Debug2b0d3bLog {

    private static final String TAG = "SolarDbg2b0d3b";
    private static final String SESSION = "2b0d3b";
    private static final String FILE = "debug-2b0d3b.log";
    private static final String INGEST =
            "http://127.0.0.1:7652/ingest/a52e4428-848e-4c3a-b047-de416047f443";
    private static final String HOST_PATH =
            "/home/deck/Documents/Cursor Workspaces/TheSolarProject/solar/.cursor/debug-2b0d3b.log";

    /** 2026-07-20 — On while hunting total key deadness; flip false after fix verified. */
    public static final boolean ENABLED = false;

    private static volatile String runId = "pre-fix";
    /** Rate-limit key-swallow spam while dial spins. */
    private static long lastSwallowLogMs;

    private Debug2b0d3bLog() {}

    /** 2026-07-20 — Tag this reproduce pass (pre-fix / post-fix). */
    public static void setRunId(String id) {
        if (id != null && id.length() > 0) runId = id;
    }

    /**
     * 2026-07-20 — One NDJSON breadcrumb for unresponsive-UI hypotheses.
     * Layman: write a timed note about why the dial may be ignored.
     */
    public static void log(Context ctx, String location, String message,
            String hypothesisId, JSONObject data) {
        if (!ENABLED) return;
        try {
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("timestamp", System.currentTimeMillis());
            o.put("location", location != null ? location : "");
            o.put("message", message != null ? message : "");
            o.put("hypothesisId", hypothesisId != null ? hypothesisId : "");
            o.put("runId", runId);
            o.put("data", data != null ? data : new JSONObject());
            String line = o.toString();
            Log.i(TAG, line);
            append(new File(HOST_PATH), line);
            if (ctx != null) {
                try {
                    append(new File(ctx.getFilesDir(), FILE), line);
                } catch (Exception ignored) {}
                try {
                    File sd = new File(DeviceFeatures.getPrimaryStorageRoot(), ".solar");
                    if (!sd.isDirectory()) sd.mkdirs();
                    append(new File(sd, FILE), line);
                } catch (Exception ignored) {}
            }
            postAsync(line);
        } catch (Exception ignored) {}
    }

    /**
     * 2026-07-20 — Log key swallows at most ~2/sec so wheel spin does not flood.
     * Layman: note ignored dial clicks without filling the log with thousands of lines.
     */
    public static void logSwallowThrottled(Context ctx, String location, String message,
            String hypothesisId, JSONObject data) {
        long now = System.currentTimeMillis();
        if (now - lastSwallowLogMs < 500L) return;
        lastSwallowLogMs = now;
        log(ctx, location, message, hypothesisId, data);
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

    private static void postAsync(final String line) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    URL url = new URL(INGEST);
                    c = (HttpURLConnection) url.openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(400);
                    c.setReadTimeout(400);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("X-Debug-Session-Id", SESSION);
                    byte[] body = line.getBytes("UTF-8");
                    c.setFixedLengthStreamingMode(body.length);
                    OutputStream os = new BufferedOutputStream(c.getOutputStream());
                    os.write(body);
                    os.flush();
                    os.close();
                    c.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }, "dbg2b0d3b").start();
    }
}
