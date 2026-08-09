package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CenterHoldPolicyTest {
    @Test
    public void completedMoveConsumesReleaseBeforeOtherActions() {
        assertEquals(CenterHoldPolicy.RELEASE_CONSUME_MOVE,
                CenterHoldPolicy.releaseAction(true, true, true, true, 900L, 420L));
        assertEquals(CenterHoldPolicy.RELEASE_CONSUME_MOVE,
                CenterHoldPolicy.releaseAction(true, false, false, false, 500L, 420L));
    }

    @Test
    public void contextAndSleepRemainOrderedForUnclaimedPresses() {
        assertEquals(CenterHoldPolicy.RELEASE_CONTEXT,
                CenterHoldPolicy.releaseAction(false, true, false, true, 500L, 420L));
        assertEquals(CenterHoldPolicy.RELEASE_SLEEP,
                CenterHoldPolicy.releaseAction(false, false, false, true, 350L, 420L));
        assertEquals(CenterHoldPolicy.RELEASE_ACTIVATE,
                CenterHoldPolicy.releaseAction(false, false, false, false, 120L, 420L));
    }
}
