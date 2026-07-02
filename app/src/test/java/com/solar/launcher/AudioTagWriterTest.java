package com.solar.launcher;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioTagWriterTest {

    @Test
    public void wavReturnsUnsupported() {
        File wav = new File("/music/track.wav");
        assertEquals(AudioTagWriter.EmbedResult.UNSUPPORTED_FORMAT, AudioTagWriter.capabilityFor(wav));
        assertFalse(AudioTagWriter.supportsEmbedding(wav));
    }

    @Test
    public void unknownExtensionReturnsUnsupported() {
        File txt = new File("/music/readme.txt");
        assertEquals(AudioTagWriter.EmbedResult.UNSUPPORTED_FORMAT, AudioTagWriter.capabilityFor(txt));
    }

    @Test
    public void mp3SupportsEmbedding() {
        File mp3 = new File("/music/track.mp3");
        assertTrue(AudioTagWriter.supportsEmbedding(mp3));
    }

    @Test
    public void sidecarBaseNameStripsAudioExtensions() {
        assertEquals("Album-Song", AudioTagWriter.sidecarBaseName("Album-Song.wav"));
        assertEquals("Album-Song", AudioTagWriter.sidecarBaseName("Album-Song.flac"));
    }
}
