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
 * 2026-07-20 — Debug session a3e8ff: podcast show select shows other shows not episodes.
 * Layman: breadcrumbs when opening a show vs when more show rows sneak in.
 * Tech: NDJSON → logcat + SD .solar + HTTP ingest (adb reverse tcp:7652).
 * Pull: adb pull …/.solar/debug-a3e8ff.log .cursor/
 * Reversal: delete this class and #region agent log call sites after fix confirmed.
 */
public final class DebugA3e8ffLog {
    private static final String TAG = "SolarDbgA3e8ff";
    private static final String SESSION = "a3e8ff";
    private static final String FILE = "debug-a3e8ff.log";
    private static final String INGEST =
            "http://127.0.0.1:7652/ingest/a52e4428-848e-4c3a-b047-de416047f443";
    private static final String HOST_PATH =
            "/home/deck/Documents/Cursor Workspaces/TheSolarProject/solar/.cursor/debug-a3e8ff.log";

    /** 2026-07-20 — On while diagnosing podcast show→episode navigation. */
    public static final boolean ENABLED = false;

    private DebugA3e8ffLog() {}

    /**
     * 2026-07-20 — One NDJSON breadcrumb for podcast browse hypotheses.
     * Layman: writes a timed note so we can see show-vs-episode mixups.
     * Technical: never throws; logcat + SD + host path + ingest.
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
            o.put("runId", "pre-fix");
            o.put("data", data != null ? data : new JSONObject());
            String line = o.toString();
            Log.i(TAG, line);
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
                    URL u = new URL(INGEST);
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(800);
                    c.setReadTimeout(800);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("X-Debug-Session-Id", SESSION);
                    byte[] body = line.getBytes("UTF-8");
                    c.setFixedLengthStreamingMode(body.length);
                    OutputStream os = c.getOutputStream();
                    os.write(body);
                    os.close();
                    c.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }, "dbg-a3e8ff").start();
    }
}
