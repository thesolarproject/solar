package com.solar.launcher.podcast;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PodcastResumeStoreTest {

    @Test
    public void selfCheck() {
        PodcastResumeStore.selfCheck();
    }

    @Test
    public void urlKeyNormalizesHttpsHttp() {
        String k1 = PodcastResumeStore.keyForUrl("https://host/ep.mp3");
        String k2 = PodcastResumeStore.keyForUrl("http://host/ep.mp3");
        assertEquals(k1, k2);
    }

    @Test
    public void parseStoredPosition() {
        assertEquals(60000, PodcastResumeStore.parseStoredPosition("60000|3600000|1", 3600000));
        assertEquals(0, PodcastResumeStore.parseStoredPosition("2000|100000|1", 100000));
        assertEquals(0, PodcastResumeStore.parseStoredPosition("98000|100000|1", 100000));
        assertEquals(0, PodcastResumeStore.parseStoredPosition(null, 100000));
        // streaming: partial player duration must not discard a saved full-episode position
        assertEquals(1200000, PodcastResumeStore.parseStoredPosition("1200000|3600000|1", 600000));
    }

    @Test
    public void effectiveDurationNeverShrinks() {
        assertEquals(3600000, PodcastResumeStore.effectiveDurationMs(600000, 3600000));
        assertEquals(3600000, PodcastResumeStore.effectiveDurationMs(3600000, 600000));
    }

    @Test
    public void fileKeyUsesAbsolutePath() {
        File f = new File(PodcastLibrary.ROOT, "Show/ep.mp3");
        assertTrue(PodcastResumeStore.keyForFile(f).contains(f.getAbsolutePath()));
    }
}
