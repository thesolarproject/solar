package com.solar.launcher.youtube;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class YouTubeMetadataCacheTest {

    private File directory;
    private YouTubeMetadataCache cache;

    @Before
    public void setUp() throws Exception {
        directory = Files.createTempDirectory("solar-youtube-cache").toFile();
        cache = new YouTubeMetadataCache(directory, 256L * 1024L);
    }

    @After
    public void tearDown() {
        delete(directory);
    }

    @Test
    public void storesOverwritesAndClearsMetadata() {
        cache.put("search:one", "{\"items\":[{\"id\":\"one\"}]}");
        YouTubeMetadataCache.Hit first =
                cache.get("search:one", 60_000L, 120_000L);
        assertEquals("{\"items\":[{\"id\":\"one\"}]}", first.payload);
        assertFalse(first.stale);

        cache.put("search:one", "{\"items\":[{\"id\":\"two\"}]}");
        assertEquals("{\"items\":[{\"id\":\"two\"}]}",
                cache.get("search:one", 60_000L, 120_000L).payload);

        cache.clear();
        assertNull(cache.get("search:one", 60_000L, 120_000L));
    }

    @Test
    public void labelsExpiredFreshnessAsStaleButKeepsOfflineCopy() throws Exception {
        cache.put("popular:US", "{\"items\":[]}");
        Thread.sleep(5L);
        YouTubeMetadataCache.Hit hit = cache.get("popular:US", 1L, 60_000L);
        assertTrue(hit.stale);
        assertTrue(hit.ageMs >= 1L);
    }

    @Test
    public void rejectsOversizeEntriesAndHonorsTotalBound() {
        StringBuilder tooLarge = new StringBuilder();
        for (int i = 0; i < 513 * 1024; i++) tooLarge.append('x');
        cache.put("oversize", tooLarge.toString());
        assertNull(cache.get("oversize", 60_000L, 120_000L));

        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 180 * 1024; i++) large.append('a');
        cache.setMaxBytes(128L * 1024L);
        cache.put("large", large.toString());
        assertTrue(cache.sizeBytes() <= 128L * 1024L);
    }

    private static void delete(File value) {
        if (value == null || !value.exists()) return;
        File[] children = value.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        value.delete();
    }
}
