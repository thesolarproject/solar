package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaylistMoveWheelFilterTest {

    @Test
    public void speedMultiplier_stepsEveryTwoSecondsToTen() {
        assertEquals(1f, PlaylistMoveWheelFilter.speedMultiplier(0), 0.001f);
        assertEquals(1f, PlaylistMoveWheelFilter.speedMultiplier(1999), 0.001f);
        assertEquals(1.25f, PlaylistMoveWheelFilter.speedMultiplier(2000), 0.001f);
        assertEquals(1.5f, PlaylistMoveWheelFilter.speedMultiplier(5000), 0.001f);
        assertEquals(1.75f, PlaylistMoveWheelFilter.speedMultiplier(7500), 0.001f);
        assertEquals(2f, PlaylistMoveWheelFilter.speedMultiplier(10000), 0.001f);
        assertEquals(2f, PlaylistMoveWheelFilter.speedMultiplier(20000), 0.001f);
    }

    @Test
    public void strideForElapsed_doublesAtHighSpeed() {
        assertEquals(1, PlaylistMoveWheelFilter.strideForElapsed(0));
        assertEquals(1, PlaylistMoveWheelFilter.strideForElapsed(4000));
        assertEquals(2, PlaylistMoveWheelFilter.strideForElapsed(6000));
        assertEquals(2, PlaylistMoveWheelFilter.strideForElapsed(12000));
    }

    @Test
    public void acceptStride_firstTwoOfBurst() {
        PlaylistMoveWheelFilter f = new PlaylistMoveWheelFilter();
        assertEquals(1, f.acceptStride(1));
        assertEquals(1, f.acceptStride(1));
        assertEquals(0, f.acceptStride(1));
    }

    @Test
    public void reset_restartsBurst() {
        PlaylistMoveWheelFilter f = new PlaylistMoveWheelFilter();
        f.acceptStride(1);
        f.acceptStride(1);
        f.reset();
        assertTrue(f.acceptStride(1) > 0);
    }
}
