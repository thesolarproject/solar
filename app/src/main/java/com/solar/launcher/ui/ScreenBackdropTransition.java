package com.solar.launcher.ui;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;

import org.json.JSONObject;

/**
 * Double-buffered wallpaper/mask layer — slides and crossfades in lockstep with root transitions.
 */
public final class ScreenBackdropTransition {

    /** Resolved wall + optional dual-pane mask for one screen state. */
    public static final class BackdropSnapshot {
        public final int screenState;
        public final Bitmap wall;
        public final Bitmap mask;
        public final boolean defaultWall;

        public BackdropSnapshot(int screenState, Bitmap wall, Bitmap mask, boolean defaultWall) {
            this.screenState = screenState;
            this.wall = wall;
            this.mask = mask;
            this.defaultWall = defaultWall;
        }

        /** True when slide would show identical pixels — use alpha crossfade instead. */
        public boolean visuallySame(BackdropSnapshot other) {
            if (other == null) return false;
            if (defaultWall != other.defaultWall) return false;
            if (wall == other.wall && mask == other.mask) return true;
            if (wall == null && other.wall == null && mask == other.mask) return true;
            return false;
        }
    }

    /** MainActivity supplies bitmap resolution and player blur early-bind. */
    public interface Resolver {
        BackdropSnapshot resolveSnapshot(int screenState);
        void bindPlayerBlurEarly();
        /** Mirror live NP blur onto the outgoing backdrop slot when leaving Now Playing. */
        void bindOutgoingPlayerBackdrop(ImageView wall, ImageView mask);
        int screenWidthPx();
        int screenHeightPx();
    }

    private static final class CacheEntry {
        final String key;
        final BackdropSnapshot snapshot;

        CacheEntry(String key, BackdropSnapshot snapshot) {
            this.key = key;
            this.snapshot = snapshot;
        }
    }

    private static CacheEntry snapshotCache;

    private View backdropOutSlot;
    private View backdropInSlot;
    private ImageView wallOut;
    private ImageView maskOut;
    private ImageView wallIn;
    private ImageView maskIn;

    private BackdropSnapshot outgoingSnapshot;
    private BackdropSnapshot incomingSnapshot;
    private boolean crossfadeOnly;
    private boolean active;

    public void attachViews(View backdropOut, View backdropIn,
            ImageView wallOut, ImageView maskOut,
            ImageView wallIn, ImageView maskIn) {
        this.backdropOutSlot = backdropOut;
        this.backdropInSlot = backdropIn;
        this.wallOut = wallOut;
        this.maskOut = maskOut;
        this.wallIn = wallIn;
        this.maskIn = maskIn;
    }

    public boolean isActive() {
        return active;
    }

    /** Drop cached cropped bitmaps when theme or bg prefs change. */
    public static void invalidateCache() {
        snapshotCache = null;
    }

    public static BackdropSnapshot resolveCached(Resolver resolver, int screenState, String cacheKey) {
        if (resolver == null) return null;
        if (snapshotCache != null && cacheKey.equals(snapshotCache.key)) {
            return snapshotCache.snapshot;
        }
        BackdropSnapshot snap = resolver.resolveSnapshot(screenState);
        snapshotCache = new CacheEntry(cacheKey, snap);
        return snap;
    }

