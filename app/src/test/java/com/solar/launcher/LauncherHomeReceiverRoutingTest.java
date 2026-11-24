package com.solar.launcher;

import com.solar.home.policy.HomeTargetPolicy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 2026-07-08 — Document HomeTargetPolicy tokens that LauncherHomeReceiver must honour.
 * Layman: Stock and custom picks stay Stock/custom — not Snap-back-to-Solar.
 * Technical: mirrors receiver branches without Android Context (policy normalize only).
 * Reversal: delete; receiver covered only by device smoke.
 */
public class LauncherHomeReceiverRoutingTest {

    /** Same normalize path the receiver uses before branching. */
    private static String route(String raw) {
        return HomeTargetPolicy.normalizeTarget(raw);
    }

    @Test
    public void stockDoesNotNormalizeToSolar() {
        assertEquals(HomeTargetPolicy.TARGET_STOCK, route("stock"));
        assertEquals(HomeTargetPolicy.TARGET_CUSTOM, route("custom"));
        assertEquals(HomeTargetPolicy.TARGET_JJ, route("jj"));
        assertEquals("solar", LauncherPreference.normalizeHomeTarget("rockbox"));
    }

    @Test
    public void unknownFailsOpenToSolar() {
        assertEquals(HomeTargetPolicy.TARGET_SOLAR, route(null));
        assertEquals(HomeTargetPolicy.TARGET_SOLAR, route("innioasis"));
        assertEquals(HomeTargetPolicy.TARGET_SOLAR, route("bogus"));
    }
}
