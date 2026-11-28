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
 * 2026-07-20 — Debug session 708768: empty Artists/Albums under Search… after cold open.
 * Layman: notes when Library shelves go blank so we can see if the memory index lost its stamp.
 * Technical: NDJSON → logcat + filesDir + SD + HTTP ingest (session 708768).
 * Pull: adb logcat -d -s SolarDbg708768:I ; or .cursor/debug-708768.log via ingest.
 * Reversal: ENABLED=false; delete class + #region agent log call sites after verify.
 */
public final class Debug708768Log {

    private static final String TAG = "SolarDbg708768";
    private static final String SESSION = "708768";
    private static final String FILE = "debug-708768.log";
    private static final String INGEST =
            "http://127.0.0.1:7652/ingest/a52e4428-848e-4c3a-b047-de416047f443";
    private static final String HOST_PATH =
            "/home/deck/Documents/Cursor Workspaces/TheSolarProject/solar/.cursor/debug-708768.log";

    /** 2026-07-20 — On while hunting empty catalog; flip false after fix verified. */
    public static final boolean ENABLED = false;

    private static volatile String runId = "pre-fix";

    private Debug708768Log() {}

    /** 2026-07-20 — Tag this reproduce pass (pre-fix / post-fix). */
    public static void setRunId(String id) {
        if (id != null && id.length() > 0) runId = id;
    }

    /**
     * 2026-07-20 — One NDJSON breadcrumb for empty-catalog hypotheses.
     * Layman: write a timed note about library index vs what the menu shows.
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
        }, "dbg708768").start();
    }
}
