package com.solar.launcher.soulseek;

import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Local Soulseek peer block list and favorites. */
public final class SoulseekPeerPrefs {
    private static final String PREF_BLOCKED = "soulseek_blocked_peers";
    private static final String PREF_IGNORED = "soulseek_ignored_peers";
    private static final String PREF_FAVORITES = "soulseek_favorite_peers";

    private SoulseekPeerPrefs() {}

    public static Set<String> blocked(SharedPreferences prefs) {
        return loadSet(prefs, PREF_BLOCKED);
    }

    public static Set<String> favorites(SharedPreferences prefs) {
        return loadSet(prefs, PREF_FAVORITES);
    }

    public static Set<String> ignored(SharedPreferences prefs) {
        return loadSet(prefs, PREF_IGNORED);
    }

    public static boolean isIgnored(SharedPreferences prefs, String username) {
        if (username == null) return false;
        if (SolarDeveloperAccounts.isVirtualPeer(username) || SolarDeveloperAccounts.hideFromReachUi(username)) return false;
        return ignored(prefs).contains(username.toLowerCase(Locale.US));
    }

    public static void setIgnored(SharedPreferences prefs, String username, boolean ignored) {
        if (prefs == null || username == null || username.trim().isEmpty()) return;
        if (SolarDeveloperAccounts.isVirtualPeer(username) || SolarDeveloperAccounts.hideFromReachUi(username)) return;
        Set<String> set = ignored(prefs);
        String key = username.toLowerCase(Locale.US);
        if (ignored) set.add(key);
        else set.remove(key);
        saveSet(prefs, PREF_IGNORED, set);
    }

    public static boolean isBlocked(SharedPreferences prefs, String username) {
        if (username == null) return false;
        if (SolarDeveloperAccounts.isVirtualPeer(username) || SolarDeveloperAccounts.hideFromReachUi(username)) return false;
        return blocked(prefs).contains(username.toLowerCase(Locale.US));
    }

    public static boolean isFavorite(SharedPreferences prefs, String username) {
        if (username == null) return false;
        return favorites(prefs).contains(username.toLowerCase(Locale.US));
    }

    public static void setBlocked(SharedPreferences prefs, String username, boolean blocked) {
        if (prefs == null || username == null || username.trim().isEmpty()) return;
        if (SolarDeveloperAccounts.isVirtualPeer(username) || SolarDeveloperAccounts.hideFromReachUi(username)) return;
        Set<String> set = blocked(prefs);
        String key = username.toLowerCase(Locale.US);
        if (blocked) set.add(key);
        else set.remove(key);
        saveSet(prefs, PREF_BLOCKED, set);
    }

    public static void setFavorite(SharedPreferences prefs, String username, boolean favorite) {
        if (prefs == null || username == null || username.trim().isEmpty()) return;
        Set<String> set = favorites(prefs);
        String key = username.toLowerCase(Locale.US);
        if (favorite) set.add(key);
        else set.remove(key);
        saveSet(prefs, PREF_FAVORITES, set);
    }

    private static Set<String> loadSet(SharedPreferences prefs, String key) {
        if (prefs == null) return Collections.emptySet();
        String raw = prefs.getString(key, "[]");
        Set<String> out = new HashSet<String>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (!v.isEmpty()) out.add(v.toLowerCase(Locale.US));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void saveSet(SharedPreferences prefs, String key, Set<String> values) {
        JSONArray arr = new JSONArray();
        for (String v : values) arr.put(v);
        prefs.edit().putString(key, arr.toString()).commit();
    }
}
