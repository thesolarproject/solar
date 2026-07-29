package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Onboarding prefs + soft scrub math + Pre-Save policy + context rows. 2026-07-21 */
public class StemMixOnboardingAndScrubTest {

    /** Tiny in-memory prefs for host unit tests. 2026-07-21 */
    public static final class MemPrefs implements SharedPreferences {
        final Map<String, Object> map = new HashMap<String, Object>();

        @Override
        public Map<String, ?> getAll() {
            return map;
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
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
            return new Editor() {
                final Map<String, Object> pending = new HashMap<String, Object>();
                boolean clear;

                @Override
                public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(String key, Set<String> values) {
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
                    pending.put(key, null);
                    return this;
                }

                @Override
                public Editor clear() {
                    clear = true;
                    return this;
                }

                @Override
                public boolean commit() {
                    apply();
                    return true;
                }

                @Override
                public void apply() {
                    if (clear) map.clear();
                    for (Map.Entry<String, Object> e : pending.entrySet()) {
                        if (e.getValue() == null) map.remove(e.getKey());
                        else map.put(e.getKey(), e.getValue());
                    }
                }
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}
    }

    @Test
    public void journeyPrefsRoundTrip() {
        MemPrefs prefs = new MemPrefs();
        assertTrue(StemMixOnboardingPrefs.needsQueueJourney(prefs));
        StemMixOnboardingPrefs.markQueueJourneySeen(prefs);
        assertFalse(StemMixOnboardingPrefs.needsQueueJourney(prefs));
        StemMixOnboardingPrefs.reShowQueueJourney(prefs);
        assertTrue(StemMixOnboardingPrefs.needsQueueJourney(prefs));
        assertTrue(StemMixOnboardingPrefs.pageProse(StemMixOnboardingPrefs.PAGE_HOLD_SCRUB).length() > 5);
    }

    @Test
    public void softScrubMath() {
        assertEquals(5000, StemMixSoftScrub.clampSeekMs(5000, 10000));
        assertEquals(0, StemMixSoftScrub.clampSeekMs(-1, 10000));
        assertEquals("1:05", StemMixSoftScrub.formatMmSs(65_000));
        assertTrue(StemMixSoftScrub.statusLine(1000, 2000).contains("/"));
        assertEquals(0.5f, StemMixSoftScrub.thumbFrac(5000, 10000), 0.001f);
        assertEquals(5000, StemMixSoftScrub.cursorFromThumb(0.5f, 10000));
        assertEquals(-2500, StemMixSoftScrub.wheelDeltaMs(100_000, 1));
        assertEquals(2500, StemMixSoftScrub.wheelDeltaMs(100_000, -1));
    }

    /** Circular pad scrub: frac ↔ angle + beat-match snap. 2026-07-21 */
    @Test
    public void circularScrubFracAngleAndBeatMatch() {
        assertEquals(-90f, StemMixSoftScrub.angleDegFromFrac(0f), 0.01f);
        assertEquals(90f, StemMixSoftScrub.angleDegFromFrac(0.5f), 0.01f);
        assertEquals(0f, StemMixSoftScrub.fracFromAngleDeg(-90f), 0.001f);
        assertEquals(0.5f, StemMixSoftScrub.fracFromAngleDeg(90f), 0.001f);
        assertEquals(0.25f, StemMixSoftScrub.fracFromAngleDeg(0f), 0.001f);
        float[] xy = new float[2];
        StemMixSoftScrub.cursorXy(100f, 100f, 50f, 0f, xy);
        assertEquals(100f, xy[0], 0.5f);
        assertEquals(50f, xy[1], 0.5f);
        assertTrue(StemMixSoftScrub.scrubFocusHaloScale() < 1f);
        // 120 BPM → 500ms/beat; 740 snaps to 500 or 1000. 2026-07-21
        int snapped = StemMixSoftScrub.beatMatchSeekMs(740, 60_000, 120f);
        assertEquals(500, snapped);
        assertEquals(0, StemMixSoftScrub.beatMatchSeekMs(-10, 1000, 120f));
    }

    @Test
    public void preSavePolicy() {
        java.io.File[] files = new java.io.File[] {
                new java.io.File("a.mp3"), new java.io.File("b.mp3")
        };
        boolean[] ready = new boolean[] { true, false };
        assertEquals(1, StemMixPreSavePolicy.needingCook(files, ready).size());
        assertTrue(StemMixPreSavePolicy.shouldBakeInstrumentalAfterFullStems(true, false));
        assertFalse(StemMixPreSavePolicy.shouldBakeInstrumentalAfterFullStems(true, true));
        assertEquals(QueuePrepStatus.KEY_BAKE_INSTRUMENTAL,
                StemMixPreSavePolicy.prepKeyForPhase("bake mix"));
    }

    @Test
    public void contextRowsSlotAndSession() {
        assertEquals(StemMixContextRows.SLOT_ROW_COUNT,
                StemMixContextRows.slotRows(1).length);
        assertEquals(StemMixContextRows.SESSION_ROW_COUNT,
                StemMixContextRows.sessionRows(false).length);
        assertEquals(StemControls.TRANSITION_PRESET_LONG,
                StemMixContextRows.transitionPresetForSessionRow(
                        StemMixContextRows.SESSION_TRANSITION_LONG));
        assertEquals(-1, StemMixContextRows.transitionPresetForSessionRow(
                StemMixContextRows.SESSION_PLAY_QUEUE));
        assertTrue(StemMixContextRows.isSessionPauseRow(StemMixContextRows.SESSION_PAUSE));
        assertTrue(StemMixContextRows.isSessionHomeRow(StemMixContextRows.SESSION_HOME));
    }

    @Test
    public void gestureHelpers() {
        assertTrue(StemControls.stemSlotHoldOneSide(true, false));
        assertTrue(StemControls.stemSessionContextBothSidesHeld(true, true));
        assertTrue(StemControls.mixSlotHoldPlayAlone(true, false, false));
        assertFalse(StemControls.mixSlotHoldPlayAlone(true, true, false));
    }
}
