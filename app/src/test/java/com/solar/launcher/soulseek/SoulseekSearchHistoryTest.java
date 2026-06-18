package com.solar.launcher.soulseek;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SoulseekSearchHistoryTest {
    private static final class MemPrefs implements SharedPreferences {
        final Map<String, Object> map = new HashMap<String, Object>();

        @Override public Map<String, ?> getAll() { return map; }
        @Override public String getString(String key, String def) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : def;
        }
        @Override public int getInt(String key, int def) { return def; }
        @Override public long getLong(String key, long def) { return def; }
        @Override public float getFloat(String key, float def) { return def; }
        @Override public boolean getBoolean(String key, boolean def) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }
        @Override public Set<String> getStringSet(String key, Set<String> def) { return def; }
        @Override public boolean contains(String key) { return map.containsKey(key); }
        @Override public Editor edit() {
            return new Editor() {
                @Override public Editor putString(String key, String value) { map.put(key, value); return this; }
                @Override public Editor putInt(String key, int value) { map.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { map.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { map.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { map.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> values) { return this; }
                @Override public Editor remove(String key) { map.remove(key); return this; }
                @Override public Editor clear() { map.clear(); return this; }
                @Override public boolean commit() { return true; }
                @Override public void apply() {}
            };
        }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    }

    @Test
    public void remember_dedupesAndCaps() {
        MemPrefs prefs = new MemPrefs();
        SoulseekSearchHistory.remember(prefs, "alpha");
        SoulseekSearchHistory.remember(prefs, "beta");
        SoulseekSearchHistory.remember(prefs, "alpha");
        List<String> list = SoulseekSearchHistory.load(prefs);
        if (list.size() != 2) throw new AssertionError("dedupe size");
        if (!"alpha".equals(list.get(0))) throw new AssertionError("most recent first");
        for (int i = 0; i < 12; i++) SoulseekSearchHistory.remember(prefs, "q" + i);
        if (SoulseekSearchHistory.load(prefs).size() > 50) throw new AssertionError("cap");
    }
}
