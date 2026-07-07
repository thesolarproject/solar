package com.solar.launcher.ui;

import com.solar.launcher.media.MediaSuiteHost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScreenTransitionTest {

    @Test
    public void menuToBrowserIsPushForward() {
        assertEquals(ScreenTransitionMap.Kind.PUSH_FORWARD,
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_MENU,
                        ScreenTransitionMap.STATE_BROWSER, false));
    }

    @Test
    public void browserToMenuIsPopBack() {
        assertEquals(ScreenTransitionMap.Kind.POP_BACK,
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_BROWSER,
                        ScreenTransitionMap.STATE_MENU, true));
    }

    @Test
    public void menuToPlayerIsSlideUp() {
        assertEquals(ScreenTransitionMap.Kind.SLIDE_UP,
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_MENU,
                        ScreenTransitionMap.STATE_PLAYER, false));
    }

    @Test
    public void playerToMenuIsSlideDown() {
        assertEquals(ScreenTransitionMap.Kind.SLIDE_DOWN,
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_PLAYER,
                        ScreenTransitionMap.STATE_MENU, true));
    }

    @Test
    public void playerToFlowDelegatesHandoff() {
        assertEquals(ScreenTransitionMap.Kind.DELEGATE_FLOW,
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_PLAYER,
                        ScreenTransitionMap.STATE_FLOW, true));
    }

    @Test
    public void flowToPlayerDelegatesHandoff() {
        assertEquals(ScreenTransitionMap.Kind.DELEGATE_FLOW,
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_FLOW,
                        ScreenTransitionMap.STATE_PLAYER, false));
    }

    @Test
    public void flowToMenuBackIsPopBack() {
        assertEquals(ScreenTransitionMap.Kind.NONE,
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_FLOW,
                        ScreenTransitionMap.STATE_MENU, false));
        assertEquals(ScreenTransitionMap.Kind.POP_BACK,
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_FLOW,
                        ScreenTransitionMap.STATE_MENU, true));
    }

    @Test
    public void browserSubModesShareRootNoAnimation() {
        assertEquals(ScreenTransitionMap.Kind.NONE,
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_BROWSER,
                        ScreenTransitionMap.STATE_PODCASTS, false));
        assertEquals(ScreenTransitionMap.Kind.NONE,
                ScreenTransitionMap.resolve(MediaSuiteHost.STATE_RADIO,
                        MediaSuiteHost.STATE_VIDEOS, false));
    }

    @Test
    public void settingsToBrowserIsPushForward() {
        assertEquals(ScreenTransitionMap.Kind.PUSH_FORWARD,
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_SETTINGS,
                        ScreenTransitionMap.STATE_BROWSER, false));
    }

    @Test
    public void easeOutQuadEndpoints() {
        assertEquals(0f, ScreenTransition.easeOutQuad(0f), 0.001f);
        assertEquals(1f, ScreenTransition.easeOutQuad(1f), 0.001f);
        assertEquals(0.75f, ScreenTransition.easeOutQuad(0.5f), 0.001f);
    }

    @Test
    public void modalConstants() {
        assertEquals(200, ScreenTransition.MODAL_MS);
    }

    @Test
    public void pushSlideFadeOutgoingFullyTransparent() {
        assertEquals(0f, ScreenTransition.outgoingEndAlpha(), 0.001f);
    }

    @Test
    public void updatedPacingConstants() {
        assertEquals(240, ScreenTransition.PUSH_MS);
        assertEquals(260, ScreenTransition.PLAYER_MS);
    }

    @Test
    public void mechanicalInterpolatorIsMonotonicAndClamped() {
        MechanicalInterpolator i = new MechanicalInterpolator(new float[] {0f, 0.2f, 0.7f, 1f});
        assertEquals(0f, i.getInterpolation(-1f), 0f);
        assertEquals(1f, i.getInterpolation(2f), 0f);
        float previous = 0f;
        for (int n = 0; n <= 100; n++) {
            float value = i.getInterpolation(n / 100f);
            assertTrue(value >= previous);
            previous = value;
        }
    }

    @Test
    public void mechanicalCurvesHaveDistinctMass() {
        float push = ScreenTransition.pushInterpolation(0.5f);
        float player = ScreenTransition.playerInterpolation(0.5f);
        float modal = ScreenTransition.modalInterpolation(0.5f);
        assertTrue(player < push);
        assertTrue(push < modal);
        assertEquals(1f, ScreenTransition.pushInterpolation(1f), 0f);
        assertEquals(1f, ScreenTransition.playerInterpolation(1f), 0f);
        assertEquals(1f, ScreenTransition.modalInterpolation(1f), 0f);
    }
}
