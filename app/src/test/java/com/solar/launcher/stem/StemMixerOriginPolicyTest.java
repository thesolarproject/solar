package com.solar.launcher.stem;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StemMixerOriginPolicyTest {
    @Test
    public void networkOriginsAlwaysUseIjk() {
        assertTrue(StemMixer.shouldUseIjkOrigin("http://example.test/track.mp3", false));
        assertTrue(StemMixer.shouldUseIjkOrigin("HTTPS://example.test/track.mp3", false));
    }

    @Test
    public void callerCanRouteLocalFallbackFormatToIjk() {
        assertTrue(StemMixer.shouldUseIjkOrigin("/music/track.ape", true));
        assertFalse(StemMixer.shouldUseIjkOrigin("/music/track.mp3", false));
    }
}
