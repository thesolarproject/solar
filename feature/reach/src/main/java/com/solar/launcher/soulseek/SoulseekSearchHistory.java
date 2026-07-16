package com.solar.launcher.soulseek;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** ponytail: last N Reach search queries in prefs — O(n) scan, max 50 entries. */
public final class SoulseekSearchHistory {
    private static final String PREF_KEY = "soulseek_search_history";
    private static final String ENTRY_SEP = "\u001e";
    private static final int MAX_ENTRIES = 50;

    private SoulseekSearchHistory() {}

    public static void remember(SharedPreferences prefs, String query) {
        if (prefs == null || query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;
        List<String> list = load(prefs);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (q.equalsIgnoreCase(list.get(i))) list.remove(i);
        }
        list.add(0, q);
        while (list.size() > MAX_ENTRIES) list.remove(list.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(ENTRY_SEP);
            sb.append(list.get(i));
        }
        try {
            prefs.edit().putString(PREF_KEY, sb.toString()).commit();
        } catch (Exception ignored) {}
    }

    public static List<String> load(SharedPreferences prefs) {
        List<String> out = new ArrayList<String>();
        if (prefs == null) return out;
        try {
            String raw = prefs.getString(PREF_KEY, "");
            if (raw == null || raw.isEmpty()) return out;
            String[] parts = raw.split(ENTRY_SEP, -1);
            for (String p : parts) {
                if (p != null) {
                    String t = p.trim();
                    if (t.length() > 0) out.add(t);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }
}
