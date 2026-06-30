package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaylistMoveWindowTest {

    private static final int SLOT_H = 60;

    @Test
    public void moveSlot_topMiddleNearBottomAndLast() {
        assertEquals(0, PlaylistMoveWindow.moveSlot(0, 10));
        assertEquals(1, PlaylistMoveWindow.moveSlot(1, 10));
        assertEquals(2, PlaylistMoveWindow.moveSlot(5, 10));
        assertEquals(2, PlaylistMoveWindow.moveSlot(7, 10));
        assertEquals(3, PlaylistMoveWindow.moveSlot(8, 10));
        assertEquals(4, PlaylistMoveWindow.moveSlot(9, 10));
    }

    @Test
    public void moveSlot_shortList() {
        assertEquals(0, PlaylistMoveWindow.moveSlot(0, 2));
        assertEquals(1, PlaylistMoveWindow.moveSlot(1, 2));
        assertEquals(2, PlaylistMoveWindow.moveSlot(2, 3));
        assertEquals(4, PlaylistMoveWindow.moveSlot(4, 5));
    }

    @Test
    public void slotDataIndex_middleShowsFiveConsecutive() {
        int moveIdx = 5;
        int count = 10;
        assertEquals(3, PlaylistMoveWindow.slotDataIndex(moveIdx, 0, count));
        assertEquals(4, PlaylistMoveWindow.slotDataIndex(moveIdx, 1, count));
        assertEquals(5, PlaylistMoveWindow.slotDataIndex(moveIdx, 2, count));
        assertEquals(6, PlaylistMoveWindow.slotDataIndex(moveIdx, 3, count));
        assertEquals(7, PlaylistMoveWindow.slotDataIndex(moveIdx, 4, count));
    }

    @Test
    public void slotDataIndex_bottomAnchorsLastFive() {
        int count = 10;
        int moveIdx = 9;
        assertEquals(5, PlaylistMoveWindow.slotDataIndex(moveIdx, 0, count));
        assertEquals(6, PlaylistMoveWindow.slotDataIndex(moveIdx, 1, count));
        assertEquals(7, PlaylistMoveWindow.slotDataIndex(moveIdx, 2, count));
        assertEquals(8, PlaylistMoveWindow.slotDataIndex(moveIdx, 3, count));
        assertEquals(9, PlaylistMoveWindow.slotDataIndex(moveIdx, 4, count));
    }

    @Test
    public void bottomPadding_shortListAndLastTrack() {
        assertEquals(180, PlaylistMoveWindow.bottomPaddingPx(1, 2, SLOT_H));
        assertEquals(120, PlaylistMoveWindow.bottomPaddingPx(2, 3, SLOT_H));
        assertEquals(0, PlaylistMoveWindow.bottomPaddingPx(5, 10, SLOT_H));
        assertEquals(SLOT_H, PlaylistMoveWindow.bottomPaddingPx(9, 10, SLOT_H));
    }

    @Test
    public void enterTranslationSlots() {
        assertEquals(-2, PlaylistMoveWindow.enterTranslationSlots(5, 10, 0));
        assertEquals(0, PlaylistMoveWindow.enterTranslationSlots(5, 10, 2));
    }
}
