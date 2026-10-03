package com.solar.launcher;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 2026-07-20 — Debug session 0b8f80: Scan Complete “0 songs” vs YouTube Hello download.
 * Layman: breadcrumbs proving whether the toast lied, the scan found nothing, or Hello never landed.
 * Technical: logcat + filesDir NDJSON + HTTP ingest (adb reverse tcp:7652).
 * Reversal: ENABLED=false; delete class + #region agent log call sites after verified.
 */
public final class Debug0b8f80Log {

    private static final String TAG = "SolarDbg0b8f80";
    private static final String SESSION = "0b8f80";
    private static final String FILE = "debug-0b8f80.log";
    private static final String INGEST =
            "http://127.0.0.1:7652/ingest/a52e4428-848e-4c3a-b047-de416047f443";

    /** 2026-07-20 — On for Hello/scan hunt; flip false after confirmed. */
    public static final boolean ENABLED = false;

    private static volatile String runId = "pre-fix";

    private Debug0b8f80Log() {}

    /** Tag verification runs after a fix. */
    public static void setRunId(String id) {
        if (id != null && id.length() > 0) runId = id;
    }

    /**
     * 2026-07-20 — One NDJSON breadcrumb for this session.
     * Layman: write a short note about scan/download so we can see what really happened.
     */
    public static void log(String location, String message, String hypothesisId,
            String dataJsonObject) {
        if (!ENABLED) return;
        try {
            StringBuilder sb = new StringBuilder(320);
            sb.append("{\"sessionId\":\"").append(SESSION).append('"');
            sb.append(",\"runId\":\"").append(runId).append('"');
            sb.append(",\"timestamp\":").append(System.currentTimeMillis());
            sb.append(",\"location\":\"").append(esc(location)).append('"');
            sb.append(",\"message\":\"").append(esc(message)).append('"');
            sb.append(",\"hypothesisId\":\"").append(esc(hypothesisId)).append('"');
            if (dataJsonObject != null && dataJsonObject.length() > 0) {
                sb.append(",\"data\":").append(dataJsonObject);
            }
            sb.append('}');
            String line = sb.toString();
            Log.i(TAG, line);
            try {
                File dir = new File("/storage/sdcard0/.solar");
                if (!dir.exists()) dir.mkdirs();
                append(new File(dir, FILE), line);
            } catch (Exception ignored) {}
            try {
                File app = new File("/data/data/com.solar.launcher/files");
                if (app.isDirectory()) append(new File(app, FILE), line);
            } catch (Exception ignored) {}
            postIngestAsync(line);
        } catch (Exception ignored) {}
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void append(File f, String line) throws Exception {
        FileWriter w = new FileWriter(f, true);
        w.write(line);
        w.write('\n');
        w.close();
    }

    private static void postIngestAsync(final String line) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(INGEST).openConnection();
                    c.setConnectTimeout(400);
                    c.setReadTimeout(400);
                    c.setRequestMethod("POST");
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
        }, "Dbg0b8f80").start();
    }
}
