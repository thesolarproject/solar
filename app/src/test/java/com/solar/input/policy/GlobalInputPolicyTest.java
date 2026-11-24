package com.solar.input.policy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 2026-07-06 — Policy JAR unit tests per modular-resilience-contract §3. */
public class GlobalInputPolicyTest {

    @Test
    public void powerTapPassthroughUnder500ms() {
        // 2026-07-18 — Tap window is POWER_TAP_MAX_MS (280); modal hold is 350.
        assertTrue(GlobalInputPolicy.shouldPassthroughPowerTap(100L));
        assertTrue(GlobalInputPolicy.shouldPassthroughPowerTap(
                GlobalInputPolicy.POWER_TAP_MAX_MS - 1L));
        assertFalse(GlobalInputPolicy.shouldPassthroughPowerTap(
                GlobalInputPolicy.POWER_TAP_MAX_MS));
        assertFalse(GlobalInputPolicy.shouldPassthroughPowerTap(350L));
    }

    /**
     * 2026-07-15 — Force-sleep uses screen-on at POWER DOWN (UP-time is always lit after wake).
     * Layman: dark press = wake (no force sleep); lit press = sleep.
     */
    @Test
    public void forcePowerTapSleepRequiresScreenOnAtDown() {
        assertTrue(GlobalInputPolicy.shouldForcePowerTapSleep(100L, true));
        assertTrue(GlobalInputPolicy.shouldForcePowerTapSleep(
                GlobalInputPolicy.POWER_TAP_MAX_MS - 1L, true));
        assertFalse(GlobalInputPolicy.shouldForcePowerTapSleep(100L, false));
        assertFalse(GlobalInputPolicy.shouldForcePowerTapSleep(
                GlobalInputPolicy.POWER_TAP_MAX_MS, true));
        assertFalse(GlobalInputPolicy.shouldForcePowerTapSleep(350L, true));
    }

    @Test
    public void y2PowerModalIncludesSolarHome() {
        assertTrue(GlobalInputPolicy.shouldOfferPowerLongModal(
                GlobalInputPolicy.SOLAR_PKG, true));
    }

    /** 2026-07-14 — Outside Solar, POWER hold must not open Solar menu (stock GlobalActions). */
    @Test
    public void y2PowerModalSolarOnlyExcludesRockboxAndThirdParty() {
        assertFalse(GlobalInputPolicy.shouldOfferPowerLongModal(
                "org.rockbox", true));
        assertFalse(GlobalInputPolicy.shouldOfferPowerLongModal(
                "com.android.settings", true));
        assertFalse(GlobalInputPolicy.shouldOfferPowerLongModal(
                GlobalInputPolicy.JJ_PKG, true));
        assertFalse(GlobalInputPolicy.shouldOfferPowerLongModal(
                "com.android.systemui", true));
        assertFalse(GlobalInputPolicy.shouldOfferPowerLongModal(null, true));
    }

    @Test
    public void y2PowerFailOpenSystemuiProbeStillRecognized() {
        assertTrue(GlobalInputPolicy.shouldFailOpenPowerFg("com.android.systemui"));
        assertTrue(GlobalInputPolicy.shouldFailOpenPowerFg(null));
    }

    @Test
    public void y2BackFailOpenSystemuiLaunchesSolar() {
        assertTrue(GlobalInputPolicy.shouldFailOpenBackFg("com.android.systemui"));
        assertTrue(GlobalInputPolicy.shouldLaunchSolarOnBackLong(
                "com.android.systemui", false, false));
        assertTrue(GlobalInputPolicy.shouldOfferBackLongModal(
                "com.android.systemui", true, false, false));
    }

    @Test
    public void y1RockboxBackPassthroughNormalMode() {
        // 2026-07-08 — Pass through under 300 ms nav-owned tier; at/over opens power path only.
        assertTrue(GlobalInputPolicy.shouldPassthroughNavOwnedLauncherBack(
                299L, false, false, false));
        assertFalse(GlobalInputPolicy.shouldPassthroughNavOwnedLauncherBack(
                300L, false, false, false));
    }

