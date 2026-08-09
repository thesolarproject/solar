package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StemPrepQueueProgressPolicyTest {
    @Test
    public void activeItemProgressIsWeightedAcrossQueue() {
        assertEquals(0, StemPrepQueueProgressPolicy.overallPercent(0, 4, 0));
        assertEquals(12, StemPrepQueueProgressPolicy.overallPercent(0, 4, 50));
        assertEquals(62, StemPrepQueueProgressPolicy.overallPercent(2, 4, 50));
        assertEquals(100, StemPrepQueueProgressPolicy.overallPercent(4, 4, 0));
    }

    @Test
    public void progressInputsAreClamped() {
        assertEquals(0, StemPrepQueueProgressPolicy.overallPercent(-2, 4, -10));
        assertEquals(100, StemPrepQueueProgressPolicy.overallPercent(99, 4, 150));
        assertEquals(100, StemPrepQueueProgressPolicy.overallPercent(0, 0, 0));
    }
}
