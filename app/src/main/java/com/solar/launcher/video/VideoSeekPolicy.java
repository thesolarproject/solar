package com.solar.launcher.video;

import java.util.Locale;

/** Pure seek math shared by the video transport UI and its unit tests. */
public final class VideoSeekPolicy {

    public static final long COMPLETE_TOLERANCE_MS = 1_500L;
    public static final long SEEK_TIMEOUT_MS = 12_000L;

    private VideoSeekPolicy() {}

    public static long clampTarget(long targetMs, long durationMs) {
        if (targetMs < 0L) return 0L;
        if (durationMs > 0L && targetMs > durationMs) return durationMs;
        return targetMs;
    }

    public static long steppedTarget(long baseMs, long deltaMs, long durationMs) {
        long target;
        if (deltaMs > 0L && baseMs > Long.MAX_VALUE - deltaMs) {
            target = Long.MAX_VALUE;
        } else if (deltaMs < 0L && baseMs < Long.MIN_VALUE - deltaMs) {
            target = Long.MIN_VALUE;
        } else {
            target = baseMs + deltaMs;
        }
        return clampTarget(target, durationMs);
    }

    public static boolean isComplete(long targetMs, long actualMs) {
        if (targetMs < 0L || actualMs < 0L) return false;
        long distance = targetMs >= actualMs ? targetMs - actualMs : actualMs - targetMs;
        return distance <= COMPLETE_TOLERANCE_MS;
    }

    public static boolean hasTimedOut(long requestedAtMs, long nowMs) {
        return requestedAtMs > 0L
                && nowMs >= requestedAtMs
                && nowMs - requestedAtMs >= SEEK_TIMEOUT_MS;
    }

    public static String formatTime(long positionMs) {
        long totalSeconds = Math.max(0L, positionMs) / 1000L;
        long seconds = totalSeconds % 60L;
        long minutes = (totalSeconds / 60L) % 60L;
        long hours = totalSeconds / 3600L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
