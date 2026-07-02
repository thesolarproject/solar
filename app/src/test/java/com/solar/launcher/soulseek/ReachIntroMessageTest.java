package com.solar.launcher.soulseek;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReachIntroMessageTest {

    @Test
    public void isIntro_detectsMarker() {
        assertTrue(ReachIntroMessage.isIntro(ReachIntroMessage.MARKER + "hello"));
        assertFalse(ReachIntroMessage.isIntro("normal message"));
    }

    @Test
    public void strip_removesIntroLine() {
        String intro = ReachIntroMessage.MARKER + "hello from reach";
        assertEquals("", ReachIntroMessage.strip(intro));
    }

    @Test
    public void shouldHideOutgoing_intro() {
        assertTrue(ReachIntroMessage.shouldHideOutgoing(ReachIntroMessage.MARKER + "x"));
    }

    @Test
    public void shouldPersist_introNeverOnReachClient() {
        assertFalse(ReachIntroMessage.shouldPersistForReachClient(
                ReachIntroMessage.MARKER + "hello", "peer", "self", null));
    }

    @Test
    public void tracker_marksPeerAndRoom() {
        android.content.SharedPreferences prefs = new android.content.SharedPreferences() {
            private String val = "{}";

            @Override public java.util.Map<String, ?> getAll() { return null; }
            @Override public String getString(String key, String defValue) { return val; }
            @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) { return null; }
            @Override public int getInt(String key, int defValue) { return 0; }
            @Override public long getLong(String key, long defValue) { return 0; }
            @Override public float getFloat(String key, float defValue) { return 0; }
            @Override public boolean getBoolean(String key, boolean defValue) { return false; }
            @Override public boolean contains(String key) { return false; }
            @Override public Editor edit() {
                return new Editor() {
                    @Override public Editor putString(String key, String value) { val = value; return this; }
                    @Override public Editor putStringSet(String key, java.util.Set<String> values) { return this; }
                    @Override public Editor putInt(String key, int value) { return this; }
                    @Override public Editor putLong(String key, long value) { return this; }
                    @Override public Editor putFloat(String key, float value) { return this; }
                    @Override public Editor putBoolean(String key, boolean value) { return this; }
                    @Override public Editor remove(String key) { return this; }
                    @Override public Editor clear() { val = "{}"; return this; }
                    @Override public boolean commit() { return true; }
                    @Override public void apply() {}
                };
            }
            @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
            @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        };
        assertTrue(ReachIntroTracker.needsIntroToPeer(prefs, "alice"));
        ReachIntroTracker.markIntroSentToPeer(prefs, "alice");
        assertFalse(ReachIntroTracker.needsIntroToPeer(prefs, "alice"));
        assertTrue(ReachIntroTracker.needsIntroToRoom(prefs, "lobby"));
        ReachIntroTracker.markIntroSentToRoom(prefs, "lobby");
        assertFalse(ReachIntroTracker.needsIntroToRoom(prefs, "lobby"));
    }
}
