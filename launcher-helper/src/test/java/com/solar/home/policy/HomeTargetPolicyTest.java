package com.solar.home.policy;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Unit tests for shared HOME policy constants — no device required. */
public class HomeTargetPolicyTest {

    @Test
    public void normalizeTargetDefaultsUnknownToSolar() {
        assertEquals(HomeTargetPolicy.TARGET_SOLAR, HomeTargetPolicy.normalizeTarget(null));
        assertEquals(HomeTargetPolicy.TARGET_SOLAR, HomeTargetPolicy.normalizeTarget("bogus"));
        assertEquals(HomeTargetPolicy.TARGET_SOLAR,
                HomeTargetPolicy.normalizeTarget("rockbox"));
        assertEquals(HomeTargetPolicy.TARGET_JJ,
                HomeTargetPolicy.normalizeTarget(HomeTargetPolicy.TARGET_JJ));
        assertEquals(HomeTargetPolicy.TARGET_STOCK,
                HomeTargetPolicy.normalizeTarget(HomeTargetPolicy.TARGET_STOCK));
        assertEquals(HomeTargetPolicy.TARGET_CUSTOM,
                HomeTargetPolicy.normalizeTarget(HomeTargetPolicy.TARGET_CUSTOM));
    }

    @Test
    public void parseComponentRoundTrip() {
        String flat = HomeTargetPolicy.flattenComponent("org.rockbox", "org.rockbox.RockboxActivity");
        assertEquals("org.rockbox/org.rockbox.RockboxActivity", flat);
        String[] parts = HomeTargetPolicy.parseComponent(flat);
        assertArrayEquals(new String[] { "org.rockbox", "org.rockbox.RockboxActivity" }, parts);
    }

    @Test
    public void isAlternateHomeTargetIncludesStock() {
        assertFalse(HomeTargetPolicy.isAlternateHomeTarget("rockbox"));
        assertTrue(HomeTargetPolicy.isAlternateHomeTarget(HomeTargetPolicy.TARGET_JJ));
        assertTrue(HomeTargetPolicy.isAlternateHomeTarget(HomeTargetPolicy.TARGET_STOCK));
        assertFalse(HomeTargetPolicy.isAlternateHomeTarget(HomeTargetPolicy.TARGET_SOLAR));
    }

    @Test
    public void resolveLaunchComponentRoutesKnownTargets() {
        String[] solar = HomeTargetPolicy.resolveLaunchComponent("solar", "");
        assertArrayEquals(new String[] {
                HomeTargetPolicy.SOLAR_PKG, HomeTargetPolicy.SOLAR_ACTIVITY }, solar);
        String[] rockbox = HomeTargetPolicy.resolveLaunchComponent("rockbox", "");
        assertArrayEquals(new String[] {
                HomeTargetPolicy.SOLAR_PKG, HomeTargetPolicy.SOLAR_ACTIVITY }, rockbox);
        String[] jj = HomeTargetPolicy.resolveLaunchComponent("jj", "");
        assertArrayEquals(new String[] {
                HomeTargetPolicy.JJ_PKG, HomeTargetPolicy.JJ_ACTIVITY }, jj);
        String[] stock = HomeTargetPolicy.resolveLaunchComponent("stock", "");
        assertArrayEquals(new String[] {
                HomeTargetPolicy.INNIOASIS_Y1_PKG, HomeTargetPolicy.INNIOASIS_Y1_ACTIVITY }, stock);
        String[] stockY2 = HomeTargetPolicy.resolveLaunchComponent("stock",
                "com.innioasis.y2/com.innioasis.y2.MainActivity");
        assertArrayEquals(new String[] {
                HomeTargetPolicy.INNIOASIS_Y2_PKG, HomeTargetPolicy.INNIOASIS_Y2_ACTIVITY }, stockY2);
        // 2026-07-16 — Family-aware stock fallback without PROP flat.
        String[] stockY2Default = HomeTargetPolicy.resolveLaunchComponent("stock", "", true);
        assertArrayEquals(new String[] {
                HomeTargetPolicy.INNIOASIS_Y2_PKG, HomeTargetPolicy.INNIOASIS_Y2_ACTIVITY },
                stockY2Default);
    }

    @Test
    public void stockPackageForDeviceBranches() {
        assertEquals(HomeTargetPolicy.INNIOASIS_Y1_PKG,
                HomeTargetPolicy.stockPackageForDevice(false));
        assertEquals(HomeTargetPolicy.INNIOASIS_Y2_PKG,
                HomeTargetPolicy.stockPackageForDevice(true));
    }

    @Test
    public void isInnioasisStockPackage() {
        assertTrue(HomeTargetPolicy.isInnioasisStockPackage("com.innioasis.y1"));
        assertTrue(HomeTargetPolicy.isInnioasisStockPackage("com.innioasis.y2"));
        assertFalse(HomeTargetPolicy.isInnioasisStockPackage("com.innioasis.music"));
    }

    @Test
    public void competitionTargetForStockPackage() {
        assertEquals(HomeTargetPolicy.TARGET_STOCK,
                LauncherCompetitionPolicy.targetForPackage("com.innioasis.y1"));
        assertEquals(HomeTargetPolicy.TARGET_STOCK,
                LauncherCompetitionPolicy.targetForPackage("com.innioasis.y2"));
    }

    @Test
    public void flattenLaunchComponentMatchesResolve() {
        String flat = HomeTargetPolicy.flattenLaunchComponent("rockbox", "");
        assertEquals(HomeTargetPolicy.SOLAR_PKG + "/" + HomeTargetPolicy.SOLAR_ACTIVITY, flat);
    }

    @Test
    public void launcherPickerActionConstant() {
        assertEquals("com.solar.launcher.homehelper.action.SHOW_LAUNCHER_PICKER",
                HomeTargetPolicy.ACTION_SHOW_LAUNCHER_PICKER);
    }

    @Test
    public void helperHomeActivityConstantStable() {
        assertEquals("com.solar.launcher.homehelper.LauncherHomeActivity",
                HomeTargetPolicy.HELPER_HOME_ACTIVITY);
    }
}
