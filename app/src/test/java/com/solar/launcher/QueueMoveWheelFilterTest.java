package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QueueMoveWheelFilterTest {

    @Test
    public void accept_firstTwoOfFiveInBurst() {
        QueueMoveWheelFilter f = new QueueMoveWheelFilter();
        assertTrue(f.accept());
        assertTrue(f.accept());
        assertFalse(f.accept());
        assertFalse(f.accept());
        assertFalse(f.accept());
    }

    @Test
    public void accept_patternRepeatsEveryFive() {
        QueueMoveWheelFilter f = new QueueMoveWheelFilter();
        for (int group = 0; group < 3; group++) {
            assertTrue(f.accept());
            assertTrue(f.accept());
            assertFalse(f.accept());
            assertFalse(f.accept());
            assertFalse(f.accept());
        }
    }

    @Test
    public void reset_startsNewBurst() {
        QueueMoveWheelFilter f = new QueueMoveWheelFilter();
        f.accept();
        f.accept();
        f.reset();
        assertTrue(f.accept());
    }
}
