package com.solar.launcher;

import org.junit.Test;

public class WifiSleepPolicyTest {

    @Test
    public void selfCheck() {
        WifiSleepPolicy.selfCheck();
        WifiOfflinePowerPolicy.selfCheck();
    }

    @Test
    public void offlinePolicyUsesThreeMinutesAndPreservesLocalPlayback() {
        if (WifiOfflinePowerPolicy.OFFLINE_TIMEOUT_MS != 180_000L) {
            throw new AssertionError("offline Wi-Fi timeout must be three minutes");
        }
        if (WifiOfflinePowerPolicy.networkPlaybackNeedsWifi(false)) {
            throw new AssertionError("local playback must not need Wi-Fi");
        }
        if (!WifiOfflinePowerPolicy.networkPlaybackNeedsWifi(true)) {
            throw new AssertionError("network playback must need Wi-Fi");
        }
    }
}
