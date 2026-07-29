package com.solar.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import com.solar.launcher.deezer.DeezerSearchHistory;
import com.solar.launcher.soulseek.SoulseekDownloadHistory;
import com.solar.launcher.soulseek.SoulseekSearchHistory;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class LocalAcquisitionHistoryTest {
    @Test
    public void clearRemovesEveryAcquisitionHistoryWithoutClearingOtherPreferences() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("unrelated_account", "keep").commit();
        GetMusicSearchHistory.remember(prefs, "ambient");
        SoulseekSearchHistory.remember(prefs, "jazz");
        DeezerSearchHistory.remember(prefs, "classical");
        SoulseekDownloadHistory.record(prefs, "trusted-peer");

        GetMusicSearchHistory.clear(prefs);
        SoulseekSearchHistory.clear(prefs);
        DeezerSearchHistory.clear(prefs);
        SoulseekDownloadHistory.clear(prefs);

        assertTrue(GetMusicSearchHistory.load(prefs).isEmpty());
        assertTrue(SoulseekSearchHistory.load(prefs).isEmpty());
        assertTrue(DeezerSearchHistory.load(prefs).isEmpty());
        assertTrue(SoulseekDownloadHistory.loadPeerSet(prefs).isEmpty());
        assertTrue(prefs.contains("unrelated_account"));
        assertFalse(prefs.getString("unrelated_account", "").isEmpty());
    }

    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<String, Object>();

        @Override public Map<String, ?> getAll() { return values; }
        @Override public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            return fallback;
        }
        @Override public int getInt(String key, int fallback) { return fallback; }
        @Override public long getLong(String key, long fallback) { return fallback; }
        @Override public float getFloat(String key, float fallback) { return fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() {
            return new Editor() {
                @Override public Editor putString(String key, String value) {
                    values.put(key, value);
                    return this;
                }
                @Override public Editor putStringSet(String key, Set<String> value) {
                    values.put(key, value);
                    return this;
                }
                @Override public Editor putInt(String key, int value) {
                    values.put(key, value);
                    return this;
                }
                @Override public Editor putLong(String key, long value) {
                    values.put(key, value);
                    return this;
                }
                @Override public Editor putFloat(String key, float value) {
                    values.put(key, value);
                    return this;
                }
                @Override public Editor putBoolean(String key, boolean value) {
                    values.put(key, value);
                    return this;
                }
                @Override public Editor remove(String key) {
                    values.remove(key);
                    return this;
                }
                @Override public Editor clear() {
                    values.clear();
                    return this;
                }
                @Override public boolean commit() { return true; }
                @Override public void apply() {}
            };
        }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}
    }
}
