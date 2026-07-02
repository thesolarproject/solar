package com.solar.launcher.flow;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class FlowCatalogSessionCacheTest {

    private static List<FlowItem> threeAlbums() {
        return Arrays.asList(
                FlowItem.album("A", "", "a|x", Collections.<java.io.File>emptyList(), ""),
                FlowItem.album("B", "", "b|x", Collections.<java.io.File>emptyList(), ""),
                FlowItem.album("C", "", "c|x", Collections.<java.io.File>emptyList(), ""));
    }

    @Test
    public void peekStaleRejectsLibraryGenMismatch() {
        FlowCatalogSessionCache cache = new FlowCatalogSessionCache();
        List<FlowItem> items = threeAlbums();
        cache.put(FlowMode.ALBUM, 1, 0, items);
        assertNull(cache.peekStale(FlowMode.ALBUM, 2, 0));
        assertSame(items, cache.peek(FlowMode.ALBUM, 1, 0));
    }

    @Test
    public void peekStaleRejectsOptionsKeyMismatch() {
        FlowCatalogSessionCache cache = new FlowCatalogSessionCache();
        List<FlowItem> items = threeAlbums();
        cache.put(FlowMode.ALBUM, 1, 10, items);
        assertNull(cache.peekStale(FlowMode.ALBUM, 1, 11));
        assertSame(items, cache.peekStale(FlowMode.ALBUM, 1, 10));
    }

    @Test
    public void clearDropsStaleSnapshots() {
        FlowCatalogSessionCache cache = new FlowCatalogSessionCache();
        cache.put(FlowMode.ALBUM, 1, 0, threeAlbums());
        cache.clear();
        assertNull(cache.peek(FlowMode.ALBUM, 1, 0));
        assertNull(cache.peekStale(FlowMode.ALBUM, 1, 0));
    }
}
