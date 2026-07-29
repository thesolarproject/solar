package com.solar.launcher.youtube;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class YouTubeDiscoverSettingsTest {

    @Test
    public void regionCycleIsBoundedAndNormalizesUnknownValues() {
        assertEquals("US", YouTubeDiscoverSettings.nextRegion(""));
        assertEquals("CA", YouTubeDiscoverSettings.nextRegion("us"));
        assertEquals("", YouTubeDiscoverSettings.nextRegion("ZZ"));
    }

    @Test
    public void cachePresetCycleReturnsToSmallestBoundedSize() {
        assertEquals(2L * 1024L * 1024L,
                YouTubeDiscoverSettings.nextCacheBytes(1024L * 1024L));
        assertEquals(512L * 1024L,
                YouTubeDiscoverSettings.nextCacheBytes(4L * 1024L * 1024L));
        assertEquals(YouTubeDiscoverSettings.DEFAULT_CACHE_BYTES,
                YouTubeDiscoverSettings.nextCacheBytes(123L));
    }

    @Test
    public void durationPresetsRoundTrip() {
        for (int preset = 0; preset < 4; preset++) {
            assertEquals(preset, YouTubeDiscoverSettings.durationPreset(
                    YouTubeDiscoverSettings.minDurationSeconds(preset),
                    YouTubeDiscoverSettings.maxDurationSeconds(preset)));
        }
        assertEquals(0, YouTubeDiscoverSettings.nextDurationPreset(3));
    }
}
