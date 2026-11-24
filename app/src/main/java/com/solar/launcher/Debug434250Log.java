package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/** 2026-07-05 — Debug session 434250: rescue HUD + quick-menu stuck states. */
public final class Debug434250Log {

    private static final String TAG = "SolarDbg434250";
    private static final String SESSION = "434250";
    private static final String FILE = "debug-434250.log";
    /** 2026-07-05 — Off in release; rescue poll loop checks before snapshot I/O. */
    public static volatile boolean ENABLED = false;

    private Debug434250Log() {}

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
            append(new File("/data/data/com.solar.launcher/files", FILE), line);
            File sd = DeviceFeatures.getPrimaryStorageRoot();
            if (sd != null) {
                File dir = new File(sd, ".solar");
                if (!dir.exists()) dir.mkdirs();
                append(new File(dir, FILE), line);
            }
        } catch (Exception ignored) {}
    }


    public static JSONObject rescueSnapshot() {
        JSONObject d = new JSONObject();
        try {
            d.put("holdDeadline", SolarRescueHoldState.readDeadlineUptime());
            d.put("hudSecond", readProp("sys.solar.rescue.hud_second"));
            d.put("hudText", SolarRescueHoldState.hudText(null) != null ? "set" : "null");
            d.put("overlayActive", readProp(OverlayKeyGate.ACTIVE_PROPERTY));
            d.put("overlayUi", readProp(OverlayKeyGate.UI_PROPERTY));
            d.put("overlayOpening", readProp(OverlayKeyGate.OPENING_PROPERTY));
        } catch (Exception ignored) {}
        return d;
    }

    private static String readProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, "?");
            return v != null ? v.toString() : "?";
        } catch (Exception e) {
            return "err";
        }
    }

    private static void append(File f, String line) {
        try {
            FileWriter w = new FileWriter(f, true);
            w.write(line);
            w.write('\n');
            w.close();
        } catch (Exception ignored) {}
    }
}
