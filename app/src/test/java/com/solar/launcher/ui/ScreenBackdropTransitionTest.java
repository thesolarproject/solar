package com.solar.launcher.ui;

import android.widget.ImageView;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScreenBackdropTransitionTest {

    @Test
    public void visuallySameWhenSameBitmapRefs() {
        // Null wall on both sides — same visual (no wallpaper bitmap).
        ScreenBackdropTransition.BackdropSnapshot a =
                new ScreenBackdropTransition.BackdropSnapshot(1, null, null, false);
        ScreenBackdropTransition.BackdropSnapshot b =
                new ScreenBackdropTransition.BackdropSnapshot(2, null, null, false);
        assertTrue(a.visuallySame(b));
    }

    @Test
    public void visuallySameWhenBothDefaultWall() {
        ScreenBackdropTransition.BackdropSnapshot a =
                new ScreenBackdropTransition.BackdropSnapshot(1, null, null, true);
        ScreenBackdropTransition.BackdropSnapshot b =
                new ScreenBackdropTransition.BackdropSnapshot(2, null, null, true);
        assertTrue(a.visuallySame(b));
    }

    @Test
    public void notVisuallySameWhenDefaultDiffers() {
        ScreenBackdropTransition.BackdropSnapshot a =
                new ScreenBackdropTransition.BackdropSnapshot(1, null, null, true);
        ScreenBackdropTransition.BackdropSnapshot b =
                new ScreenBackdropTransition.BackdropSnapshot(1, null, null, false);
        assertFalse(a.visuallySame(b));
    }

    @Test
    public void invalidateCacheClearsEntry() {
        ScreenBackdropTransition.BackdropSnapshot snap =
                new ScreenBackdropTransition.BackdropSnapshot(1, null, null, true);
        ScreenBackdropTransition.Resolver resolver = new ScreenBackdropTransition.Resolver() {
            @Override
            public ScreenBackdropTransition.BackdropSnapshot resolveSnapshot(int screenState) {
                return snap;
            }

            @Override
            public void bindPlayerBlurEarly() {}

            @Override
            public void bindOutgoingPlayerBackdrop(ImageView wall, ImageView mask) {}

            @Override
            public int screenWidthPx() {
                return 480;
            }

            @Override
            public int screenHeightPx() {
                return 360;
            }
        };
        ScreenBackdropTransition.resolveCached(resolver, 1, "key-a");
        ScreenBackdropTransition.invalidateCache();
        // After invalidate, resolveCached should call resolver again — no crash is the check.
        ScreenBackdropTransition.resolveCached(resolver, 1, "key-b");
    }
}
