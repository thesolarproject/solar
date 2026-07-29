package com.solar.launcher.ui;

import com.solar.launcher.media.MediaSuiteHost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
                ScreenTransitionMap.resolve(ScreenTransitionMap.STATE_DOWNLOADS,
                        ScreenTransitionMap.STATE_BROWSER, false));
        assertEquals(ScreenTransitionMap.Kind.NONE,
                ScreenTransitionMap.resolve(MediaSuiteHost.STATE_RADIO,
                        MediaSuiteHost.STATE_VIDEOS, false));
        assertEquals(ScreenTransitionMap.Kind.NONE,
                ScreenTransitionMap.resolve(MediaSuiteHost.STATE_VIDEO_HUB,
                        MediaSuiteHost.STATE_YOUTUBE_BROWSE, false));
    }

    @Test
    public void videoHubDepthIsOne() {
        assertEquals(1, ScreenTransitionMap.depth(MediaSuiteHost.STATE_VIDEO_HUB));
        assertEquals(1, ScreenTransitionMap.depth(MediaSuiteHost.STATE_YOUTUBE_BROWSE));
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
        assertEquals(140, ScreenTransition.MODAL_MS);
    }

    @Test
    public void pushSlideFadeOutgoingFullyTransparent() {
        assertEquals(0f, ScreenTransition.outgoingEndAlpha(), 0.001f);
    }

    @Test
    public void updatedPacingConstants() {
        // 2026-07-17 — Snappier push so wheel is live sooner after Back/OK.
        assertEquals(155, ScreenTransition.PUSH_MS);
        assertEquals(175, ScreenTransition.PLAYER_MS);
        assertEquals(140, ScreenTransition.CROSSFADE_MS);
    }
}