    @Test
    public void timingTiersMatchDocumentedHoldGuard() {
        // 2026-07-20 — 280/300 modal tiers; tap max 220.
        assertEquals(300L, GlobalInputPolicy.THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS);
        assertEquals(300L, GlobalInputPolicy.NAV_OWNED_LAUNCHER_MODAL_HOLD_MS);
        assertEquals(300L, GlobalInputPolicy.THIRD_PARTY_LAUNCHER_MODAL_HOLD_MS);
        assertEquals(280L, GlobalInputPolicy.GLOBAL_MODAL_HOLD_MS);
        assertEquals(280L, GlobalInputPolicy.THIRD_PARTY_MODAL_HOLD_MS);
        assertEquals(280L, GlobalInputPolicy.MODAL_HOLD_MS);
        assertEquals(280L, GlobalInputPolicy.CENTER_MENU_HOLD_MS);
        assertEquals(280L, GlobalInputPolicy.SOLAR_BACK_CONTEXT_HOLD_MS);
        assertEquals(220L, GlobalInputPolicy.POWER_TAP_MAX_MS);
        assertEquals(7000L, GlobalInputPolicy.HUD_COUNTDOWN_START_MS);
        assertEquals(10000L, GlobalInputPolicy.RESCUE_EXECUTE_MS);
        assertEquals(10000L, GlobalInputPolicy.RESCUE_HOLD_MS);
        assertEquals(27, GlobalInputPolicy.POLICY_REV);
    }

    @Test
    public void thirdPartyBackModalAtFourHundredTwentyMs() {
        // 2026-07-20 — name kept; values are GLOBAL_MODAL_HOLD / half grace.
        assertEquals(280L, GlobalInputPolicy.backModalHoldMsForPackage("com.android.settings"));
        assertEquals(280L, GlobalInputPolicy.powerModalHoldMsForPackage("com.mediatek.camera"));
        assertEquals(140L, GlobalInputPolicy.overlayDismissGraceMsForPackage("com.android.settings"));
        assertEquals(280L, GlobalInputPolicy.backModalHoldMsForPackage("com.android.systemui"));
    }

    /** 2026-07-08 — JJ/stock HOME targets arm the wheel remap even with Solar disabled. */
    @Test
    public void jjKeylayoutHomeTargetsArmRemapFallback() {
        // JJ and factory Innioasis HOME need MEDIA→DPAD wheel translation.
        assertTrue(GlobalInputPolicy.isJjKeylayoutHomeTarget("jj"));
        assertTrue(GlobalInputPolicy.isJjKeylayoutHomeTarget("stock"));
        // Solar/Rockbox/custom handle the wheel natively (or via other tiers) — no remap.
        assertFalse(GlobalInputPolicy.isJjKeylayoutHomeTarget("solar"));
        assertFalse(GlobalInputPolicy.isJjKeylayoutHomeTarget("rockbox"));
        assertFalse(GlobalInputPolicy.isJjKeylayoutHomeTarget("custom"));
        assertFalse(GlobalInputPolicy.isJjKeylayoutHomeTarget(""));
        assertFalse(GlobalInputPolicy.isJjKeylayoutHomeTarget(null));
    }

    @Test
    public void navOwnedLauncherHoldThreeHundredMs() {
        assertEquals(GlobalInputPolicy.GLOBAL_MODAL_HOLD_MS, GlobalInputPolicy.backModalHoldMsForPackage("org.rockbox"));
        assertEquals(300L, GlobalInputPolicy.backModalHoldMsForPackage(GlobalInputPolicy.JJ_PKG));
        assertEquals(GlobalInputPolicy.GLOBAL_MODAL_HOLD_MS, GlobalInputPolicy.powerModalHoldMsForPackage("org.rockbox"));
        assertEquals(300L, GlobalInputPolicy.powerModalHoldMsForPackage(GlobalInputPolicy.JJ_PKG));
        assertEquals(300L, GlobalInputPolicy.overlayDismissGraceMsForPackage(GlobalInputPolicy.JJ_PKG));
        assertEquals(GlobalInputPolicy.GLOBAL_MODAL_HOLD_MS / 2L, GlobalInputPolicy.overlayDismissGraceMsForPackage("org.rockbox"));
    }

    @Test
    public void y2RockboxBackPassthroughUntilThreeHundredMs() {
        assertTrue(GlobalInputPolicy.shouldPassthroughNavOwnedLauncherBack(
                299L, true, false, false));
        assertFalse(GlobalInputPolicy.shouldPassthroughNavOwnedLauncherBack(
                300L, true, false, false));
    }

    @Test
    public void rockboxPowerPassthroughUsesNavOwnedTier() {
        assertTrue(GlobalInputPolicy.shouldPassthroughNavOwnedLauncherPower(
                299L, "org.rockbox", false));
        assertFalse(GlobalInputPolicy.shouldPassthroughNavOwnedLauncherPower(
                300L, "org.rockbox", false));
    }

    @Test
    public void jjPowerPassthroughUsesNavOwnedTier() {
        assertTrue(GlobalInputPolicy.shouldPassthroughNavOwnedLauncherPower(
                299L, GlobalInputPolicy.JJ_PKG, false));
        assertFalse(GlobalInputPolicy.shouldPassthroughNavOwnedLauncherPower(
                300L, GlobalInputPolicy.JJ_PKG, false));
    }

