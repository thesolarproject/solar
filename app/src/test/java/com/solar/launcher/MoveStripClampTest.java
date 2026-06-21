package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Boundary clamping for full-list strip reorder (adjacent steps only). */
public class MoveStripClampTest {

    @Test
    public void nextMoveIndex_clampsAtTopAndBottom() {
        assertEquals(0, QueueMoveWindow.nextMoveIndex(0, -1, 5));
        assertEquals(0, QueueMoveWindow.nextMoveIndex(0, 1, 1));
        assertEquals(4, QueueMoveWindow.nextMoveIndex(4, 1, 5));
        assertEquals(3, QueueMoveWindow.nextMoveIndex(4, -1, 5));
    }

    @Test
    public void nextMoveIndex_stepsOneAtATime() {
        assertEquals(1, QueueMoveWindow.nextMoveIndex(0, 1, 5));
        assertEquals(3, QueueMoveWindow.nextMoveIndex(4, -1, 5));
    }

    @Test
    public void nextMoveIndex_emptyListIsNoop() {
        assertEquals(0, QueueMoveWindow.nextMoveIndex(0, 1, 0));
    }
}
