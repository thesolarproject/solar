package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

public class WheelPhysicsTest {
    @Test public void isolatedTicksMoveOneRow() {
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        p.tick(1_000_000_000L, 1, r);
        assertEquals(1, r.rowSteps);
        p.tick(1_500_000_000L, 1, r);
        assertEquals(1, r.rowSteps);
    }

    @Test public void burstAcceleratesAndCapsBeforeSectionMode() {
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        long t = 1_000_000_000L;
        for (int i = 0; i < 4; i++) p.tick(t += 40_000_000L, 1, r);
        assertTrue(r.rowSteps >= 2 && r.rowSteps <= 4);
        while (!r.sectionJump) p.tick(t += 40_000_000L, 1, r);
        assertEquals(0, r.rowSteps);
    }

    @Test public void pauseAndDirectionChangeResetMomentum() {
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        long t = 1_000_000_000L;
        for (int i = 0; i < 4; i++) p.tick(t += 30_000_000L, 1, r);
        p.tick(t += WheelPhysics.RESET_NANOS, 1, r);
        assertEquals(1, r.rowSteps);
        p.tick(t += 30_000_000L, -1, r);
        assertEquals(1, r.rowSteps);
    }
}
