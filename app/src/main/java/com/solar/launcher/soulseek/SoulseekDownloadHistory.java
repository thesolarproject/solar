package com.solar.launcher.soulseek;

import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Peers from successful Reach downloads — boosts search ranking for repeat sources. */
public final class SoulseekDownloadHistory {
    private static final String PREF_PEERS = "soulseek_download_peers";
    private static final int MAX_PEERS = 64;

    private SoulseekDownloadHistory() {}

    public static void record(SharedPreferences prefs, String username) {
        if (prefs == null || username == null) return;
        String peer = username.trim();
        if (peer.isEmpty()) return;
        Set<String> peers = loadPeerSet(prefs);
        String key = peer.toLowerCase(Locale.US);
        peers.remove(key);
        peers.add(key);
        while (peers.size() > MAX_PEERS) {
            String oldest = peers.iterator().next();
            peers.remove(oldest);
        }
        save(prefs, peers);
    }

    public static Set<String> loadPeerSet(SharedPreferences prefs) {
        if (prefs == null) return Collections.emptySet();
        String raw = prefs.getString(PREF_PEERS, "[]");
        Set<String> out = new HashSet<String>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "").trim().toLowerCase(Locale.US);
                if (!p.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void clear(SharedPreferences prefs) {
        if (prefs == null) return;
        prefs.edit().remove(PREF_PEERS).commit();
    }

    private static void save(SharedPreferences prefs, Set<String> peers) {
        JSONArray arr = new JSONArray();
        for (String p : peers) arr.put(p);
        prefs.edit().putString(PREF_PEERS, arr.toString()).commit();
    }
}
