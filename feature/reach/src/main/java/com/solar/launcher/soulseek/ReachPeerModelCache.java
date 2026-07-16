package com.solar.launcher.soulseek;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/** Cache remote device model (Y1/Y2) for Reach user badges — never use local model. */
public final class ReachPeerModelCache {
    private static final String PREFS_KEY = "reach_peer_models_v1";

    private ReachPeerModelCache() {}

    public static void put(SharedPreferences prefs, String username, String model) {
        if (prefs == null || username == null || username.trim().isEmpty()) return;
        String m = normalizeModel(model);
        if (m.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(prefs.getString(PREFS_KEY, "{}"));
            root.put(username.trim().toLowerCase(Locale.US), m);
            prefs.edit().putString(PREFS_KEY, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static String get(SharedPreferences prefs, String username) {
        if (prefs == null || username == null) return "";
        try {
            JSONObject root = new JSONObject(prefs.getString(PREFS_KEY, "{}"));
            return root.optString(username.trim().toLowerCase(Locale.US), "");
        } catch (Exception e) {
            return "";
        }
    }

    public static String fromClientDescription(String desc) {
        if (desc == null) return "";
        String d = desc.toLowerCase(Locale.US);
        if (d.contains("y2")) return "Y2";
        if (d.contains("y1")) return "Y1";
        return "";
    }

    static String normalizeModel(String model) {
        if (model == null) return "";
        String m = model.trim().toUpperCase(Locale.US);
        if (m.contains("Y2")) return "Y2";
        if (m.contains("Y1")) return "Y1";
        if ("2".equals(m)) return "Y2";
        if ("1".equals(m)) return "Y1";
        return "";
    }
}
