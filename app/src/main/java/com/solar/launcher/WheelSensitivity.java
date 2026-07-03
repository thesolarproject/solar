package com.solar.launcher;

/**
 * Accelerates menu/list wheel scrolling based on how quickly the user is spinning
 * the click wheel. Slower turns move one row per detent; fast continuous spins can
 * move 2-5 rows per detent depending on the chosen sensitivity.
 *
 * This is intentionally NOT applied to volume, brightness, keyboard, scrubbing,
 * or Cover Flow, where one-detent-per-action is desired.
 */
final class WheelSensitivity {

    static final int LEVEL_NONE = 0;
    static final int LEVEL_LOW = 1;
    static final int LEVEL_MEDIUM = 2;
    static final int LEVEL_HIGH = 3;

    private static final long FAST_INTERVAL_MS = 80L;   // below this = fast spin
    private static final long PAUSE_MS = 160L;          // above this = reset
    private static final int MAX_HISTORY = 6;

    private final long[] history = new long[MAX_HISTORY];
    private int historyCount;
    private long lastEventMs;

    WheelSensitivity() {
        reset();
    }

    void reset() {
        historyCount = 0;
        lastEventMs = 0;
    }

    /** Call once per wheel event that should participate in acceleration. */
    int deltaForEvent(int level) {
        if (level == LEVEL_NONE) {
            reset();
            return 1;
        }

        long now = System.currentTimeMillis();
        if (lastEventMs != 0 && now - lastEventMs > PAUSE_MS) {
            reset();
        }
        lastEventMs = now;

        // slide window
        if (historyCount < MAX_HISTORY) {
            history[historyCount++] = now;
        } else {
            System.arraycopy(history, 1, history, 0, MAX_HISTORY - 1);
            history[MAX_HISTORY - 1] = now;
        }

        int consecutiveFast = 0;
        for (int i = historyCount - 1; i > 0; i--) {
            long gap = history[i] - history[i - 1];
            if (gap > 0 && gap < FAST_INTERVAL_MS) {
                consecutiveFast++;
            } else {
                break;
            }
        }

        int maxMultiplier = maxMultiplier(level);
        // first fast event still moves 1, then ramp up
        int multiplier = Math.min(maxMultiplier, 1 + consecutiveFast);
        return Math.max(1, multiplier);
    }

    private static int maxMultiplier(int level) {
        switch (level) {
            case LEVEL_LOW: return 2;
            case LEVEL_MEDIUM: return 3;
            case LEVEL_HIGH: return 5;
            default: return 1;
        }
    }
}
