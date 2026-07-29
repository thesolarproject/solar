package com.solar.launcher.youtube;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-06 — Wheel-tap search suggestions from recent YouTube queries.
 * Layman: remembers what you searched before and offers quick picks.
 * Technical: JSON string list in SharedPreferences, max six entries.
 * Reversal: delete; browse shows popular only until user types again.
 */
public final class YouTubeRecentSearches {

    private static final String PREFS = "youtube_recent_searches";
    private static final String KEY_LIST = "queries";
    private static final int MAX = 6;

    private YouTubeRecentSearches() {}

    public static List<String> get(Context ctx) {
        List<String> out = new ArrayList<String>();
        if (ctx == null) return out;
        try {
            String raw = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LIST, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            for (int i = 0; i < arr.length(); i++) {
                String q = arr.optString(i, "").trim();
                if (q.length() > 0) out.add(q);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void remember(Context ctx, String query) {
        if (ctx == null || query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;
        List<String> list = new ArrayList<String>();
        list.add(q);
        for (String prev : get(ctx)) {
            if (prev.equalsIgnoreCase(q)) continue;
            list.add(prev);
            if (list.size() >= MAX) break;
        }
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LIST, arr.toString())
                .apply();
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LIST)
                .commit();
    }
}
