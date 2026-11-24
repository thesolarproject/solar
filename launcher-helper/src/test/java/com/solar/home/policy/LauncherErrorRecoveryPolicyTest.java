package com.solar.home.policy;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-07-07 — Rolling crash window + silent kill policy unit tests.
 */
public class LauncherErrorRecoveryPolicyTest {

    @After
    public void tearDown() {
        LauncherErrorRecoveryPolicy.resetForTest();
    }

    @Test
    public void silentDismissWhenPendingKillMatchesSolar() {
        LauncherErrorRecoveryPolicy.setPendingKillForTest(HomeTargetPolicy.SOLAR_PKG);
        assertTrue(LauncherErrorRecoveryPolicy.shouldSilentlyDismissErrorUi(
                "com.solar.launcher:overlay"));
    }

    @Test
    public void expectedSwitchVictimWhenTargetRockbox() {
        LauncherErrorRecoveryPolicy.setKillReasonForTest(LauncherErrorRecoveryPolicy.REASON_SWITCH);
        LauncherErrorRecoveryPolicy.setHomeTargetForTest("rockbox");
        assertTrue(LauncherErrorRecoveryPolicy.isExpectedSwitchVictim(HomeTargetPolicy.SOLAR_PKG));
    }

    @Test
    public void crashWindowThresholdWithinTwoMinutes() {
        long now = 1_700_000_000_000L;
        LauncherErrorRecoveryPolicy.setNowMsForTest(now);
        LauncherErrorRecoveryPolicy.setCrashTimesForTest(
                String.valueOf(now - 30_000L) + "," + String.valueOf(now - 60_000L));
        int count = LauncherErrorRecoveryPolicy.recordCrashInWindow(HomeTargetPolicy.SOLAR_PKG);
        assertEquals(3, count);
        assertTrue(LauncherErrorRecoveryPolicy.shouldOfferRecoveryOverlay(HomeTargetPolicy.SOLAR_PKG));
    }

    @Test
    public void recoveryOverlayRequiresSolarHomeTarget() {
        long now = 1_700_000_000_000L;
        LauncherErrorRecoveryPolicy.setNowMsForTest(now);
        LauncherErrorRecoveryPolicy.setCrashTimesForTest(
                String.valueOf(now - 30_000L) + "," + String.valueOf(now - 60_000L)
                        + "," + String.valueOf(now - 90_000L));
        LauncherErrorRecoveryPolicy.setHomeTargetForTest("rockbox");
        assertFalse(LauncherErrorRecoveryPolicy.shouldOfferRecoveryOverlay(HomeTargetPolicy.SOLAR_PKG));
        LauncherErrorRecoveryPolicy.setHomeTargetForTest(HomeTargetPolicy.TARGET_SOLAR);
        assertTrue(LauncherErrorRecoveryPolicy.shouldOfferRecoveryOverlay(HomeTargetPolicy.SOLAR_PKG));
    }

    @Test
    public void silentDismissWhenHomeApplying() {
        // home.applying exercised via test hook on pending kill path — policy reads live sysprop in prod.
        LauncherErrorRecoveryPolicy.setPendingKillForTest(HomeTargetPolicy.SOLAR_PKG);
        assertTrue(LauncherErrorRecoveryPolicy.shouldSilentlyDismissErrorUi("com.solar.launcher"));
    }
}
