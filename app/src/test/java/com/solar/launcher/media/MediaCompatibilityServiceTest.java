package com.solar.launcher.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaCompatibilityServiceTest {
    @Test
    public void recognizesPlatformFormatsCaseInsensitively() {
        MediaCompatibilityService.Decision decision =
                MediaCompatibilityService.analyzeName("Artist/Track.FLAC");

        assertEquals("flac", decision.extension);
        assertEquals(MediaCompatibilityService.PlaybackPath.PLATFORM, decision.playbackPath);
        assertTrue(decision.canImportWithoutConversion);
        assertFalse(MediaCompatibilityService.prefersIjk("Track.FLAC"));
    }

    @Test
    public void routesKnownFallbackFormatsToIjk() {
        assertTrue(MediaCompatibilityService.prefersIjk("album/song.opus"));
        assertTrue(MediaCompatibilityService.prefersIjk("song.WEBM"));
        assertTrue(MediaCompatibilityService.prefersIjk("song.ape"));
        assertTrue(MediaCompatibilityService.prefersIjk("song.WMA"));
    }

    @Test
    public void rejectsUnknownFormatsInsteadOfClaimingConversion() {
        MediaCompatibilityService.Decision decision =
                MediaCompatibilityService.analyzeName("track.alac");

        assertEquals(MediaCompatibilityService.PlaybackPath.UNSUPPORTED, decision.playbackPath);
        assertFalse(decision.canImportWithoutConversion);
        assertFalse(MediaCompatibilityService.isSupportedAudioName("no-extension"));
    }
}
