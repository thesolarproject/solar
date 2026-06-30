package com.solar.launcher.flow;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FlowEngineTest {

    @Test
    public void scrollByReturningMoved_advancesCarousel() {
        FlowEngine engine = new FlowEngine();
        engine.setItemCount(5);
        engine.setFocusIndex(1);
        assertTrue(engine.scrollByReturningMoved(1));
        for (long t = 16; t <= 800; t += 16) engine.tick(t);
        assertEquals(2, engine.getFocusIndex());
        assertFalse(engine.isAnimating());
    }

    @Test
    public void scrollClampsAtEnds() {
        FlowEngine engine = new FlowEngine();
        engine.setItemCount(3);
        engine.setFocusIndex(0);
        engine.scrollBy(-1);
        for (long t = 0; t < 500; t += 16) engine.tick(t);
        assertEquals(0, engine.getFocusIndex());
        engine.setFocusIndex(2);
        engine.scrollBy(1);
        for (long t = 0; t < 1000; t += 16) engine.tick(t);
        assertEquals(2, engine.getFocusIndex());
    }

    @Test
    public void animationProgressBetweenIndices() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(10);
        engine.setFocusIndex(3);
        engine.scrollBy(1);
        assertTrue(engine.isAnimating());
        for (long t = 16; t <= 120; t += 16) engine.tick(t);
        float mid = engine.getVisualOffset();
        assertTrue("mid scroll should be between 3 and 4", mid > 3.05f && mid < 3.95f);
        for (long t = 121; t <= 800; t += 16) engine.tick(t);
        assertTrue(!engine.isAnimating());
        assertEquals(4, engine.getFocusIndex());
        assertEquals(4f, engine.getVisualOffset(), 0.01f);
    }

    @Test
    public void sideSlotRotationNearFortyFiveDegrees() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(5);
        engine.setFocusIndex(2);
        FlowEngine.SlotTransform left = engine.slotTransform(1, 480f, 360f, 0f);
        FlowEngine.SlotTransform right = engine.slotTransform(3, 480f, 360f, 0f);
        assertTrue(Math.abs(left.rotationYDeg + 45f) < 10f);
        assertTrue(Math.abs(right.rotationYDeg - 45f) < 10f);
        assertTrue(left.width > 0f);
        assertEquals(left.width, left.height, left.width * 0.05f);
    }

    @Test
    public void coverFlowMetricsSquareOnY1() {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        assertTrue(m.displaySize >= 160f && m.displaySize <= 190f);
        assertTrue(m.reflectHeight > 100f);
    }

    @Test
    public void findIndexMatchesKeyOrTitle() {
        FlowEngine engine = new FlowEngine();
        java.util.List<FlowItem> items = Arrays.asList(
                FlowItem.album("OK Computer", "", "ok|radiohead", Collections.<java.io.File>emptyList(), ""),
                FlowItem.artist("Radiohead", "", "radiohead"));
        assertEquals(0, engine.findIndexForKey(items, "ok|radiohead"));
        assertEquals(0, engine.findIndexForKey(items, "ok"));
        assertEquals(1, engine.findIndexForKey(items, "radiohead"));
    }

    @Test
    public void visibleSlotRangeTracksFocus() {
        FlowEngine engine = new FlowEngine();
        engine.setItemCount(20);
        engine.setFocusIndex(5);
        assertTrue(engine.visibleSlotMin() <= 5);
        assertTrue(engine.visibleSlotMax() >= 5);
    }
}
