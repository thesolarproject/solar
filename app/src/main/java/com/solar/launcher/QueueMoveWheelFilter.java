package com.solar.launcher;

/** Drops rapid wheel repeats during queue move — accept 2 of every 5 in a burst. */
final class QueueMoveWheelFilter {
    static final int BURST_GAP_MS = 130;
    static final int GROUP_SIZE = 5;
    static final int ACCEPT_PER_GROUP = 2;

    private int groupCount;
    private long lastEventMs;

    boolean accept() {
        long now = System.currentTimeMillis();
        if (lastEventMs == 0 || now - lastEventMs > BURST_GAP_MS) {
            groupCount = 0;
        }
        lastEventMs = now;
        int pos = groupCount % GROUP_SIZE;
        groupCount++;
        return pos < ACCEPT_PER_GROUP;
    }

    void reset() {
        groupCount = 0;
        lastEventMs = 0;
    }
}
