package com.solar.launcher.flow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FlowGuidedScrollBudgetTest {

    @Test
    public void oneAlbumUsesWheelStep() {
        assertEquals(1, FlowGuidedScrollBudget.stepDelta(1, FlowGuidedScrollBudget.MAX_MS));
        assertEquals(1, FlowGuidedScrollBudget.stepDelta(3, 2000L));
    }

    @Test
    public void largeDistanceWidensStepsToFitBudget() {
        int delta = FlowGuidedScrollBudget.stepDelta(20, FlowGuidedScrollBudget.MAX_MS);
        assertEquals(4, delta);
    }

    @Test
    public void finalAlbumsAlwaysWheelScroll() {
        assertEquals(1, FlowGuidedScrollBudget.stepDelta(1, 0L));
        assertEquals(1, FlowGuidedScrollBudget.stepDelta(2, 0L));
    }

    @Test
    public void handoffZipJumpsToNeighborThenOneScroll() {
        assertEquals(1, FlowGuidedScrollBudget.handoffStepDelta(1));
        assertEquals(4, FlowGuidedScrollBudget.handoffStepDelta(5));
        assertEquals(14, FlowGuidedScrollBudget.handoffStepDelta(15));
    }

    @Test
    public void expiredBudgetJumpsRemainingWhenFar() {
        assertEquals(15, FlowGuidedScrollBudget.stepDelta(15, 0L));
    }
}
