package com.solar.launcher;

/** When turning Wi-Fi off needs a confirmation (streaming / sharing / transfers). */
public final class WifiOffPolicy {
    private WifiOffPolicy() {}

    public static boolean disableNeedsWarning(boolean wifiOn, boolean soulseekSharing,
            boolean reachTransferActive, boolean reachStreamingBuffering,
            boolean podcastStreamingBuffering) {
        if (!wifiOn) return false;
        return soulseekSharing || reachTransferActive
                || reachStreamingBuffering || podcastStreamingBuffering;
    }

    public static void selfCheck() {
        if (disableNeedsWarning(false, true, true, true, true)) {
            throw new AssertionError("wifi off");
        }
        if (disableNeedsWarning(true, false, false, false, false)) {
            throw new AssertionError("local ok");
        }
        if (!disableNeedsWarning(true, true, false, false, false)) {
            throw new AssertionError("sharing");
        }
        if (!disableNeedsWarning(true, false, true, false, false)) {
            throw new AssertionError("reach xfer");
        }
        if (!disableNeedsWarning(true, false, false, true, false)) {
            throw new AssertionError("reach stream");
        }
        if (!disableNeedsWarning(true, false, false, false, true)) {
            throw new AssertionError("podcast stream");
        }
    }
}
