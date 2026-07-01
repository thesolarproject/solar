package com.solar.launcher.flow;

import android.graphics.RectF;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FlowPlayerHandoffTest {

    private static RectF rect(float left, float top, float right, float bottom) {
        RectF r = new RectF();
        r.left = left;
        r.top = top;
        r.right = right;
        r.bottom = bottom;
        return r;
    }

    @Test
    public void reverseMorphStartsAtPlayerEndsAtFlow() {
        RectF player = rect(100f, 50f, 200f, 150f);
        RectF flow = rect(200f, 100f, 280f, 180f);
        float fromRotY = FlowAlbumArt3d.PLAYER_ROT_Y_DEG;
        float toRotY = 0f;

        RectF pose = new RectF();
        float[] rot = new float[1];

        FlowPlayerHandoff.handoffPoseAt(0f, player, flow, fromRotY, toRotY, pose, rot);
        assertEquals((player.left + player.right) * 0.5f, (pose.left + pose.right) * 0.5f, 0.5f);
        assertEquals((player.top + player.bottom) * 0.5f, (pose.top + pose.bottom) * 0.5f, 0.5f);
        assertEquals(fromRotY, rot[0], 0.01f);

        FlowPlayerHandoff.handoffPoseAt(1f, player, flow, fromRotY, toRotY, pose, rot);
        assertEquals((flow.left + flow.right) * 0.5f, (pose.left + pose.right) * 0.5f, 0.5f);
        assertEquals((flow.top + flow.bottom) * 0.5f, (pose.top + pose.bottom) * 0.5f, 0.5f);
        assertEquals(toRotY, rot[0], 0.01f);

        FlowPlayerHandoff.handoffPoseAt(0.5f, player, flow, fromRotY, toRotY, pose, rot);
        float midCx = (pose.left + pose.right) * 0.5f;
        assertTrue(midCx > (player.left + player.right) * 0.5f
                && midCx < (flow.left + flow.right) * 0.5f);
        assertTrue(rot[0] > toRotY && rot[0] < fromRotY);
    }

    @Test
    public void forwardMorphSameInterpolation() {
        RectF flow = rect(50f, 40f, 150f, 140f);
        RectF player = rect(10f, 20f, 110f, 120f);
        RectF pose = new RectF();
        float[] rot = new float[1];

        FlowPlayerHandoff.handoffPoseAt(0f, flow, player, 0f, FlowAlbumArt3d.PLAYER_ROT_Y_DEG, pose, rot);
        assertEquals(0f, rot[0], 0.01f);

        FlowPlayerHandoff.handoffPoseAt(1f, flow, player, 0f, FlowAlbumArt3d.PLAYER_ROT_Y_DEG, pose, rot);
        assertEquals(FlowAlbumArt3d.PLAYER_ROT_Y_DEG, rot[0], 0.01f);
    }

    @Test
    public void measuredLandingSkipsLandingCrossfadePhase() {
        assertEquals(420, FlowPlayerHandoff.reverseHandoffTotalMs(true));
        assertEquals(1f, FlowPlayerHandoff.reverseFlyerAlphaAt(1f, 0f, true), 0.01f);
        // Helper still ramps carousel alpha for unmeasured paths; measured morph reveals at snap.
        assertEquals(1f, FlowPlayerHandoff.reverseLandingCarouselAlphaAt(1f, 0f, true), 0.01f);
    }

    @Test
    public void unmeasuredLandingUsesLandingCrossfadeDuration() {
        assertEquals(520, FlowPlayerHandoff.reverseHandoffTotalMs(false));
        assertEquals(0f, FlowPlayerHandoff.reverseLandingCarouselAlphaAt(1f, 0f, false), 0.01f);
        assertEquals(1f, FlowPlayerHandoff.reverseLandingCarouselAlphaAt(1f, 1f, false), 0.01f);
        assertEquals(0f, FlowPlayerHandoff.reverseFlyerAlphaAt(1f, 1f, false), 0.01f);
    }

    @Test
    public void reverseFlyerOpaqueUntilLandingCrossfade() {
        assertEquals(1f, FlowPlayerHandoff.reverseFlyerAlphaAt(0.5f, 0f), 0.01f);
        assertEquals(1f, FlowPlayerHandoff.reverseFlyerAlphaAt(1f, 0f), 0.01f);
        assertEquals(0f, FlowPlayerHandoff.reverseFlyerAlphaAt(1f, 1f), 0.01f);
    }

    @Test
    public void reverseCarouselHiddenUntilLandingCrossfade() {
        assertEquals(0f, FlowPlayerHandoff.reverseLandingCarouselAlphaAt(0.5f, 0f), 0.01f);
        assertEquals(0f, FlowPlayerHandoff.reverseLandingCarouselAlphaAt(1f, 0f), 0.01f);
        assertEquals(1f, FlowPlayerHandoff.reverseLandingCarouselAlphaAt(1f, 1f), 0.01f);
    }

    @Test
    public void chromeStaggerStartsLateNotMidMorph() {
        assertTrue(FlowPlayerHandoff.reverseChromeStaggerStartFraction() >= 0.7f);
    }

    @Test
    public void sideRevealRampsDuringMorphSecondHalf() {
        assertEquals(0f, FlowPlayerHandoff.reverseSideRevealAt(0.2f), 0.01f);
        assertTrue(FlowPlayerHandoff.reverseSideRevealAt(0.6f) > 0.2f);
        assertEquals(1f, FlowPlayerHandoff.reverseSideRevealAt(0.9f), 0.01f);
    }

    @Test
    public void reverseMorphLeftNpToCenterFlow_hasHorizontalTravel() {
        // NP slot is left-aligned (~10–110); Flow center is viewport center (~190–270).
        RectF player = rect(10f, 50f, 110f, 150f);
        RectF flow = rect(190f, 80f, 270f, 160f);
        float fromRotY = FlowAlbumArt3d.PLAYER_ROT_Y_DEG;
        float toRotY = 0f;
        RectF pose = new RectF();
        float[] rot = new float[1];

        FlowPlayerHandoff.handoffPoseAt(0.5f, player, flow, fromRotY, toRotY, pose, rot);
        float playerCx = (player.left + player.right) * 0.5f;
        float flowCx = (flow.left + flow.right) * 0.5f;
        float midCx = (pose.left + pose.right) * 0.5f;
        assertTrue("midpoint should be right of NP center", midCx > playerCx + 20f);
        assertTrue("midpoint should be left of Flow center", midCx < flowCx - 20f);
    }

    @Test
    public void retargetLandingRectMovesTowardMeasured() {
        RectF current = rect(100f, 50f, 200f, 150f);
        RectF measured = rect(220f, 110f, 300f, 190f);
        RectF out = new RectF();
        FlowPlayerHandoff.retargetLandingRect(current, measured, 1f, out);
        assertEquals((measured.left + measured.right) * 0.5f, (out.left + out.right) * 0.5f, 0.5f);
        assertEquals((measured.top + measured.bottom) * 0.5f, (out.top + out.bottom) * 0.5f, 0.5f);
        FlowPlayerHandoff.retargetLandingRect(current, measured, 0f, out);
        assertEquals((current.left + current.right) * 0.5f, (out.left + out.right) * 0.5f, 0.5f);
    }
}
