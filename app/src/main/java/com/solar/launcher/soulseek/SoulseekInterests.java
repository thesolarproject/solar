package com.solar.launcher.soulseek;

import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Local Soulseek likes/dislikes lists (synced to server on login). */
public final class SoulseekInterests {
    private static final String PREF_LIKES = "soulseek_interest_likes";
    private static final String PREF_DISLIKES = "soulseek_interest_dislikes";
    private static final int MAX_ITEMS = 100;

    private SoulseekInterests() {}

    public static String normalizeItem(String item) {
        if (item == null) return "";
        return item.trim().toLowerCase(Locale.US);
    }

    /** Auto-synced on login — never shown in editor or dislikes. */
    public static List<String> systemLikes(android.content.Context context) {
        List<String> out = new ArrayList<String>();
        out.add("innioasis");
        out.add("reach client");
        if (context != null) {
            try {
                String version = com.solar.launcher.AppVersion.installedVersionName(context);
                if (version != null && !version.isEmpty()) {
                    out.add("reach " + version);
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Overload for cases where Context is unavailable. */
    public static List<String> systemLikes() {
        return systemLikes(null);
    }

    public static boolean isSystemInterest(String item) {
        String key = normalizeItem(item);
        for (String s : systemLikes()) {
            if (s.equals(key)) return true;
        }
        return false;
    }

    public static List<String> loadLikes(SharedPreferences prefs) {
        return loadList(prefs, PREF_LIKES);
    }

    /** User likes only — excludes system interests for UI display. */
    public static List<String> loadUserLikes(SharedPreferences prefs) {
        List<String> out = new ArrayList<String>();
        for (String s : loadLikes(prefs)) {
            if (!isSystemInterest(s)) out.add(s);
        }
        return out;
    }

    public static List<String> loadDislikes(SharedPreferences prefs) {
        return loadList(prefs, PREF_DISLIKES);
    }

    public static boolean addLike(SharedPreferences prefs, String item) {
        if (isSystemInterest(item)) return false;
        return addToList(prefs, PREF_LIKES, PREF_DISLIKES, normalizeItem(item));
    }

    public static boolean removeLike(SharedPreferences prefs, String item) {
        if (isSystemInterest(item)) return false;
        return removeFromList(prefs, PREF_LIKES, normalizeItem(item));
    }

    public static boolean addHate(SharedPreferences prefs, String item) {
        if (isSystemInterest(item)) return false;
        return addToList(prefs, PREF_DISLIKES, PREF_LIKES, normalizeItem(item));
    }

    public static boolean removeHate(SharedPreferences prefs, String item) {
        return removeFromList(prefs, PREF_DISLIKES, normalizeItem(item));
    }

    public static boolean moveToHate(SharedPreferences prefs, String item) {
        if (isSystemInterest(item)) return false;
        String key = normalizeItem(item);
        if (key.isEmpty()) return false;
        removeFromList(prefs, PREF_LIKES, key);
        return addToList(prefs, PREF_DISLIKES, PREF_LIKES, key);
    }

    public static boolean moveToLike(SharedPreferences prefs, String item) {
        if (isSystemInterest(item)) return false;
        String key = normalizeItem(item);
        if (key.isEmpty()) return false;
        removeFromList(prefs, PREF_DISLIKES, key);
        return addToList(prefs, PREF_LIKES, PREF_DISLIKES, key);
    }

    private static boolean addToList(SharedPreferences prefs, String targetKey,
            String otherKey, String item) {
        if (prefs == null || item == null || item.isEmpty()) return false;
        List<String> list = loadList(prefs, targetKey);
        if (list.contains(item)) return false;
        removeFromList(prefs, otherKey, item);
        if (list.size() >= MAX_ITEMS) return false;
        list.add(item);
        Collections.sort(list);
        saveList(prefs, targetKey, list);
        return true;
    }

    private static boolean removeFromList(SharedPreferences prefs, String key, String item) {
        if (prefs == null || item == null || item.isEmpty()) return false;
        if (isSystemInterest(item)) return false;
        List<String> list = loadList(prefs, key);
        if (!list.remove(item)) return false;
        saveList(prefs, key, list);
        return true;
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

    private static void saveList(SharedPreferences prefs, String key, List<String> values) {
        JSONArray arr = new JSONArray();
        for (String v : values) arr.put(v);
        prefs.edit().putString(key, arr.toString()).commit();
    }
}
