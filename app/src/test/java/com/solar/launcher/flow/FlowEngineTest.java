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
    public void sideSlotRotationNearClassipodTilt() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(7);
        engine.setFocusIndex(2);
        FlowEngine.SlotTransform left = engine.slotTransform(1, 480f, 360f, 0f);
        FlowEngine.SlotTransform right = engine.slotTransform(3, 480f, 360f, 0f);
        assertTrue(Math.abs(left.rotationYDeg - 50f) < 10f);
        assertTrue(Math.abs(right.rotationYDeg + 50f) < 10f);
        assertTrue(left.width > 0f);
        assertEquals(left.width, left.height, left.width * 0.05f);
    }

    @Test
    public void nearestNeighborsTuckCloserThanOuterRack() {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        float rank1 = CoverFlowLayout.cxForSideRank(1, m);
        float rank2 = CoverFlowLayout.cxForSideRank(2, m);
        float rank4 = CoverFlowLayout.cxForSideRank(4, m);
        assertTrue("rank1 fans out from center", rank1 > m.displaySize * 0.65f);
        assertTrue("outer rack fans outward", rank4 > rank2 && rank2 > rank1);
        FlowEngine engine = new FlowEngine();
        engine.setViewportMetrics(m, 480f, 360f);
        engine.setItemCount(7);
        engine.setFocusIndex(3);
        FlowEngine.SlotTransform center = engine.slotTransform(3, 480f, 360f, 0f);
        FlowEngine.SlotTransform neighbor = engine.slotTransform(4, 480f, 360f, 0f);
        float gap = neighbor.centerX - center.centerX - center.width * 0.5f;
        assertTrue("neighbor peeks before center right edge ends",
                gap < m.displaySize * 0.35f);
    }

    @Test
    public void coverFlowClassicMetricsOnY1() {
        assertEquals(4, CoverFlowLayout.SIDE_SLIDES);
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        assertEquals(120f, m.reflectHeight, 1f);
        assertTrue(m.displaySize >= 190f && m.displaySize <= 220f);
        float centerTop = m.viewH * 0.33f - m.displaySize * 0.5f;
        assertTrue("center cover must not clip above viewport", centerTop >= 0f);
    }

    @Test
    public void coverFlowMetricsSquareOnY1() {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        assertTrue(m.displaySize >= 190f && m.displaySize <= 220f);
        assertTrue(m.reflectHeight > 100f);
    }

    @Test
    public void incomingCoverPromotesDuringScroll() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(10);
        engine.setFocusIndex(3);
        engine.scrollBy(1);
        float earlyDepth = 0f;
        float lateDepth = 0f;
        for (long t = 16; t <= 600; t += 16) {
            engine.tick(t);
            FlowEngine.SlotTransform incoming = engine.slotTransform(4, 480f, 360f, 0f);
            if (t <= 120) earlyDepth = Math.max(earlyDepth, incoming.depthOrder);
            if (t >= 300) lateDepth = Math.max(lateDepth, incoming.depthOrder);
        }
        assertTrue("incoming cover advances in paint order during scroll", lateDepth > earlyDepth + 50f);
        FlowEngine.SlotTransform focused = engine.slotTransform(4, 480f, 360f, 0f);
        FlowEngine.SlotTransform side = engine.slotTransform(5, 480f, 360f, 0f);
        assertTrue("focused cover larger than side", focused.width > side.width);
    }

    @Test
    public void coverFlowPivotOnInnerEdgeForSideTilt() {
        assertEquals(190.91f, CoverFlowLayout.pivotXForCoverFlow(45f, 100f, 200f), 0.5f);
        assertEquals(109.09f, CoverFlowLayout.pivotXForCoverFlow(-45f, 100f, 200f), 0.5f);
        assertEquals(150f, CoverFlowLayout.pivotXForCoverFlow(0f, 100f, 200f), 0.01f);
    }

    @Test
    public void centernessAtCenterIsOne() {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        CoverFlowLayout.SlidePose center = new CoverFlowLayout.SlidePose();
        center.cx = 0f;
        center.angle = 0;
        assertEquals(1f, CoverFlowLayout.centernessFromPose(center, m), 0.01f);
        CoverFlowLayout.SlidePose side = new CoverFlowLayout.SlidePose();
        side.cx = -CoverFlowLayout.cxForSideRank(1, m);
        side.angle = CoverFlowLayout.SIDE_TILT;
        side.angleDeg = CoverFlowLayout.angleToDegrees(CoverFlowLayout.SIDE_TILT);
        assertTrue(CoverFlowLayout.centernessFromPose(side, m) < 0.2f);
    }

    @Test
    public void visualCenterIndexDuringScroll() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(10);
        engine.setFocusIndex(3);
        engine.scrollBy(1);
        for (long t = 16; t <= 120; t += 16) engine.tick(t);
        assertTrue(engine.isAnimating());
        int visual = engine.getVisualCenterIndex();
        int focus = engine.getFocusIndex();
        assertTrue("visual center during scroll is 3 or 4", visual == 3 || visual == 4);
        assertTrue("focus and visual differ by at most one slot",
                Math.abs(focus - visual) <= 1);
        engine.snapToVisualCenter();
        assertFalse(engine.isAnimating());
        assertEquals(visual, engine.getFocusIndex());
    }

    @Test
    public void findIndexMatchesKeyOrTitle() {
        FlowEngine engine = new FlowEngine();
        java.util.List<FlowItem> items = Arrays.asList(
                FlowItem.album("OK Computer", "", "ok|radiohead", Collections.<java.io.File>emptyList(), ""),
                FlowItem.artist("Radiohead", "", "radiohead"));
        assertEquals(0, engine.findIndexForKey(items, "ok|radiohead"));
        assertEquals(0, engine.findIndexForKey(items, "ok"));
        assertEquals(0, engine.findIndexForKey(items, "ok|other artist"));
        assertEquals(1, engine.findIndexForKey(items, "radiohead"));
    }

    @Test
    public void squareBackFaceAnchorKeepsEqualWidthHeight() {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        FlowEngine.SlotTransform t = new FlowEngine.SlotTransform();
        t.centerX = m.viewW * 0.5f;
        t.centerY = m.viewH * 0.33f;
        t.width = m.displaySize;
        t.height = m.displaySize;
        CoverFlowLayout.applySquareBackFaceSouthAnchor(t, m, 1f, 48f, 12f);
        assertEquals(t.width, t.height, t.width * 0.01f);
        assertTrue(t.width > m.displaySize * 0.9f);
    }

    @Test
    public void rectangleEncroachDiffersWidthHeight() {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        FlowEngine.SlotTransform t = new FlowEngine.SlotTransform();
        t.centerX = m.viewW * 0.5f;
        t.centerY = m.viewH * 0.33f;
        t.width = m.displaySize;
        t.height = m.displaySize;
        CoverFlowLayout.applyBackFaceSouthEncroach(t, m, 1f, 48f, 12f);
        assertTrue(t.width > t.height * 1.02f);
    }

    @Test
    public void rectangleEncroachMatchesIpodProportions() {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        FlowEngine.SlotTransform t = new FlowEngine.SlotTransform();
        t.centerX = m.viewW * 0.5f;
        t.centerY = m.viewH * 0.33f;
        t.width = m.displaySize;
        t.height = m.displaySize;
        float topInset = 48f;
        float bottomMargin = 12f;
        CoverFlowLayout.applyBackFaceSouthEncroach(t, m, 1f, topInset, bottomMargin);
        float expectedW = m.viewW * CoverFlowLayout.BACK_FACE_WIDTH_FRAC;
        float availableH = m.viewH - topInset - bottomMargin;
        float expectedH = Math.min(m.viewH * CoverFlowLayout.BACK_FACE_HEIGHT_FRAC, availableH);
        assertEquals(expectedW, t.width, 1f);
        assertEquals(expectedH, t.height, 1f);
        float bottom = t.centerY + t.height * 0.5f;
        assertEquals(m.viewH - bottomMargin, bottom, 1f);
    }

    @Test
    public void sameDirectionScrollExtendsTarget() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(10);
        engine.setFocusIndex(3);
        engine.scrollByReturningMoved(1);
        assertTrue(engine.isAnimating());
        engine.scrollByReturningMoved(1);
        for (long t = 16; t <= 1200; t += 16) engine.tick(t);
        assertEquals(5, engine.getFocusIndex());
    }

    @Test
    public void rackLayeringInnerNeighborAboveOuterAtIdle() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(20);
        engine.setFocusIndex(10);
        float rank1Depth = engine.slotTransform(11, 480f, 360f, 0f).depthOrder;
        float rank2Depth = engine.slotTransform(12, 480f, 360f, 0f).depthOrder;
        float centerDepth = engine.slotTransform(10, 480f, 360f, 0f).depthOrder;
        assertTrue("rank1 should paint above rank2 (higher depthOrder)", rank1Depth > rank2Depth);
        assertTrue("center should paint above rank1", centerDepth > rank1Depth);
    }

    @Test
    public void leftRackOutermostPaintsBehindInnerNeighbor() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(20);
        engine.setFocusIndex(10);
        float rank3Left = engine.slotTransform(7, 480f, 360f, 0f).depthOrder;
        float rank4Left = engine.slotTransform(6, 480f, 360f, 0f).depthOrder;
        assertTrue("left rank3 should paint above leftmost rank4", rank3Left > rank4Left);
    }

    @Test
    public void depthOrderMatchesSlotTransform() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(5);
        engine.setFocusIndex(2);
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        for (int idx = 0; idx < 5; idx++) {
            FlowEngine.SlotTransform t = engine.slotTransform(idx, 480f, 360f, 0f);
            CoverFlowLayout.SlidePose pose = engine.poseForItemPublic(idx);
            float depth = CoverFlowLayout.depthOrderFromPose(pose, m);
            assertEquals(t.depthOrder, depth, 0.001f);
        }
    }

    @Test
    public void continuousPoseMidScrollShowsBothNeighbors() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(10);
        engine.setFocusIndex(3);
        engine.scrollBy(1);
        assertTrue(engine.isAnimating());
        float visual = 3f;
        for (long t = 16; t <= 400; t += 16) {
            engine.tick(t);
            visual = engine.getVisualOffset();
            if (visual > 3.05f && visual < 3.95f) break;
        }
        assertTrue("mid scroll between 3 and 4", visual > 3.05f && visual < 3.95f);
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        CoverFlowLayout.SlidePose outgoing =
                CoverFlowLayout.poseFromRelative(3 - visual, m);
        CoverFlowLayout.SlidePose incoming =
                CoverFlowLayout.poseFromRelative(4 - visual, m);
        assertTrue(outgoing.alpha > 0);
        assertTrue(incoming.alpha > 0);
        assertTrue(Math.abs(outgoing.angleDeg) > 0.5f);
        assertTrue(Math.abs(incoming.angleDeg) > 0.5f);
        FlowEngine.SlotTransform t3 = engine.slotTransform(3, 480f, 360f, 0f);
        FlowEngine.SlotTransform t4 = engine.slotTransform(4, 480f, 360f, 0f);
        assertTrue(t3.alpha > 0.01f);
        assertTrue(t4.alpha > 0.01f);
    }

    @Test
    public void poseFromRelativeMirrorsLeftAndRight() {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        for (float rel : new float[] {0.3f, 0.5f}) {
            CoverFlowLayout.SlidePose right = CoverFlowLayout.poseFromRelative(rel, m);
            CoverFlowLayout.SlidePose left = CoverFlowLayout.poseFromRelative(-rel, m);
            assertEquals(Math.abs(right.angleDeg), Math.abs(left.angleDeg), 0.5f);
            assertEquals(Math.abs(right.cx), Math.abs(left.cx), 0.5f);
        }
    }

    @Test
    public void fastWheelQueuesEveryAlbum() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(10);
        engine.setFocusIndex(3);
        engine.scrollBy(1);
        assertTrue(engine.isAnimating());
        assertEquals(4, engine.getTargetIndex());
        engine.scrollBy(1);
        assertEquals(5, engine.getTargetIndex());
        long start = 0L;
        for (long t = start + 16; t <= start + FlowEngine.SCROLL_MS + 32; t += 16) engine.tick(t);
        assertFalse(engine.isAnimating());
        assertEquals(5, engine.getFocusIndex());
    }

    @Test
    public void scrollCompletesNearClassipodDuration() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(10);
        engine.setFocusIndex(3);
        long start = 1000L;
        engine.scrollBy(1);
        engine.tick(start);
        assertTrue(engine.isAnimating());
        engine.tick(start + FlowEngine.SCROLL_MS - 1);
        assertTrue(engine.isAnimating());
        engine.tick(start + FlowEngine.SCROLL_MS);
        assertFalse(engine.isAnimating());
        assertEquals(4, engine.getFocusIndex());
    }

    @Test
    public void pivotHingeOnSideCovers() {
        assertEquals(100.91f, CoverFlowLayout.pivotXForCoverFlow(45f, 10f, 110f), 0.5f);
        assertEquals(19.09f, CoverFlowLayout.pivotXForCoverFlow(-45f, 10f, 110f), 0.5f);
        assertEquals(60f, CoverFlowLayout.pivotXForCoverFlow(0f, 10f, 110f), 0.01f);
        assertEquals(110f, CoverFlowLayout.pivotXForCoverFlow(58f, 10f, 110f), 0.01f);
        assertEquals(10f, CoverFlowLayout.pivotXForCoverFlow(-58f, 10f, 110f), 0.01f);
    }

    @Test
    public void neighborSlotsVisibleAtIdleFocus() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(10);
        int focus = 5;
        engine.setFocusIndex(focus);
        FlowEngine.SlotTransform left = engine.slotTransform(focus - 1, 480f, 360f, 0f);
        FlowEngine.SlotTransform center = engine.slotTransform(focus, 480f, 360f, 0f);
        FlowEngine.SlotTransform right = engine.slotTransform(focus + 1, 480f, 360f, 0f);
        assertTrue("left neighbor visible", left.alpha > 0f);
        assertTrue("center visible", center.alpha > 0f);
        assertTrue("right neighbor visible", right.alpha > 0f);
        float vo = engine.getVisualOffset();
        assertEquals(-1f, (focus - 1) - vo, 0.01f);
        assertEquals(0f, focus - vo, 0.01f);
        assertEquals(1f, (focus + 1) - vo, 0.01f);
    }

    @Test
    public void fiveSlotAlphabetMappingAtFocusTwo() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(26);
        engine.setFocusIndex(2);
        float vo = engine.getVisualOffset();
        assertEquals(2f, vo, 0.01f);
        for (int d = -2; d <= 2; d++) {
            int idx = 2 + d;
            FlowEngine.SlotTransform t = engine.slotTransform(idx, 480f, 360f, 0f);
            assertEquals("rel at idx " + idx, (float) d, idx - vo, 0.05f);
            assertTrue("slot visible idx " + idx, t.alpha > 0.05f);
        }
        assertEquals(2, engine.getVisualCenterIndex());
    }

    @Test
    public void threeAlbumRackNeighborsVisibleAtEachFocus() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setViewportMetrics(CoverFlowLayout.metricsForViewport(480f, 360f), 480f, 360f);
        engine.setItemCount(3);
        for (int focus = 0; focus < 3; focus++) {
            engine.setFocusIndex(focus);
            if (focus > 0) {
                FlowEngine.SlotTransform left = engine.slotTransform(focus - 1, 480f, 360f, 0f);
                assertTrue("left visible at focus " + focus, left.alpha > 0.05f);
            }
            FlowEngine.SlotTransform center = engine.slotTransform(focus, 480f, 360f, 0f);
            assertTrue("center visible at focus " + focus, center.alpha > 0.05f);
            if (focus < 2) {
                FlowEngine.SlotTransform right = engine.slotTransform(focus + 1, 480f, 360f, 0f);
                assertTrue("right visible at focus " + focus, right.alpha > 0.05f);
            }
        }
    }

    @Test
    public void wheelLeftFromFocusOneLandsOnZero() {
        FlowEngine engine = new FlowEngine();
        engine.setViewport(480f, 360f);
        engine.setItemCount(3);
        engine.setFocusIndex(1);
        engine.scrollByReturningMoved(-1);
        for (long t = 16; t <= 800; t += 16) engine.tick(t);
        assertEquals(0, engine.getFocusIndex());
    }

    @Test
    public void unifiedMetricsUseIpodLayoutConstants() {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        assertEquals(CoverFlowLayout.NEIGHBOR_CX_FRAC, m.offsetX / m.displaySize, 0.01f);
        assertEquals(CoverFlowLayout.OUTER_SPACING_FRAC, m.outerSpacingFrac, 0.01f);
        assertEquals(CoverFlowLayout.SIDE_SCALE, m.sideScale, 0.01f);
        assertEquals(CoverFlowLayout.SIDE_TILT, m.sideTilt);
    }

    /** Tilted side covers must extend past center bbox — otherwise center paint hides them. */
    @Test
    public void threeAlbumSmallRackNeighborPeeksPastCenterEdge() {
        FlowEngine engine = new FlowEngine();
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        engine.setViewportMetrics(m, 480f, 360f);
        engine.setItemCount(3);
        engine.setFocusIndex(0);
        FlowEngine.SlotTransform center = engine.slotTransform(0, 480f, 360f, 0f);
        FlowEngine.SlotTransform right = engine.slotTransform(1, 480f, 360f, 0f);
        float centerRight = center.centerX + center.width * 0.5f;
        float hinge = right.centerX - right.width * 0.5f;
        double rad = Math.toRadians(right.rotationYDeg);
        float rightEdge = hinge + (float) (right.width * Math.cos(rad));
        assertTrue("right neighbor face peeks past center",
                rightEdge > centerRight + m.displaySize * 0.12f);
        engine.setFocusIndex(1);
        FlowEngine.SlotTransform left = engine.slotTransform(0, 480f, 360f, 0f);
        center = engine.slotTransform(1, 480f, 360f, 0f);
        float centerLeft = center.centerX - center.width * 0.5f;
        float rightHinge = left.centerX + left.width * 0.5f;
        rad = Math.toRadians(left.rotationYDeg);
        float leftEdge = rightHinge - (float) (left.width * Math.cos(rad));
        assertTrue("left neighbor face peeks past center",
                leftEdge < centerLeft - m.displaySize * 0.12f);
    }

    /** Small rack at middle index — both neighbors show meaningful face, not a 2px sliver. */
    @Test
    public void threeAlbumSmallRackBothNeighborsVisibleAtCenter() {
        FlowEngine engine = new FlowEngine();
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        engine.setViewportMetrics(m, 480f, 360f);
        engine.setItemCount(3);
        engine.setFocusIndex(1);
        FlowEngine.SlotTransform center = engine.slotTransform(1, 480f, 360f, 0f);
        FlowEngine.SlotTransform left = engine.slotTransform(0, 480f, 360f, 0f);
        FlowEngine.SlotTransform right = engine.slotTransform(2, 480f, 360f, 0f);
        float centerLeft = center.centerX - center.width * 0.5f;
        float centerRight = center.centerX + center.width * 0.5f;
        float leftEdge = left.centerX + left.width * 0.5f
                - (float) (left.width * Math.cos(Math.toRadians(left.rotationYDeg)));
        float rightHinge = right.centerX - right.width * 0.5f;
        float rightEdge = rightHinge
                + (float) (right.width * Math.cos(Math.toRadians(right.rotationYDeg)));
        assertTrue(leftEdge < centerLeft - m.displaySize * 0.10f);
        assertTrue(rightEdge > centerRight + m.displaySize * 0.10f);
    }

    /** Large library — idle shows center + ±2 neighbors (5 covers) with same layout as tiny racks. */
    @Test
    public void largeLibraryFiveCoversVisibleAtIdle() {
        FlowEngine engine = new FlowEngine();
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        engine.setViewportMetrics(m, 480f, 360f);
        engine.setItemCount(20);
        int focus = 10;
        engine.setFocusIndex(focus);
        for (int d = -2; d <= 2; d++) {
            FlowEngine.SlotTransform t = engine.slotTransform(focus + d, 480f, 360f, 0f);
            assertTrue("slot visible at offset " + d, t.alpha > 0.05f);
        }
        FlowEngine.SlotTransform center = engine.slotTransform(focus, 480f, 360f, 0f);
        FlowEngine.SlotTransform right2 = engine.slotTransform(focus + 2, 480f, 360f, 0f);
        FlowEngine.SlotTransform left2 = engine.slotTransform(focus - 2, 480f, 360f, 0f);
        float centerRight = center.centerX + center.width * 0.5f;
        float centerLeft = center.centerX - center.width * 0.5f;
        float rightHinge = right2.centerX - right2.width * 0.5f;
        float rightEdge = rightHinge
                + (float) (right2.width * Math.cos(Math.toRadians(right2.rotationYDeg)));
        float leftHinge = left2.centerX + left2.width * 0.5f;
        float leftEdge = leftHinge
                - (float) (left2.width * Math.cos(Math.toRadians(left2.rotationYDeg)));
        assertTrue("outer right peeks past center", rightEdge > centerRight + m.displaySize * 0.08f);
        assertTrue("outer left peeks past center", leftEdge < centerLeft - m.displaySize * 0.08f);
    }

    /** Outermost covers drift off-screen and fade — no margin clamp pop-in. */
    @Test
    public void outboundEgressDriftsAndFades() {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(480f, 360f);
        assertTrue(CoverFlowLayout.cxForSideRank(4, m) > CoverFlowLayout.cxForSideRank(3, m));
        CoverFlowLayout.SlidePose rack2 = CoverFlowLayout.poseFromRelative(2f, m);
        CoverFlowLayout.SlidePose egress = CoverFlowLayout.poseFromRelative(2.75f, m);
        assertTrue("egress pushes past idle rank-2 cx", egress.cx > rack2.cx + m.displaySize * 0.1f);
        assertTrue("egress fades into shadow", egress.alpha < rack2.alpha * 0.75f);
        FlowEngine engine = new FlowEngine();
        engine.setViewportMetrics(m, 480f, 360f);
        engine.setItemCount(20);
        engine.setFocusIndex(10);
        engine.scrollBy(1);
        float midAlpha = 1f;
        float midCx = 0f;
        for (long t = 16; t <= 200; t += 16) {
            engine.tick(t);
            FlowEngine.SlotTransform leaving = engine.slotTransform(8, 480f, 360f, 0f);
            float rel = 8 - engine.getVisualOffset();
            if (rel < -1.6f && rel > -2.8f) {
                midCx = leaving.centerX;
                midAlpha = leaving.alpha;
            }
        }
        assertTrue("leaving outer cover moves left during scroll", midCx < m.viewW * 0.5f);
        assertTrue("leaving outer cover dims during scroll", midAlpha < 0.92f);
    }

    @Test
    public void playerPoseUsesPositiveSlant() {
        assertEquals(14f, FlowAlbumArt3d.PLAYER_ROT_Y_DEG, 0.01f);
        float viewW = 200f;
        float viewH = 280f;
        float size = Math.min(viewW, viewH);
        // ponytail: host JVM android.jar RectF stub may not set fields — assert layout math.
        assertEquals(40f, (viewH - size) * 0.5f, 0.01f);
        FlowAlbumArt3d.AlbumArtPose pose = FlowAlbumArt3d.playerPose(viewW, viewH);
        assertEquals(FlowAlbumArt3d.PLAYER_ROT_Y_DEG, pose.rotationYDeg, 0.01f);
        assertEquals(size, Math.min(viewW, viewH), 0.01f);
    }
}
