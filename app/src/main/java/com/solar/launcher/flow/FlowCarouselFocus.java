package com.solar.launcher.flow;

import java.util.List;

/** Carousel focus index after catalog bind — testable without FlowScreenHost. */
public final class FlowCarouselFocus {

    private FlowCarouselFocus() {}

    /**
     * Resolve carousel index after catalog (re)bind.
     * Home/menu entry (not from library section) → index 0 (iPod rack head).
     */
    public static int resolveIndex(List<FlowItem> items, FlowMode mode, String focusKey,
            boolean enteredFromSection, String residentMatchKey,
            String savedMatchKey, int savedIndex, FlowEngine engine) {
        if (items == null || items.isEmpty()) return 0;
        if (focusKey != null && !focusKey.isEmpty()) {
            int found = engine.findIndexForKey(items, focusKey);
            if (found >= 0) return found;
        }
        if ((focusKey == null || focusKey.isEmpty()) && !enteredFromSection) {
            return 0;
        }
        if (residentMatchKey != null && !residentMatchKey.isEmpty()) {
            int found = engine.findIndexForKey(items, residentMatchKey);
            if (found >= 0) return found;
        }
        if (savedMatchKey != null && !savedMatchKey.isEmpty()) {
            int found = engine.findIndexForKey(items, savedMatchKey);
            if (found >= 0) return found;
        }
        if (savedIndex >= 0 && savedIndex < items.size()) return savedIndex;
        return 0;
    }
}
