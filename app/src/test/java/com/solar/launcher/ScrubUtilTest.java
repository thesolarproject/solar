package com.solar.launcher;

import org.junit.Test;

public class ScrubUtilTest {
    @Test
    public void clampScrubPositionMs_respectsDuration() {
        if (MainActivity.clampScrubPositionMs(-1000, 60000) != 0) throw new AssertionError("floor");
        if (MainActivity.clampScrubPositionMs(90000, 60000) != 60000) throw new AssertionError("ceiling");
        if (MainActivity.clampScrubPositionMs(30000, 60000) != 30000) throw new AssertionError("mid");
    }

    @Test
    public void clampScrubPositionMs_bufferShorterThanDisplayDuration() {
        if (MainActivity.clampScrubPositionMs(120000, 60000) != 60000) {
            throw new AssertionError("buffer cap");
        }
    }

    /** Seek-vs-completion suppression window math. 2026-08-01 */
    @Test
    public void userAudioSeekRecentlyForTest_windowBoundary() {
        // Completion 1ms after a seek is seek noise.
        if (!MainActivity.userAudioSeekRecentlyForTest(1000L, 1001L, 2000L)) {
            throw new AssertionError("inside window should suppress");
        }
        // Exactly at the boundary (>= window) is NOT suppressed.
        if (MainActivity.userAudioSeekRecentlyForTest(1000L, 3000L, 2000L)) {
            throw new AssertionError("boundary should not suppress");
        }
        // Long after the seek is a genuine completion.
        if (MainActivity.userAudioSeekRecentlyForTest(1000L, 9000L, 2000L)) {
            throw new AssertionError("outside window should not suppress");
        }
        // Same timestamp (completion immediately after seek record).
        if (!MainActivity.userAudioSeekRecentlyForTest(1000L, 1000L, 2000L)) {
            throw new AssertionError("same tick should suppress");
        }
        // Zero window never suppresses.
        if (MainActivity.userAudioSeekRecentlyForTest(1000L, 1000L, 0L)) {
            throw new AssertionError("zero window should not suppress");
        }
    }
}
