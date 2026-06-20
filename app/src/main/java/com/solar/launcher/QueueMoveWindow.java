package com.solar.launcher;

/** Fixed 3-slot ribbon for context-menu queue reorder — center is always the mover. */
final class QueueMoveWindow {
    static final int VISIBLE_ROWS = 3;
    static final int RIBBON_ABOVE = 0;
    static final int RIBBON_CENTER = 1;
    static final int RIBBON_BELOW = 2;

    private QueueMoveWindow() {}

    static int ribbonAboveIndex(int moveIdx) {
        return moveIdx > 0 ? moveIdx - 1 : -1;
    }

    static int ribbonBelowIndex(int moveIdx, int count) {
        if (moveIdx < 0 || count <= 0) return -1;
        return moveIdx < count - 1 ? moveIdx + 1 : -1;
    }

    static boolean isNowPlayingSlot(int idx, int npIdx) {
        return npIdx >= 0 && idx == npIdx;
    }

    /** Wheel target — swap with adjacent NP instead of jumping over it. */
    static int nextMoveIndex(int from, int delta, int npIdx, int size) {
        if (size <= 0) return from;
        int to = from + delta;
        if (to < 0 || to >= size) return from;
        if (isNowPlayingSlot(to, npIdx) && (from == npIdx + 1 || from == npIdx - 1)) {
            return to;
        }
        if (isNowPlayingSlot(to, npIdx)) {
            to += delta;
            if (to < 0 || to >= size) return from;
        }
        return to;
    }

    static boolean canMoveTo(int from, int to, int npIdx) {
        if (from < 0 || to < 0 || from == to) return false;
        if (isNowPlayingSlot(from, npIdx)) return false;
        if (isNowPlayingSlot(to, npIdx)) {
            return from == npIdx + 1 || from == npIdx - 1;
        }
        return true;
    }
}
