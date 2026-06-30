package com.solar.launcher.flow;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FlowFlipControllerTest {

    @Test
    public void flipToBackReachesBackState() {
        FlowFlipController flip = new FlowFlipController();
        flip.setBackContent("Album", "Artist", Arrays.asList(
                new FlowScreenHost.FlowBackRow("Track 1", "", null, null)));
        long start = System.currentTimeMillis();
        flip.startFlipToBack(null);
        assertEquals(FlowFlipController.STATE_FLIP_TO_BACK, flip.getState());
        for (long t = start + 16; t <= start + 300; t += 16) {
            flip.tick(t);
        }
        assertEquals(FlowFlipController.STATE_BACK, flip.getState());
        assertEquals(1f, flip.flipProgress(), 0.01f);
    }

    @Test
    public void scrollBackClampsAtEnds() {
        FlowFlipController flip = new FlowFlipController();
        flip.setBackContent("A", "", Arrays.asList(
                new FlowScreenHost.FlowBackRow("One", "", null, null),
                new FlowScreenHost.FlowBackRow("Two", "", null, null)));
        long start = System.currentTimeMillis();
        flip.startFlipToBack(null);
        for (long t = start + 16; t <= start + 300; t += 16) flip.tick(t);
        assertTrue(flip.scrollBackBy(1));
        assertEquals(1, flip.backIndex());
        assertFalse(flip.scrollBackBy(1));
        assertEquals(1, flip.backIndex());
        assertTrue(flip.scrollBackBy(-1));
        assertEquals(0, flip.backIndex());
        assertFalse(flip.scrollBackBy(-1));
    }

    @Test
    public void backStackPushPop() {
        FlowFlipController flip = new FlowFlipController();
        flip.setBackContent("Artist", "", Arrays.asList(
                new FlowScreenHost.FlowBackRow("Album A", "", null, null, null, null)));
        long start = System.currentTimeMillis();
        flip.startFlipToBack(null);
        for (long t = start + 16; t <= start + 300; t += 16) flip.tick(t);
        flip.pushBackLevel();
        flip.setBackContent("Album A", "", Arrays.asList(
                new FlowScreenHost.FlowBackRow("Track 1", "", null, null)));
        assertTrue(flip.popBackLevel());
        assertEquals("Artist", flip.headerTitle());
        assertEquals(1, flip.backRows().size());
        assertEquals("Album A", flip.backRows().get(0).title);
    }

    @Test
    public void flipToFrontReturnsIdle() {
        FlowFlipController flip = new FlowFlipController();
        flip.setBackContent("A", "", Collections.singletonList(
                new FlowScreenHost.FlowBackRow("T", "", null, null)));
        long start = System.currentTimeMillis();
        flip.startFlipToBack(null);
        for (long t = start + 16; t <= start + 300; t += 16) flip.tick(t);
        flip.startFlipToFront(null);
        for (long t = start + 316; t <= start + 650; t += 16) flip.tick(t);
        assertEquals(FlowFlipController.STATE_IDLE, flip.getState());
        assertFalse(flip.isFlippedOrFlipping());
    }

    @Test
    public void easeOutQuadEndsAtOne() {
        assertEquals(1f, FlowFlipController.easeOutQuad(1f), 0.001f);
        assertTrue(FlowFlipController.easeOutQuad(0.5f) > 0.5f);
    }
}
