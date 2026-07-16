package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Local Soulseek likes/dislikes (synced to server on login). */
public final class SoulseekInterests {
    private static final String PREF_LIKES = "soulseek_interest_likes";
    private static final String PREF_DISLIKES = "soulseek_interest_dislikes";
    private static final int MAX_ITEMS = 100;

    private SoulseekInterests() {}

    public static String normalizeItem(String item) {
        if (item == null) return "";
        return item.trim().toLowerCase(Locale.US);
    }

    public static List<String> systemLikes(Context context) {
        List<String> out = new ArrayList<String>();
        out.add("innioasis");
        out.add("reach client");
        String model = detectModelInterest();
        if (!model.isEmpty()) out.add(model);
        if (context != null) {
            try {
                String version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
                if (version != null && !version.isEmpty()) {
                    out.add("reach " + version.trim().toLowerCase(Locale.US));
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** y1 / y2 interest from hardware heuristics (MT6582 ≈ Y2). */
    static String detectModelInterest() {
        try {
            String board = android.os.Build.BOARD != null
                    ? android.os.Build.BOARD.toLowerCase(Locale.US) : "";
            String hardware = android.os.Build.HARDWARE != null
                    ? android.os.Build.HARDWARE.toLowerCase(Locale.US) : "";
            String model = android.os.Build.MODEL != null
                    ? android.os.Build.MODEL.toLowerCase(Locale.US) : "";
            String blob = board + " " + hardware + " " + model;
            if (blob.contains("6582") || blob.contains("y2")) return "y2";
            if (blob.contains("6572") || blob.contains("y1")) return "y1";
        } catch (Exception ignored) {}
        return "y1";
    }

    public static List<String> systemLikes() {
        return systemLikes(null);
    }

    public static boolean isSystemInterest(String item) {
        String key = normalizeItem(item);
        for (String s : systemLikes()) {
            if (s.equals(key)) return true;
        }
        if ("y1".equals(key) || "y2".equals(key)) return true;
        if (key.startsWith("reach ")) return true;
        return false;
    }

    public static List<String> loadLikes(SharedPreferences prefs) {
        return loadList(prefs, PREF_LIKES);
    }

    public static List<String> loadDislikes(SharedPreferences prefs) {
        return loadList(prefs, PREF_DISLIKES);
    }

    private static List<String> loadList(SharedPreferences prefs, String key) {
        if (prefs == null) return new ArrayList<String>();
        String raw = prefs.getString(key, "[]");
        List<String> out = new ArrayList<String>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String v = normalizeItem(arr.optString(i, ""));
                if (!v.isEmpty() && !out.contains(v)) out.add(v);
            }
        } catch (Exception ignored) {}
        Collections.sort(out);
        return out;
    }

    /** Extract y1/y2 from a peer's interest list for badges. */
    public static String modelFromInterests(List<String> likes) {
        if (likes == null) return "";
        for (String s : likes) {
            String n = normalizeItem(s);
            if ("y1".equals(n) || "y2".equals(n)) return n.toUpperCase(Locale.US);
            if (n.contains("y1")) return "Y1";
            if (n.contains("y2")) return "Y2";
        }
        return "";
    }
}