    /**
     * Bind incoming slot and optionally early-bind player blur before the first anim frame.
     */
    public void prepareIncoming(Resolver resolver, int from, int to, String fromCacheKey, String toCacheKey) {
        if (wallOut == null || wallIn == null) return;
        outgoingSnapshot = resolveCached(resolver, from, fromCacheKey);
        incomingSnapshot = resolveCached(resolver, to, toCacheKey);

        boolean playerTarget = to == ScreenTransitionMap.STATE_PLAYER;
        boolean playerSource = from == ScreenTransitionMap.STATE_PLAYER;

        // Outgoing slot: NP blur lives in layout_player_mode — mirror it for slide-down exits.
        if (playerSource) {
            resolver.bindOutgoingPlayerBackdrop(wallOut, maskOut);
            if (backdropOutSlot != null) backdropOutSlot.setVisibility(View.VISIBLE);
        } else if (outgoingSnapshot != null) {
            bindSnapshot(wallOut, maskOut, outgoingSnapshot, resolver);
        }

        crossfadeOnly = !playerSource && outgoingSnapshot != null && incomingSnapshot != null
                && outgoingSnapshot.visuallySame(incomingSnapshot);

        if (playerTarget) {
            resolver.bindPlayerBlurEarly();
        }

        if (playerTarget || playerSource) {
            // Player blur lives inside layout_player_mode — global slots only animate the menu/library wall.
            if (playerTarget) {
                hideIncomingSlot();
            } else {
                bindSnapshot(wallIn, maskIn, incomingSnapshot, resolver);
                showIncomingSlot();
            }
        } else if (incomingSnapshot != null) {
            bindSnapshot(wallIn, maskIn, incomingSnapshot, resolver);
            showIncomingSlot();
        } else {
            hideIncomingSlot();
        }

        active = true;
        resetSlotTransforms(backdropOutSlot);
        resetSlotTransforms(backdropInSlot);

        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("from", from);
            d.put("to", to);
            d.put("crossfadeOnly", crossfadeOnly);
            d.put("playerTarget", playerTarget);
            TransitionPerfLog.log("ScreenBackdropTransition.prepareIncoming", "ready", "E", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    public View outSlot() {
        return backdropOutSlot;
    }

    public View inSlot() {
        return crossfadeOnly || backdropInSlot == null || backdropInSlot.getVisibility() != View.VISIBLE
                ? null : backdropInSlot;
    }

    public boolean crossfadeOnly() {
        return crossfadeOnly;
    }

    /** Promote incoming wallpaper to primary views after root transition completes. */
    public void commit(int toState, Resolver resolver) {
        if (wallOut == null) {
            active = false;
            return;
        }
        if (incomingSnapshot != null && toState != ScreenTransitionMap.STATE_PLAYER) {
            bindSnapshot(wallOut, maskOut, incomingSnapshot, resolver);
        } else if (toState == ScreenTransitionMap.STATE_PLAYER) {
            // Player uses iv_player_bg_blur — keep global wall as-is until leaving player.
        } else {
            bindSnapshot(wallOut, maskOut, incomingSnapshot, resolver);
        }
        hideIncomingSlot();
        resetSlotTransforms(backdropOutSlot);
        resetSlotTransforms(backdropInSlot);
        ScreenTransition.clearHardwareLayer(backdropOutSlot);
        ScreenTransition.clearHardwareLayer(backdropInSlot);
        active = false;
        outgoingSnapshot = null;
        incomingSnapshot = null;
    }

    public static void bindSnapshot(ImageView wall, ImageView mask, BackdropSnapshot snap, Resolver resolver) {
        if (wall == null || snap == null) return;
        if (snap.wall != null) {
            wall.setImageBitmap(snap.wall);
        } else if (snap.defaultWall) {
            wall.setImageResource(com.solar.launcher.R.drawable.default_back);
        } else {
            wall.setImageResource(0);
        }
        if (mask != null) {
            if (snap.mask != null) {
                mask.setImageBitmap(snap.mask);
                mask.setVisibility(View.VISIBLE);
                mask.setAlpha(1f);
            } else {
                mask.setVisibility(View.GONE);
            }
        }
    }

    private void showIncomingSlot() {
        if (backdropInSlot == null) return;
        backdropInSlot.setVisibility(View.VISIBLE);
        backdropInSlot.setAlpha(crossfadeOnly ? 0f : 1f);
    }

    private void hideIncomingSlot() {
        if (backdropInSlot == null) return;
        backdropInSlot.setVisibility(View.GONE);
        backdropInSlot.setAlpha(0f);
        if (wallIn != null) wallIn.setImageResource(0);
        if (maskIn != null) maskIn.setVisibility(View.GONE);
    }

    private static void resetSlotTransforms(View slot) {
        if (slot == null) return;
        slot.animate().cancel();
        slot.setTranslationX(0f);
        slot.setTranslationY(0f);
        slot.setAlpha(1f);
    }
}
