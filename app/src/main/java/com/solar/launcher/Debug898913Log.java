package com.solar.launcher;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Debug session 898913 — NP→Flow handoff; logcat tag SolarDbg898913 + pullable files. */
public final class Debug898913Log {
    private static final String TAG = "SolarDbg898913";
    private static final String SESSION = "898913";
    private static final String FILE = "debug-898913.log";
  /** Async file append — sync SD I/O on the UI thread caused wheel/scroll jank. */
    private static final ExecutorService FILE_EXEC = Executors.newSingleThreadExecutor();

    public static volatile boolean ENABLED = false;

    private Debug898913Log() {}

    /** Always emits — uncaught crashes and critical path (debug session 898913). */
    public static void logAlways(String location, String message, String hypothesisId,
            JSONObject data) {
        emit(location, message, hypothesisId, data, true);
    }

    public static void log(String location, String message, String hypothesisId, JSONObject data) {
        if (!ENABLED) return;
        emit(location, message, hypothesisId, data, false);
    }

    private static void emit(String location, String message, String hypothesisId, JSONObject data,
            boolean always) {
        try {
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("timestamp", System.currentTimeMillis());
            o.put("location", location);
            o.put("message", message);
            o.put("hypothesisId", hypothesisId);
            if (data != null) o.put("data", data);
            final String line = o.toString();
            Log.i(TAG, line);
            if (!always && !ENABLED) return;
            FILE_EXEC.execute(new Runnable() {
                @Override
                public void run() {
                    appendLine(line);
                }
            });
        } catch (Exception ignored) {}
    }

    private static void appendLine(String line) {
        try {
            File dir = new File("/storage/sdcard0/solar");
            if (!dir.exists()) dir.mkdirs();
            try {
                FileWriter w = new FileWriter(new File(dir, FILE), true);
                w.write(line);
                w.write('\n');
                w.close();
            } catch (Exception ignored2) {}
            try {
                File appDir = new File("/data/data/com.solar.launcher/files");
                if (!appDir.exists()) appDir.mkdirs();
                FileWriter w = new FileWriter(new File(appDir, FILE), true);
                w.write(line);
                w.write('\n');
                w.close();
            } catch (Exception ignored3) {}
        } catch (Exception ignored) {}
    }
}
