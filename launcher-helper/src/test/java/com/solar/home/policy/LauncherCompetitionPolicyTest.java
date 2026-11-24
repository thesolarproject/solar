package com.solar.home.policy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 2026-07-06 — HOME competition disable matrix per target. */
public class LauncherCompetitionPolicyTest {

    @Test
    public void packageForTarget_mapsKnownIds() {
        assertEquals(HomeTargetPolicy.SOLAR_PKG,
                LauncherCompetitionPolicy.packageForTarget(HomeTargetPolicy.TARGET_SOLAR));
        assertEquals(HomeTargetPolicy.SOLAR_PKG,
                LauncherCompetitionPolicy.packageForTarget("rockbox"));
        assertEquals(HomeTargetPolicy.JJ_PKG,
                LauncherCompetitionPolicy.packageForTarget(HomeTargetPolicy.TARGET_JJ));
    }

    /**
     * 2026-07-08 — Disable matrix now includes stock Innioasis HOME packages (y1 + y2).
     * Layman: picking a home screen parks all the other home apps, including the factory one.
     * Reversal: expect 2/1 lengths again if Innioasis packages leave the competition lists.
     */
    @Test
    public void packagesToDisableForTarget_neverIncludesActive() {
        String[] solarOff = LauncherCompetitionPolicy.packagesToDisableForTarget(
                HomeTargetPolicy.TARGET_SOLAR);
        // Solar active — Rockbox, JJ, and both factory Innioasis packages get parked.
        assertEquals(4, solarOff.length);
        String[] rockboxOff = LauncherCompetitionPolicy.packagesToDisableForTarget(
                "rockbox");
        // Rockbox active — JJ + factory packages parked; Rockbox itself never in its own list.
        assertEquals(3, rockboxOff.length);
        assertEquals(HomeTargetPolicy.JJ_PKG, rockboxOff[0]);
        for (String pkg : rockboxOff) {
            assertFalse("org.rockbox".equals(pkg));
        }
        // Stock active — factory package stays enabled; Rockbox + JJ parked.
        String[] stockOff = LauncherCompetitionPolicy.packagesToDisableForTarget(
                HomeTargetPolicy.TARGET_STOCK);
        for (String pkg : stockOff) {
            assertFalse(HomeTargetPolicy.INNIOASIS_Y1_PKG.equals(pkg));
        }
    }

    @Test
    public void isKnownLauncherPackage_stripsProcessSuffix() {
        assertTrue(LauncherCompetitionPolicy.isKnownLauncherPackage("com.solar.launcher:overlay"));
        assertFalse(LauncherCompetitionPolicy.isKnownLauncherPackage("com.android.systemui"));
    }

    @Test
    public void targetForPackage_roundTrips() {
        assertNull(LauncherCompetitionPolicy.targetForPackage("org.rockbox"));
    }
}
