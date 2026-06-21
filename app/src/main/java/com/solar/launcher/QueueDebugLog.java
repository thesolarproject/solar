package com.solar.launcher;

import android.util.Log;

import org.json.JSONObject;

import java.io.FileOutputStream;

/** Debug-mode NDJSON — pull via adb from /storage/sdcard0/debug-d88428.log */
final class QueueDebugLog {
    private static final String TAG = "SolarQueueDbg";
    private static final String SESSION = "d88428";
    private static final String LOG_PATH = "/storage/sdcard0/debug-d88428.log";

    private QueueDebugLog() {}

    static void log(String location, String message, String hypothesisId, JSONObject data) {
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
            FileOutputStream out = new FileOutputStream(LOG_PATH, true);
            out.write(line.getBytes("UTF-8"));
            out.write('\n');
            out.close();
        } catch (Exception ignored) {}
    }
}
