package com.solar.launcher.stem;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Decoder choice stays behind the shared SolarTransport origin contract.
 * The test is pure JVM coverage: no MediaPlayer or IJK native library is loaded.
 */
public class StemMixerOriginPolicyTest {

    @Test
    public void networkOriginsAlwaysUseIjk() {
        assertTrue(StemMixer.shouldUseIjkOrigin("http://example.test/track.mp3", false));
        assertTrue(StemMixer.shouldUseIjkOrigin("HTTPS://example.test/track.mp3", false));
    }

    @Test
    public void callerCanRouteLocalFallbackFormatToIjk() {
        assertTrue(StemMixer.shouldUseIjkOrigin("/music/track.opus", true));
        assertTrue(StemMixer.shouldUseIjkOrigin("/cache/youtube_play/track.m4a", true));
    }

    @Test
    public void ordinaryLocalOriginUsesPlatformDecoder() {
        assertFalse(StemMixer.shouldUseIjkOrigin("/music/artist/track.mp3", false));
    }

    @Test
    public void nullOriginNeverSelectsIjk() {
        assertFalse(StemMixer.shouldUseIjkOrigin(null, false));
        assertFalse(StemMixer.shouldUseIjkOrigin(null, true));
    }
}
