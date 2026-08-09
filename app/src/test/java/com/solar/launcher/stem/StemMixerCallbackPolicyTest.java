package com.solar.launcher.stem;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure callback-generation coverage; no MediaPlayer or IJK native library is loaded. */
public class StemMixerCallbackPolicyTest {

    @Test
    public void currentCallbackIsDeliveredWhenMixerIsActive() {
        assertTrue(StemMixer.shouldDeliverCallback(false, 4, 4));
    }

    @Test
    public void releasedMixerSuppressesQueuedCallback() {
        assertFalse(StemMixer.shouldDeliverCallback(true, 4, 4));
    }

    @Test
    public void callbackFromPreviousLoadIsSuppressed() {
        assertFalse(StemMixer.shouldDeliverCallback(false, 5, 4));
    }

    /** Seek-noise window: completion before expiry is suppressed; at/after expiry it is not. 2026-08-01 */
    @Test
    public void seekCompletionSuppressionWindow() {
        assertTrue(StemMixer.seekSuppressionActiveForTest(2_000L, 1_999L));
        assertTrue(StemMixer.seekSuppressionActiveForTest(2_000L, 0L));
        assertFalse(StemMixer.seekSuppressionActiveForTest(2_000L, 2_000L));
        assertFalse(StemMixer.seekSuppressionActiveForTest(0L, 1_000L));
        assertFalse(StemMixer.seekSuppressionActiveForTest(-500L, 0L));
    }
}
