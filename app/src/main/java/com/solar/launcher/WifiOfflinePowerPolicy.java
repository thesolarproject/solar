package com.solar.launcher;

/**
 * Pure policy for powering down an unused Wi-Fi radio on Solar hardware.
 * Phone Chrome and emulator callers are excluded by the caller's device gate.
 */
public final class WifiOfflinePowerPolicy {

    /** Wi-Fi may remain enabled without an association for this long. */
    public static final long OFFLINE_TIMEOUT_MS = 3L * 60L * 1000L;
    /** Retry while the user is actively connecting or using the Wi-Fi UI. */
    public static final long RETRY_DELAY_MS = 30L * 1000L;

    private WifiOfflinePowerPolicy() {}

    /** True when a currently playing source needs Wi-Fi to continue. */
    public static boolean networkPlaybackNeedsWifi(boolean sourceIsNetwork) {
        return sourceIsNetwork;
    }

    /** True when the radio is eligible to be disabled right now. */
    public static boolean shouldPowerOff(boolean excluded, boolean wifiEnabled,
            boolean wifiConnected, boolean connectionInProgress, boolean wifiUiActive,
            boolean activeNetworkUse) {
        return !excluded && wifiEnabled && !wifiConnected
                && !connectionInProgress && !wifiUiActive && !activeNetworkUse;
    }

    /** Remaining delay from an elapsed-realtime offline anchor. */
    public static long remainingDelayMs(long offlineSinceMs, long nowMs) {
        if (offlineSinceMs <= 0L) return OFFLINE_TIMEOUT_MS;
        long elapsed = Math.max(0L, nowMs - offlineSinceMs);
        return Math.max(0L, OFFLINE_TIMEOUT_MS - elapsed);
    }

    public static void selfCheck() {
        if (!shouldPowerOff(false, true, false, false, false, false)) {
            throw new AssertionError("offline hardware should power off");
        }
        if (shouldPowerOff(true, true, false, false, false, false)) {
            throw new AssertionError("excluded device");
        }
        if (shouldPowerOff(false, true, true, false, false, false)) {
            throw new AssertionError("connected network");
        }
        if (shouldPowerOff(false, true, false, true, false, false)) {
            throw new AssertionError("connection in progress");
        }
        if (shouldPowerOff(false, true, false, false, true, false)) {
            throw new AssertionError("wifi UI active");
        }
        if (shouldPowerOff(false, true, false, false, false, true)) {
            throw new AssertionError("active network use");
        }
        if (remainingDelayMs(0L, 100L) != OFFLINE_TIMEOUT_MS) {
            throw new AssertionError("initial delay");
        }
        if (remainingDelayMs(1_000L, 181_000L) != 0L) {
            throw new AssertionError("expired delay");
        }
        if (networkPlaybackNeedsWifi(false)) {
            throw new AssertionError("local playback must not need Wi-Fi");
        }
        if (!networkPlaybackNeedsWifi(true)) {
            throw new AssertionError("network playback needs Wi-Fi");
        }
    }
}
