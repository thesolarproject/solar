package com.solar.launcher;

/** Visible slice of a large queue list — browse mode only. */
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
}
