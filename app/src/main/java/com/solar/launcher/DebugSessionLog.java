package com.solar.launcher;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/**
 * Debug-mode NDJSON logger — session a76d20.
 * ponytail: writes to SD card; pull with adb to workspace .cursor/debug-a76d20.log
 */
public final class DebugSessionLog {
    private static final String TAG = "SolarDbga76d20";
    private static final String SESSION = "a76d20";
    private static final String FILE = "debug-a76d20.log";
    /** Enabled for this debug session — sync append; keep calls sparse. */
    public static volatile boolean ENABLED = true;

    private DebugSessionLog() {}

    public static void log(String location, String message, String hypothesisId, JSONObject data) {
        if (!ENABLED) return;
        try {
            JSONObject o = new JSONObject();
            o.put("sessionId", SESSION);
            o.put("timestamp", System.currentTimeMillis());
            o.put("location", location);
            o.put("message", message);
            o.put("hypothesisId", hypothesisId);
            if (data != null) o.put("data", data);
            String line = o.toString();
            Log.i(TAG, line);
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
