package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Host checks for idle auto power-off vs playback.
 * Layman: the player must not turn itself off while something is playing.
 * 2026-10-05
 */
public class InactivityShutdownPlaybackTest {

    @Test
    public void nothingPlaying_allowsShutdown() {
        assertFalse(MainActivity.isAnyPlaybackKeepingAwakeForTest(false, false, false, false, false));
    }

    /** Gapless SolarTransport playing while the legacy MediaPlayer sits idle. */
    @Test
    public void transportPlaying_idleMediaPlayer_blocksShutdown() {
        assertTrue(MainActivity.isAnyPlaybackKeepingAwakeForTest(true, false, false, false, false));
    }

    @Test
    public void eachEngine_blocksShutdown() {
        assertTrue(MainActivity.isAnyPlaybackKeepingAwakeForTest(false, true, false, false, false));
        assertTrue(MainActivity.isAnyPlaybackKeepingAwakeForTest(false, false, true, false, false));
        assertTrue(MainActivity.isAnyPlaybackKeepingAwakeForTest(false, false, false, true, false));
        assertTrue(MainActivity.isAnyPlaybackKeepingAwakeForTest(false, false, false, false, true));
    }
}
