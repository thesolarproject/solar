package com.solar.launcher.flow;

import android.graphics.Bitmap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** LRU of pre-scaled Flow cover bitmaps with distance-priority eviction (Rockbox free_slide_prio). */
public final class FlowCoverCache {

    public interface Listener {
        void onCoverReady(String coverKey, Bitmap bitmap);
    }

    private static final int MAX_ENTRIES = 48;
    private static final int EVICT_DISTANCE = 6;

    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            if (size() <= MAX_ENTRIES) return false;
            recycleUnlessPlaceholder(eldest.getValue());
            return true;
        }
    };

    private Bitmap emptyPlaceholder;
    private int emptyPlaceholderPx;

    public int thumbSizePx() {
        return AlbumArtCache.THUMB_PX;
    }

    /** Viewport-aware thumb size (Cover Flow square cover). */
    public int thumbSizePx(float viewW, float viewH) {
        return CoverFlowLayout.metricsForViewport(viewW, viewH).thumbSizePx();
    }

    /** Shared empty slide bitmap — never evicted or recycled by LRU. */
    public synchronized Bitmap emptyPlaceholder(int sizePx) {
        if (sizePx <= 0) sizePx = 96;
        if (emptyPlaceholder != null && emptyPlaceholderPx == sizePx
                && !emptyPlaceholder.isRecycled()) {
            return emptyPlaceholder;
        }
        if (emptyPlaceholder != null && !emptyPlaceholder.isRecycled()) {
            emptyPlaceholder.recycle();
        }
        emptyPlaceholderPx = sizePx;
        emptyPlaceholder = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
        emptyPlaceholder.eraseColor(0xFF2A2A30);
        return emptyPlaceholder;
    }

    public synchronized Bitmap get(String coverKey) {
        if (coverKey == null) return null;
        Bitmap b = cache.get(coverKey);
        if (b != null && b.isRecycled()) {
            cache.remove(coverKey);
            return null;
        }
        return b;
    }

    public synchronized void put(String coverKey, Bitmap bitmap) {
        if (coverKey == null || bitmap == null) return;
        Bitmap old = cache.put(coverKey, bitmap);
        recycleUnlessPlaceholder(old);
    }

    /**
     * Evict covers far from carousel focus — keeps RAM for nearby slides.
     * ponytail: linear scan of LRU map; upgrade to index→key if catalog grows huge.
     */
    public synchronized void evictFarFrom(int focusIndex, List<FlowItem> items) {
        if (items == null || items.isEmpty() || cache.size() <= MAX_ENTRIES / 2) return;
        java.util.Set<String> cachedKeys = new java.util.HashSet<String>(cache.keySet());
        Map<String, Integer> keyToIndex = new java.util.HashMap<String, Integer>(cachedKeys.size());
        for (int i = 0; i < items.size(); i++) {
            FlowItem item = items.get(i);
            if (item != null && item.coverKey != null && cachedKeys.contains(item.coverKey)) {
                keyToIndex.put(item.coverKey, i);
            }
        }
        Iterator<Map.Entry<String, Bitmap>> it = cache.entrySet().iterator();
        while (it.hasNext() && cache.size() > MAX_ENTRIES / 2) {
            Map.Entry<String, Bitmap> e = it.next();
            Integer idx = keyToIndex.get(e.getKey());
            if (idx == null || Math.abs(idx - focusIndex) > EVICT_DISTANCE) {
                recycleUnlessPlaceholder(e.getValue());
                it.remove();
            }
        }
    }

    private void recycleUnlessPlaceholder(Bitmap b) {
        if (b != null && b != emptyPlaceholder && !b.isRecycled()) b.recycle();
    }

    public synchronized void clear() {
        for (Bitmap b : cache.values()) {
            recycleUnlessPlaceholder(b);
        }
        cache.clear();
    }
}
