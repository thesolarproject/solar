package com.solar.launcher.ui;

import com.solar.launcher.media.MediaSuiteHost;

/**
 * Resolves iPod-style transition kind from Solar screen state ints.
 * State values mirror {@code MainActivity} / {@link MediaSuiteHost} — keep in sync.
 */
public final class ScreenTransitionMap {

    public static final int STATE_MENU = 1;
    public static final int STATE_BROWSER = 2;
    public static final int STATE_PLAYER = 3;
    public static final int STATE_SETTINGS = 4;
    public static final int STATE_BLUETOOTH = 5;
    public static final int STATE_WIFI = 6;
    public static final int STATE_WIFI_KEYBOARD = 7;
    public static final int STATE_BRIGHTNESS = 8;
    public static final int STATE_STORAGE = 9;
    public static final int STATE_WEBSERVER = 10;
    public static final int STATE_PODCASTS = 11;
    public static final int STATE_SOULSEEK = 12;
    public static final int STATE_APPS = 13;
    public static final int STATE_MORE = 14;
    public static final int STATE_DEEZER = 15;
    public static final int STATE_DEEZER_SETUP = 16;
    public static final int STATE_FLOW = 24;
    public static final int STATE_USB_STORAGE = 25;
    public static final int STATE_NAVIDROME = 26;
    // 2026-07-14: 27–30 owned by MediaSuite (video hub / YouTube / FM player); Plex/Jellyfin follow MainActivity 31/32.
    public static final int STATE_PLEX = 31;
    public static final int STATE_JELLYFIN = 32;
    /** 2026-07-18 — Lalal Stem Player. */
    public static final int STATE_STEM_PLAYER = 33;
    /** 2026-07-19 — 3-deck Mix. */
    public static final int STATE_MIX = 34;
    public static final int STATE_DOWNLOADS = 35;

    public enum Kind {
        /** No root animation — instant or in-place content swap. */
        NONE,
        PUSH_FORWARD,
        POP_BACK,
        SLIDE_UP,
        SLIDE_DOWN,
        CROSSFADE,
        /** Flow ↔ Player — existing handoff / FlowScreenTransition paths. */
        DELEGATE_FLOW
    }

    private ScreenTransitionMap() {}

    public static Kind resolve(int from, int to, boolean isBack) {
        if (from == to) return Kind.NONE;

        if ((from == STATE_FLOW && to == STATE_PLAYER)
                || (from == STATE_PLAYER && to == STATE_FLOW)) {
            return Kind.DELEGATE_FLOW;
        }

        // Flow → home via Back / Go Home — pop like other depth-1 tiles.
        if (from == STATE_FLOW && to == STATE_MENU && isBack) return Kind.POP_BACK;
        if (from == STATE_FLOW && to == STATE_MENU) return Kind.NONE;

        if (to == STATE_PLAYER && from == STATE_MENU) return Kind.SLIDE_UP;
        if (from == STATE_PLAYER && to == STATE_MENU) return Kind.SLIDE_DOWN;

        if (to == STATE_PLAYER && depth(from) == 1 && from != STATE_FLOW) {
            return Kind.PUSH_FORWARD;
        }
        if (from == STATE_PLAYER && depth(to) == 1 && to != STATE_FLOW) {
            return Kind.POP_BACK;
        }

        if (sameBrowserRoot(from) && sameBrowserRoot(to) && from != to) {
            return Kind.NONE;
        }

        if (sameAuxRoot(from) && sameAuxRoot(to) && from != to) {
            return Kind.NONE;
        }

        int fromDepth = depth(from);
        int toDepth = depth(to);
        if (isBack || toDepth < fromDepth) return Kind.POP_BACK;
        if (toDepth > fromDepth) return Kind.PUSH_FORWARD;
        return Kind.PUSH_FORWARD;
    }

    /** @visibleForTesting */
    public static int depth(int state) {
        if (state == STATE_MENU) return 0;
        if (state == STATE_PLAYER || state == STATE_STEM_PLAYER || state == STATE_MIX
                || state == STATE_WIFI_KEYBOARD
                || state == MediaSuiteHost.STATE_VIDEO_PLAYER
                || state == MediaSuiteHost.STATE_PHOTO_VIEWER) {
            return 2;
        }
        return 1;
    }

    public static boolean sameBrowserRoot(int state) {
        return state == STATE_BROWSER || state == STATE_PODCASTS || state == STATE_SOULSEEK
                || state == STATE_DEEZER || state == STATE_APPS || state == STATE_MORE
                || state == STATE_DOWNLOADS
                || state == STATE_USB_STORAGE || state == STATE_NAVIDROME
                || state == STATE_PLEX || state == STATE_JELLYFIN
                || state == MediaSuiteHost.STATE_RADIO
                || state == MediaSuiteHost.STATE_RADIO_FM_BROWSE
                || state == MediaSuiteHost.STATE_RADIO_NET_BROWSE
                || state == MediaSuiteHost.STATE_VIDEOS
                || state == MediaSuiteHost.STATE_VIDEO_HUB
                || state == MediaSuiteHost.STATE_YOUTUBE_BROWSE
                || state == MediaSuiteHost.STATE_PHOTOS;
    }

    private static boolean sameAuxRoot(int state) {
        return state == STATE_WEBSERVER || state == STATE_DEEZER_SETUP;
    }

    public static boolean sharesRoot(int a, int b) {
        if (a == b) return true;
        if (sameBrowserRoot(a) && sameBrowserRoot(b)) return true;
        if (a == STATE_SETTINGS && b == STATE_SETTINGS) return true;
        if (sameAuxRoot(a) && sameAuxRoot(b)) return true;
        return false;
    }
}
