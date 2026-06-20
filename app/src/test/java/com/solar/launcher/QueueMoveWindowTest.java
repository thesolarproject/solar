package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QueueMoveWindowTest {

    @Test
    public void ribbonAboveIndex_edgesAndInterior() {
        assertEquals(-1, QueueMoveWindow.ribbonAboveIndex(0));
        assertEquals(0, QueueMoveWindow.ribbonAboveIndex(1));
        assertEquals(4, QueueMoveWindow.ribbonAboveIndex(5));
    }

    @Test
    public void ribbonBelowIndex_edgesAndInterior() {
        assertEquals(-1, QueueMoveWindow.ribbonBelowIndex(0, 1));
        assertEquals(1, QueueMoveWindow.ribbonBelowIndex(0, 3));
        assertEquals(-1, QueueMoveWindow.ribbonBelowIndex(9, 10));
        assertEquals(6, QueueMoveWindow.ribbonBelowIndex(5, 10));
    }

    @Test
    public void ribbon_neighborsShowNowPlayingWhenAdjacent() {
        int np = 5;
        assertEquals(np, QueueMoveWindow.ribbonBelowIndex(np - 1, 10));
        assertEquals(np, QueueMoveWindow.ribbonAboveIndex(np + 1));
    }

    @Test
    public void nextMoveIndex_adjacentNowPlaying_swapsInsteadOfSkipping() {
        int np = 2;
        assertEquals(2, QueueMoveWindow.nextMoveIndex(3, -1, np, 6));
        assertEquals(2, QueueMoveWindow.nextMoveIndex(1, 1, np, 6));
    }

    @Test
    public void nextMoveIndex_doesNotSkipWhenNowPlayingNotTarget() {
        int np = 2;
        assertEquals(4, QueueMoveWindow.nextMoveIndex(5, -1, np, 6));
        assertEquals(1, QueueMoveWindow.nextMoveIndex(0, 1, np, 6));
    }

    @Test
    public void canMoveTo_allowsAdjacentNowPlayingSwap() {
        int np = 2;
        assertTrue(QueueMoveWindow.canMoveTo(3, np, np));
        assertTrue(QueueMoveWindow.canMoveTo(1, np, np));
        assertFalse(QueueMoveWindow.canMoveTo(4, np, np));
        assertFalse(QueueMoveWindow.canMoveTo(np, 3, np));
    }
}
