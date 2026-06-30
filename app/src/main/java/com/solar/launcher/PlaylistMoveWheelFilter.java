package com.solar.launcher;

/** Sustained playlist move wheel — ramps to 2× over 10s in 2s steps while scrolling. */
final class PlaylistMoveWheelFilter {
    private static final long RAMP_MS = 10_000L;
    private static final long STEP_MS = 2_000L;

    private final QueueMoveWheelFilter burst = new QueueMoveWheelFilter();
    private long burstStartMs;
    private long lastEventMs;

    /**
     * @return stride 1 or 2 for this wheel tick; 0 when the burst filter rejects the tick.
     */
    int acceptStride(int delta) {
        if (delta == 0) return 0;
        if (!burst.accept()) return 0;
        long now = System.currentTimeMillis();
        if (lastEventMs == 0 || now - lastEventMs > QueueMoveWheelFilter.BURST_GAP_MS) {
            burstStartMs = now;
        }
        lastEventMs = now;
        return strideForElapsed(now - burstStartMs);
    }

    /** Multiplier ramp: 1.0 → 1.25 → 1.5 → 1.75 → 2.0 every 2s, held at 2× after 10s. */
    static float speedMultiplier(long continuousMs) {
        if (continuousMs <= 0) return 1f;
        if (continuousMs >= RAMP_MS) return 2f;
        int step = (int) (continuousMs / STEP_MS);
        return 1f + step * 0.25f;
    }

    static int strideForElapsed(long continuousMs) {
        float mult = speedMultiplier(continuousMs);
        if (mult >= 1.75f) return 2;
        return 1;
    }

    void reset() {
        burst.reset();
        burstStartMs = 0;
        lastEventMs = 0;
    }
}
