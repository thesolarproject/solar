package com.solar.launcher.stem;

/** Pure progress math for the shared background stem preparation queue. */
public final class StemPrepQueueProgressPolicy {
    private StemPrepQueueProgressPolicy() {}

    /**
     * Combine completed items and the active item's percent into one queue percent.
     * The active item is not counted as complete until its publish is verified.
     */
    public static int overallPercent(int completed, int total, int activePercent) {
        if (total <= 0) return 100;
        int done = Math.max(0, Math.min(completed, total));
        int active = Math.max(0, Math.min(activePercent, 100));
        return Math.max(0, Math.min(100, (done * 100 + active) / total));
    }
}
