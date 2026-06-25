package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/** Debug-mode NDJSON logger — session 5f8a90; pull via adb from /storage/sdcard0/. */
public final class DebugAgentLog {
    private static final String TAG = "SolarNetDbg";
    private static final String FILE = "debug-5f8a90.log";
    private static final String SESSION = "5f8a90";
    private static final String WORKSPACE_LOG =
            "/home/deck/Documents/Cursor Workspaces/TheSolarProject/solar/.cursor/debug-5f8a90.log";

    private DebugAgentLog() {}

    public static void log(Context ctx, String location, String message, String hypothesisId, JSONObject data) {
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
            try {
                File sdcard = new File("/storage/sdcard0", FILE);
                FileWriter w = new FileWriter(sdcard, true);
                w.write(line);
                w.write('\n');
                w.close();
            } catch (Exception ignored2) {}
            if (ctx != null) {
                File f = new File(ctx.getFilesDir(), FILE);
                FileWriter w = new FileWriter(f, true);
                w.write(line);
                w.write('\n');
                w.close();
            }
            try {
                FileWriter w = new FileWriter(WORKSPACE_LOG, true);
                w.write(line);
                w.write('\n');
                w.close();
            } catch (Exception ignored3) {}
        } catch (Exception ignored) {}
    }
}
