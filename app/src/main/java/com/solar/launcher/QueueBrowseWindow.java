package com.solar.launcher;

/**
 * Visible slice of a large queue list — browse mode only.
 *
 * Viewport slot rules (3 visible rows, {@link QueueMoveWindow#VISIBLE_ROWS}):
 * <ul>
 *   <li>Browse: first track anchors slot 0, last track slot 2, middle rows slot 1 when count &gt; 4.</li>
 *   <li>Move (handled in {@code ThemedContextMenu}): mover stays in center slot until first/last.</li>
 * </ul>
 * All three slots must show consecutive tracks with no empty gap between them.
 */
final class QueueBrowseWindow {
    static final int VIRTUAL_MIN_ROWS = 16;
    static final int ROW_BUFFER = 2;

    private QueueBrowseWindow() {}

    static int windowStart(int focusIdx, int count, int visibleRows, int buffer) {
        if (count <= 0) return 0;
        int windowSize = windowSize(visibleRows, buffer);
        if (count <= windowSize) return 0;
        int start = focusIdx - visibleRows / 2 - buffer;
        if (start < 0) return 0;
        int maxStart = count - windowSize;
        return Math.min(start, maxStart);
    }

    static int windowSize(int visibleRows, int buffer) {
        return visibleRows + buffer * 2;
    }

    static int windowEnd(int start, int count, int visibleRows, int buffer) {
        return Math.min(count, start + windowSize(visibleRows, buffer));
    }

    /** Viewport row for browse scroll — edges anchor top/bottom; center lock when count > 4. */
    static int browseViewportSlot(int index, int count, int viewportRows) {
        if (count <= 0 || index < 0 || index >= count) return 0;
        if (index == 0) return 0;
        if (index == count - 1) return Math.max(0, viewportRows - 1);
        if (count <= 4) return Math.min(index, Math.max(0, viewportRows - 1));
        return 1;
    }

    /** Top padding when queue content is shorter than the fixed viewport. */
    static int shortListTopPadding(int focusIdx, int count, int viewportPx, int slotH) {
        if (slotH <= 0 || count <= 0 || viewportPx <= 0) return 0;
        int contentH = count * slotH;
        if (contentH >= viewportPx) return 0;
        if (focusIdx <= 0) return 0;
        if (focusIdx >= count - 1) return viewportPx - contentH;
        if (count <= 4) return (viewportPx - contentH) / 2;
        return 0;
    }
}
