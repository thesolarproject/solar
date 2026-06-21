package com.solar.launcher;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LibraryBrowsePrefsTest {

    private LibraryBrowsePrefs prefs;

    @Before
    public void setUp() {
        prefs = new LibraryBrowsePrefs(new MemSharedPreferences());
    }

    @Test
    public void defaultsMatchCurrentBehavior() {
        assertTrue(prefs.splitCredits());
        assertTrue(prefs.normalizeAlbumCase());
        assertTrue(prefs.normalizeHonorifics());
        assertEquals(LibraryBrowsePrefs.GUEST_BROWSE_AUTO, prefs.guestBrowseMode());
        assertEquals(LibraryBrowsePrefs.FILTER_ALL, prefs.artistFilter());
        assertEquals(LibraryBrowsePrefs.ARTIST_SORT_NAME, prefs.artistSort());
        assertEquals(LibraryBrowsePrefs.SONG_SORT_TITLE, prefs.songSort());
        assertTrue(prefs.albumOwnerSubtitles());
        assertTrue(prefs.guestSongSubtitles());
    }

    @Test
    public void cycleWraps() {
        assertEquals(LibraryBrowsePrefs.GUEST_BROWSE_ALWAYS_ALBUMS, prefs.cycleGuestBrowseMode());
        assertEquals(LibraryBrowsePrefs.GUEST_BROWSE_ALWAYS_SONGS, prefs.cycleGuestBrowseMode());
        assertEquals(LibraryBrowsePrefs.GUEST_BROWSE_AUTO, prefs.cycleGuestBrowseMode());

        for (int i = 0; i < 4; i++) prefs.cycleArtistFilter();
        assertEquals(LibraryBrowsePrefs.FILTER_ALL, prefs.artistFilter());

        for (int i = 0; i < 3; i++) prefs.cycleArtistSort();
        assertEquals(LibraryBrowsePrefs.ARTIST_SORT_NAME, prefs.artistSort());

        for (int i = 0; i < 4; i++) prefs.cycleSongSort();
        assertEquals(LibraryBrowsePrefs.SONG_SORT_TITLE, prefs.songSort());
    }

    /** Minimal in-memory SharedPreferences for JVM unit tests. */
    static final class MemSharedPreferences implements SharedPreferences {
        private final Map<String, Object> map = new HashMap<String, Object>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<String, Object>(map);
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = map.get(key);
            return v instanceof Set ? (Set<String>) v : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = map.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = map.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = map.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return map.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private final class MemEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<String, Object>();
            private final Set<String> removes = new HashSet<String>();

            @Override
            public Editor putString(String key, String value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                pending.put(key, values);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removes.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                map.clear();
                pending.clear();
                removes.clear();
                return this;
            }

            @Override
            public boolean commit() {
                for (String key : removes) map.remove(key);
                map.putAll(pending);
                pending.clear();
                removes.clear();
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