    @Test
    public void navOwnedLauncherGetsBackLongReturnToSolar() {
        assertFalse(GlobalInputPolicy.shouldLaunchSolarOnBackLong(
                "org.rockbox", false, false));
        assertTrue(GlobalInputPolicy.shouldLaunchSolarOnBackLong(
                GlobalInputPolicy.JJ_PKG, false, false));
        assertTrue(GlobalInputPolicy.shouldOfferBackLongModal(
                GlobalInputPolicy.INNIOASIS_Y1_PKG, false, false, false));
        assertTrue(GlobalInputPolicy.shouldOfferBackLongModal(
                GlobalInputPolicy.INNIOASIS_Y2_PKG, true, false, false));
    }

    @Test
    public void launcherPickerAliasesBackLongEligibility() {
        assertTrue(GlobalInputPolicy.shouldOfferLauncherPickerOnBackHold(
                "com.android.settings", false, false, false));
        assertTrue(GlobalInputPolicy.shouldOfferLauncherPickerOnBackHold(
                GlobalInputPolicy.JJ_PKG, false, false, false));
    }

    @Test
    public void imeActiveAllowsBackReturnToSolarOverRockbox() {
        assertTrue(GlobalInputPolicy.shouldLaunchSolarOnBackLong(
                "org.rockbox", true, false));
        assertTrue(GlobalInputPolicy.shouldOfferBackLongModal(
                "org.rockbox", false, true, false));
    }

    @Test
    public void launcherPickerPowerHoldMatchesPowerModal() {
        assertTrue(GlobalInputPolicy.shouldOfferLauncherPickerOnPowerHold(
                GlobalInputPolicy.SOLAR_PKG, true));
        assertFalse(GlobalInputPolicy.shouldOfferLauncherPickerOnPowerHold(
                GlobalInputPolicy.SOLAR_PKG, false));
        assertFalse(GlobalInputPolicy.shouldOfferLauncherPickerOnPowerHold(
                "com.android.settings", true));
    }

    @Test
    public void rescueHoldArmsForNullForegroundAtBoot() {
        assertTrue(GlobalInputPolicy.shouldArmRescueHoldForPackage(null, 0));
        assertTrue(GlobalInputPolicy.shouldArmRescueHoldForPackage("", GlobalInputPolicy.KEYCODE_BACK));
        assertTrue(GlobalInputPolicy.shouldArmRescueHoldForPackage("android", GlobalInputPolicy.KEYCODE_BACK));
        assertFalse(GlobalInputPolicy.shouldArmRescueHoldForPackage(
                GlobalInputPolicy.SOLAR_PKG, GlobalInputPolicy.KEYCODE_BACK));
        assertTrue(GlobalInputPolicy.shouldArmRescueHoldForPackage(
                GlobalInputPolicy.SOLAR_PKG, GlobalInputPolicy.KEYCODE_POWER));
    }

    @Test
    public void genericHomeLauncherUsesStandardModalHold() {
        // 2026-07-20 — Generic HOME uses GLOBAL_MODAL_HOLD like stock apps (not nav-owned 300).
        assertEquals(280L, GlobalInputPolicy.backModalHoldMsForPackage("com.example.home"));
        assertFalse(GlobalInputPolicy.isThirdPartyHomeLauncher("com.example.home"));
        assertFalse(GlobalInputPolicy.isNavOwnedHomeLauncher("com.example.home"));
    }

    @Test
    public void thirdPartyHomeLauncherDetection() {
        assertTrue(GlobalInputPolicy.isThirdPartyHomeLauncher(GlobalInputPolicy.JJ_PKG));
        assertTrue(GlobalInputPolicy.isJjKeylayoutLauncher(GlobalInputPolicy.INNIOASIS_Y1_PKG));
        assertTrue(GlobalInputPolicy.isJjKeylayoutLauncher(GlobalInputPolicy.INNIOASIS_Y2_PKG));
        assertFalse(GlobalInputPolicy.isInnioasisStockLauncher("com.innioasis.music"));
        assertTrue(GlobalInputPolicy.isInnioasisNonLauncherPackage("com.innioasis.music"));
        assertFalse(GlobalInputPolicy.shouldOfferBackLongModal(
                "com.innioasis.music", false, false, false));
        assertFalse(GlobalInputPolicy.isThirdPartyHomeLauncher("org.rockbox"));
        assertFalse(GlobalInputPolicy.isNavOwnedHomeLauncher("org.rockbox"));
        assertTrue(GlobalInputPolicy.isNavOwnedHomeLauncher(GlobalInputPolicy.INNIOASIS_Y1_PKG));
    }
}
