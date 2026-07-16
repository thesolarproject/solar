package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/** Debug-mode NDJSON logger — session 5c5611; pull via adb from SD .solar/ or app files. */
public final class DebugAgentLog {
    private static final String TAG = "SolarDbg5c5611";
    private static final String FILE = "debug-5c5611.log";
    private static final String SESSION = "5c5611";
    /** ponytail: hot-path sync file I/O was freezing UI — flip true only for short debug sessions. */
    public static volatile boolean ENABLED = false;

    private DebugAgentLog() {}

    public static void log(Context ctx, String location, String message, String hypothesisId, JSONObject data) {
        if (!ENABLED) return;
        try {
            long ts = System.currentTimeMillis();
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("timestamp", ts);
            o.put("location", location);
            o.put("message", message);
            o.put("hypothesisId", hypothesisId);
            if (data != null) o.put("data", data);
            String line = o.toString();
            Log.i(TAG, line);
            // Prefer primary music volume; fall back to common Y1 mounts.
            File sdRoot = new File("/storage/sdcard0");
            if (!sdRoot.isDirectory()) sdRoot = new File("/storage/sdcard1");
            if (sdRoot.isDirectory()) {
                try {
                    File dir = new File(sdRoot, ".solar");
                    if (!dir.exists()) dir.mkdirs();
                    FileWriter w = new FileWriter(new File(dir, FILE), true);
                    w.write(line);
                    w.write('\n');
                    w.close();
                } catch (Exception ignored2) {}
            }
            if (ctx != null) {
                try {
                    File f = new File(ctx.getFilesDir(), FILE);
                    FileWriter w = new FileWriter(f, true);
                    w.write(line);
                    w.write('\n');
                    w.close();
                } catch (Exception ignored3) {}
            }
        } catch (Exception ignored) {}
    }
}
