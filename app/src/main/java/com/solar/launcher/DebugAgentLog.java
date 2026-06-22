package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/** Debug-mode NDJSON logger — session 384d9d; pull via adb from /storage/sdcard0/. */
final class DebugAgentLog {
    private static final String TAG = "SolarNetDbg";
    private static final String FILE = "debug-384d9d.log";
    private static final String SESSION = "384d9d";

    private DebugAgentLog() {}

    static void log(Context ctx, String location, String message, String hypothesisId, JSONObject data) {
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
        } catch (Exception ignored) {}
    }
}
