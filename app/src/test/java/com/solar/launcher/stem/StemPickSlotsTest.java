package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;

import org.junit.Test;

/**
 * Unlimited Stem pick queue + TRANSITION presets + one-side hold.
 * Was: File[2] bind slots. Reversal: that bind/slotOf test shape.
 * 2026-07-20 / 2026-07-21
 */
public class StemPickSlotsTest {

    @Test
    public void toggleAppendAndUnlimited() throws Exception {
        ArrayList<File> q = new ArrayList<File>();
        File a = File.createTempFile("stem-a-", ".mp3");
        File b = File.createTempFile("stem-b-", ".mp3");
        File c = File.createTempFile("stem-c-", ".mp3");
        a.deleteOnExit();
        b.deleteOnExit();
        c.deleteOnExit();
        assertEquals(1, StemPickSlots.toggle(q, a));
        assertEquals(2, StemPickSlots.append(q, b));
        assertEquals(3, StemPickSlots.append(q, c));
        assertEquals(3, StemPickSlots.filled(q));
        assertTrue(StemPickSlots.canStart(q));
        assertEquals(1, StemPickSlots.positionOf(q, a));
        assertEquals(3, StemPickSlots.positionOf(q, c));
        assertEquals(3, StemPickSlots.orderedTracks(q).size());
        // Toggle remove. 2026-07-21
        assertEquals(0, StemPickSlots.toggle(q, b));
        assertEquals(2, StemPickSlots.filled(q));
        assertEquals(2, StemPickSlots.positionOf(q, c));
        StemPickSlots.clear(q);
        assertEquals(0, StemPickSlots.filled(q));
        assertFalse(StemPickSlots.canStart(q));
    }

    @Test
    public void singleTrackCanStart() throws Exception {
        ArrayList<File> q = new ArrayList<File>();
        File a = File.createTempFile("stem-one-", ".mp3");
        a.deleteOnExit();
        StemPickSlots.append(q, a);
        assertTrue(StemPickSlots.canStart(q));
        assertEquals(1, StemPickSlots.orderedTracks(q).size());
    }

    @Test
    public void appendIdempotentByPath() throws Exception {
        ArrayList<File> q = new ArrayList<File>();
        File a = File.createTempFile("stem-dup-", ".mp3");
        a.deleteOnExit();
        assertEquals(1, StemPickSlots.append(q, a));
        assertEquals(1, StemPickSlots.append(q, a));
        assertEquals(1, StemPickSlots.filled(q));
    }

    @Test
    public void selectAllSkipsDupes() throws Exception {
        ArrayList<File> q = new ArrayList<File>();
        File a = File.createTempFile("stem-sa-", ".mp3");
        File b = File.createTempFile("stem-sb-", ".mp3");
        a.deleteOnExit();
        b.deleteOnExit();
        StemPickSlots.append(q, a);
        ArrayList<File> candidates = new ArrayList<File>();
        candidates.add(a);
        candidates.add(b);
        assertEquals(1, StemPickSlots.selectAll(q, candidates));
        assertEquals(2, StemPickSlots.filled(q));
    }

    @Test
    public void transitionPresets() {
        assertEquals(4000L, StemControls.transitionMsForPreset(StemControls.TRANSITION_PRESET_LONG));
        assertEquals(8000L, StemControls.transitionMsForPreset(StemControls.TRANSITION_PRESET_OVERLAP));
        assertEquals(400L, StemControls.transitionMsForPreset(StemControls.TRANSITION_PRESET_WAVE));
        assertEquals((int) (4000L / StemControls.TRANSITION_TICK_MS),
                StemControls.transitionFadeSteps(4000L));
        assertEquals(1, StemControls.transitionFadeSteps(0L));
        assertTrue(StemControls.stemTransitionHoldOneSide(true, false));
        assertTrue(StemControls.stemTransitionHoldOneSide(false, true));
        assertFalse(StemControls.stemTransitionHoldOneSide(true, true));
        assertFalse(StemControls.stemTransitionHoldOneSide(false, false));
    }

    @Test
    public void fadeGainStepLinear() {
        assertEquals(0.5f, StemControls.fadeGainStep(0f, 1f, 5, 10), 0.001f);
        assertEquals(0f, StemControls.fadeGainStep(1f, 0f, 10, 10), 0.001f);
    }

    @Test
    public void placeholderLetter() {
        assertEquals('A', StemControls.placeholderLetter("Alive"));
        assertEquals('#', StemControls.placeholderLetter(""));
        // Prefers letter over digit when both present. 2026-07-21
        assertEquals('R', StemControls.placeholderLetter("7 rings"));
        assertEquals('7', StemControls.placeholderLetter("7"));
    }
}
