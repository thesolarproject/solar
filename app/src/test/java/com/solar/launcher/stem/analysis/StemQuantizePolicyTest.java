package com.solar.launcher.stem.analysis;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM tests for StemFM-style Quantize Performance delay math. 2026-08-01
 */
public class StemQuantizePolicyTest {

    @Test
    public void offIsAlwaysZero() {
        assertEquals(0, StemQuantizePolicy.delayMs(StemQuantizePolicy.OFF, 120f, 0));
        assertEquals(0, StemQuantizePolicy.delayMs(StemQuantizePolicy.OFF, 90f, 9999));
    }

    @Test
    public void beatSnap120() {
        // 120 BPM → 500 ms/beat. Position 100 ms → next beat at 500 → delay 400.
        assertEquals(400, StemQuantizePolicy.delayMs(StemQuantizePolicy.BEAT, 120f, 100));
        // Exactly on a beat → next beat (full period).
        assertEquals(500, StemQuantizePolicy.delayMs(StemQuantizePolicy.BEAT, 120f, 500));
        // Just past a beat → short wait.
        assertEquals(100, StemQuantizePolicy.delayMs(StemQuantizePolicy.BEAT, 120f, 400));
    }

    @Test
    public void beatSnap90() {
        // 90 BPM → ~667 ms/beat.
        int d = StemQuantizePolicy.delayMs(StemQuantizePolicy.BEAT, 90f, 0);
        assertTrue("delay=" + d, d > 600 && d <= 700);
    }

    @Test
    public void barSnap120() {
        // Bar = 4 beats = 2000 ms at 120 BPM.
        assertEquals(1800, StemQuantizePolicy.delayMs(StemQuantizePolicy.BAR, 120f, 200));
        assertEquals(2000, StemQuantizePolicy.delayMs(StemQuantizePolicy.BAR, 120f, 2000));
    }

    @Test
    public void halfBarSnap120() {
        // Half bar = 2 beats = 1000 ms at 120 BPM.
        assertEquals(300, StemQuantizePolicy.delayMs(StemQuantizePolicy.HALF_BAR, 120f, 700));
        assertEquals(1000, StemQuantizePolicy.delayMs(StemQuantizePolicy.HALF_BAR, 120f, 1000));
    }

    @Test
    public void capsAtFourBars() {
        // Position 19000 of a 2000 ms grid → next is 20000 → delay 1000 (fine).
        assertEquals(1000, StemQuantizePolicy.delayMs(StemQuantizePolicy.BAR, 120f, 19000));
        // Degenerate bpm → default 120 grid, still bounded.
        int d = StemQuantizePolicy.delayMs(StemQuantizePolicy.BAR, 0f, 0);
        assertTrue(d >= 0 && d <= 8000);
    }

    @Test
    public void labels() {
        assertEquals("Quantize · Off", StemQuantizePolicy.label(StemQuantizePolicy.OFF));
        assertEquals("Quantize · Beat", StemQuantizePolicy.label(StemQuantizePolicy.BEAT));
        assertEquals("Quantize · Half Bar", StemQuantizePolicy.label(StemQuantizePolicy.HALF_BAR));
        assertEquals("Quantize · Bar", StemQuantizePolicy.label(StemQuantizePolicy.BAR));
    }
}
