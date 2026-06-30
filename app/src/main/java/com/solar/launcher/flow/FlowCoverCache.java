package com.solar.launcher.flow;

import android.graphics.Bitmap;

import java.util.LinkedHashMap;
import java.util.Map;

/** LRU of pre-scaled Flow cover bitmaps (~128px). */
public final class FlowCoverCache {

    public interface Listener {
        void onCoverReady(String coverKey, Bitmap bitmap);
    }

    private static final int MAX_ENTRIES = 48;
    private static final int THUMB_PX = 128;

    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            if (size() <= MAX_ENTRIES) return false;
            Bitmap b = eldest.getValue();
            if (b != null && !b.isRecycled()) b.recycle();
            return true;
        }
    };

    public int thumbSizePx() {
        return THUMB_PX;
    }

    /** Viewport-aware thumb size (Cover Flow square cover). */
    public int thumbSizePx(float viewW, float viewH) {
        return CoverFlowLayout.metricsForViewport(viewW, viewH).thumbSizePx();
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
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
    }

    public synchronized void clear() {
        for (Bitmap b : cache.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        cache.clear();
    }
}
