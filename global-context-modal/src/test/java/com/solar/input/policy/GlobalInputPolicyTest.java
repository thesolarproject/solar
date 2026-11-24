package com.solar.input.policy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 2026-07-05 — Companion module policy unit tests (shared JAR). */
public class GlobalInputPolicyTest {

    @Test
    public void powerTapPassthroughUnder500ms() {
        assertTrue(GlobalInputPolicy.shouldPassthroughPowerTap(100L));
        assertFalse(GlobalInputPolicy.shouldPassthroughPowerTap(300L));
    }

    @Test
    public void solarHomeOffersPowerLongOnY2() {
        assertTrue(GlobalInputPolicy.shouldOfferPowerLongModal(
                GlobalInputPolicy.SOLAR_PKG, true));
    }

    @Test
    public void rockboxAndJjUseShorterModalTier() {
        assertEquals(300L, GlobalInputPolicy.powerModalHoldMsForPackage(
                "com.example.app"));
        assertEquals(300L, GlobalInputPolicy.powerModalHoldMsForPackage(
                GlobalInputPolicy.JJ_PKG));
        assertEquals(280L, GlobalInputPolicy.powerModalHoldMsForPackage(
                "com.android.settings"));
    }

    @Test
    public void systemUiFailOpenDoesNotOfferPowerModalSolarOnly() {
        assertTrue(GlobalInputPolicy.shouldFailOpenPowerFg("com.android.systemui"));
        assertFalse(GlobalInputPolicy.shouldOfferPowerLongModal(
                "com.android.systemui", true));
        assertFalse(GlobalInputPolicy.shouldOfferPowerLongModal(null, true));
        assertTrue(GlobalInputPolicy.shouldOfferPowerLongModal(
                GlobalInputPolicy.SOLAR_PKG, true));
    }

    @Test
    public void rockboxBackLongBlockedSettingsReturnsToSolar() {
        assertFalse(GlobalInputPolicy.shouldLaunchSolarOnBackLong(
                "com.example.app", false, false));
        assertTrue(GlobalInputPolicy.shouldLaunchSolarOnBackLong(
                "com.android.settings", false, false));
        assertTrue(GlobalInputPolicy.shouldOfferBackLongModal(
                GlobalInputPolicy.JJ_PKG, true, false, false));
    }
}
