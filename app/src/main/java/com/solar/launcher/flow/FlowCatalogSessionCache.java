package com.solar.launcher.flow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Session cache of built {@link FlowItem} lists — instant Flow re-enter on same library scan.
 * ponytail: keyed by {@code libraryScanGen}; stale snapshot only valid for matching gen + options.
 */
public final class FlowCatalogSessionCache {

    private int libraryGen = -1;
    private int optionsKey;
    private final Map<FlowMode, List<FlowItem>> byMode = new HashMap<FlowMode, List<FlowItem>>();
    /** Last built list per mode — paint-first reopen only when gen/options still match. */
    private final Map<FlowMode, List<FlowItem>> staleByMode = new HashMap<FlowMode, List<FlowItem>>();
    private int staleLibraryGen = -1;
    private int staleOptionsKey;

    private void syncGeneration(int libraryGen, int optionsKey) {
        if (libraryGen != this.libraryGen || optionsKey != this.optionsKey) {
            byMode.clear();
            this.libraryGen = libraryGen;
            this.optionsKey = optionsKey;
        }
    }

    public synchronized List<FlowItem> peek(FlowMode mode, int libraryGen, int optionsKey) {
        syncGeneration(libraryGen, optionsKey);
        return byMode.get(mode);
    }

    /**
     * Paint-first reopen when a prior catalog exists for the same library generation and options.
     * Returns null when sort/filter prefs or scan generation changed — never bind wrong rack order.
     */
    public synchronized List<FlowItem> peekStale(FlowMode mode, int libraryGen, int optionsKey) {
        if (mode == null || mode == FlowMode.UNSPECIFIED) mode = FlowMode.ALBUM;
        if (libraryGen != staleLibraryGen || optionsKey != staleOptionsKey) {
            return null;
        }
        List<FlowItem> fresh = byMode.get(mode);
        if (fresh != null && !fresh.isEmpty()) return fresh;
        return staleByMode.get(mode);
    }

    public synchronized void put(FlowMode mode, int libraryGen, int optionsKey, List<FlowItem> items) {
        syncGeneration(libraryGen, optionsKey);
        byMode.put(mode, items);
        if (items != null && !items.isEmpty()) {
            staleByMode.put(mode, items);
            staleLibraryGen = libraryGen;
            staleOptionsKey = optionsKey;
        }
    }

    public synchronized void clear() {
        byMode.clear();
        staleByMode.clear();
        libraryGen = -1;
        optionsKey = 0;
        staleLibraryGen = -1;
        staleOptionsKey = 0;
    }
}
