package com.solar.launcher.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.solar.launcher.R;
import com.solar.launcher.theme.ThemeManager;

import java.util.List;

/**
 * Apple Cover Flow renderer — 45° carousel, reflections, in-place flip to tracklist.
 */
public final class FlowView extends View {

    public interface Callback {
        void requestCover(int itemIndex, String coverKey, FlowItem item);
        /** Sync disk warm for center ±radius — only when carousel is idle (not scrolling). */
        void warmCarouselCovers(int center, int radius);
        void onFocusIndexChanged(int index);
        void onBackRowSelected(FlowScreenHost.FlowBackRow row, int index);
        void onFlipDismissed();
        /** Choreographer tick — idle thumb bake and perf logging. */
        void onCarouselFrameTick();
        /** One missing flow-thumb per idle frame — returns true if backlog remains. */
        boolean onIdleThumbBakeTick();
        /** In-flight cover decodes — keep anim tick alive until they land in cache. */
        boolean hasPendingCoverDecodes();
        /** Async reflection composite ready — invalidate center slot. */
        void onCoverBakeReady(String bakeKey);
        /** Scroll wheel step — prefetch neighbors (may run while scrolling). */
        void prefetchCarouselCovers(int visualCenter, int radius);
        /** Carousel settled on center — schedule reflection bakes for center ±radius. */
        void scheduleCenterBakes(int center, int radius);
    }

    private final FlowEngine engine = new FlowEngine();
    private final FlowFlipController flip = new FlowFlipController();
    private final Paint coverPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflectionPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint backPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backRowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backSelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint();
    private final RectF drawRect = new RectF();
    private final RectF bakedDestRect = new RectF();
    private final Matrix matrix = new Matrix();
    private final Camera camera = new Camera();
    private final int[] drawOrder = new int[CoverFlowLayout.SIDE_SLIDES * 2 + 1];
    private final float[] drawDepth = new float[CoverFlowLayout.SIDE_SLIDES * 2 + 1];
    private final FlowMarquee titleMarquee = new FlowMarquee();
    private final FlowMarquee subtitleMarquee = new FlowMarquee();
    private final FlowMarquee backTitleMarquee = new FlowMarquee();
    private final FlowMarquee backSubtitleMarquee = new FlowMarquee();
    private final FlowMarquee backRowMarquee = new FlowMarquee();
    private FlowCoverCache cache;
    private final FlowCoverBakeCache bakeCache = new FlowCoverBakeCache();
    private List<FlowItem> items = java.util.Collections.emptyList();
    private Callback callback;
    private int reflectionTint = 0x44FFFFFF;
    /** Debug: skip floor reflection draws — compare carousel perf on device. */
    private boolean noReflections;
    private int lastNotifiedFocus = -1;
    private float viewW;
    private float viewH;
    private CoverFlowLayout.Metrics cachedMetrics;
    private float cachedMetricsW;
    private float cachedMetricsH;
    private int deferredFocusNotify = -1;
    private int deferredPrefetchCenter = -1;
    private float titleAlpha = 1f;
    private int titleFadeFrom = -1;
    private int titleFadeTo = -1;
    private long titleFadeStartMs;
    private static final int TITLE_FADE_MS = 150;
    /** Skip 3D carousel draws while reverse handoff keeps Flow layout at alpha 0. */
    private boolean handoffDrawSuppressed;
    /** True while reverse reveal progress drives chrome alpha. */
    private boolean handoffRevealActive;
    /** NP→Flow pinned art — center slot never shows empty placeholder while set. */
    private String handoffPinKey;
    private Bitmap handoffPinBitmap;
    /** Center cover alpha during reverse landing crossfade (flyer hands off here). */
    private float handoffCenterRevealAlpha = 1f;
    /** Side carousel covers fade in during reverse background crossfade. */
    private float handoffSideRevealAlpha = 1f;
    /** Floor reflection fades in after landing — Classipod-style ease-in. */
    private float handoffReflectRevealAlpha = 1f;
    private long handoffReflectRevealStartMs;
    private static final int REFLECT_REVEAL_MS = 150;
    private long handoffTitleRevealStartMs;
    /** Debug session — log first few carousel frames only. */
    private int debugDrawFramesLeft;
    private boolean debugBackFaceLogged;
    /** Throttle cover-reflection debug NDJSON — center + one side slot per burst. */
    private long debugReflectLogMs;
    private long lastScrollWheelLogMs;
    /** Coalesce anim ticks when frames arrive faster than display can show. */
    private long lastAnimInvalidateMs;
    /** Front-face title marquee — invalidate loop while text overflows clip. */
    private final Runnable frontMarqueeTick = new Runnable() {
        @Override
        public void run() {
            int s = flip.getState();
            if (s == FlowFlipController.STATE_BACK
                    || s == FlowFlipController.STATE_FLIP_TO_BACK) return;
            if (titleAlpha < 0.01f) return;
            postInvalidateOnAnimation();
            postDelayed(this, 48);
        }
    };

    private final Runnable animTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            engine.tick(now);
            boolean scrolling = engine.isAnimating();
            boolean flipping = flip.tick(now);
            tickTitleFade(now);
            tickHandoffTitleReveal(now);
            tickHandoffReflectReveal(now);
            if (!engine.isCarouselScrolling()) {
                flushDeferredCarouselSideEffects();
                if (guidedScrollTarget >= 0) {
                    advanceGuidedScrollStep();
                }
            }
            notifyFocusIfChanged();
            if (callback != null) callback.onCarouselFrameTick();
            boolean pendingCovers = callback != null && callback.hasPendingCoverDecodes();
            boolean needsFrame = scrolling || flipping
                    || flip.getState() == FlowFlipController.STATE_FLIP_TO_BACK
                    || flip.getState() == FlowFlipController.STATE_FLIP_TO_FRONT
                    || titleFadeFrom >= 0
                    || handoffTitleRevealStartMs > 0L
                    || handoffRevealActive
                    || handoffReflectRevealStartMs > 0L
                    || guidedScrollTarget >= 0
                    || guidedScrollPendingComplete != null
                    || pendingCovers;
            if (needsFrame) {
                // Under load, skip redundant invalidates — state still advances via wall clock.
                long sinceDraw = now - lastAnimInvalidateMs;
                boolean handoffActive = com.solar.launcher.flow.FlowPlayerHandoff.isHandoffAnimating();
                if (handoffActive || sinceDraw >= 14L || scrolling || flipping) {
                    postInvalidateOnAnimation();
                    lastAnimInvalidateMs = now;
                }
                postOnAnimation(this);
            }
        }
    };

    public FlowView(Context context) {
        this(context, null);
    }

    public FlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initFlowView();
        bakeCache.setListener(new FlowCoverBakeCache.BakeListener() {
            @Override
            public void onBaked(String bakeKey) {
                postInvalidateOnAnimation();
                if (callback != null) callback.onCoverBakeReady(bakeKey);
            }
        });
    }

    private void initFlowView() {
        coverPaint.setFilterBitmap(false);
        reflectionPaint.setFilterBitmap(false);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(ThemeManager.getCustomFont());
        backPaint.setColor(0xCC1A1A22);
        backRowPaint.setColor(0xFFFFFFFF);
        backRowPaint.setTextAlign(Paint.Align.LEFT);
        backSelPaint.setColor(0xFF0066CC);
        placeholderPaint.setColor(ThemeManager.getListButtonNormalBg() | 0xFF000000);
        setBackgroundColor(0xFF000000);
        setFocusable(true);
        reflectionTint = ThemeManager.getListButtonNormalBg() & 0x00FFFFFF | 0x55000000;
    }

    public FlowEngine engine() {
        return engine;
    }

    public FlowFlipController flipController() {
        return flip;
    }

    public void setCoverCache(FlowCoverCache cache) {
        this.cache = cache;
    }

    public void setItems(List<FlowItem> items) {
        this.items = items != null ? items : java.util.Collections.<FlowItem>emptyList();
        engine.setItemCount(this.items.size());
        lastNotifiedFocus = -1;
        deferredFocusNotify = -1;
        deferredPrefetchCenter = -1;
        flip.reset();
        cancelGuidedScroll();
        debugDrawFramesLeft = 3;
        debugBackFaceLogged = false;
        if (!this.items.isEmpty()) {
            postOnAnimation(animTick);
        }
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setDebugTheme(boolean on) {
        reflectionTint = on
                ? (ThemeManager.getStatusBarBackgroundColor() & 0x00FFFFFF) | 0x55000000
                : (ThemeManager.getListButtonNormalBg() & 0x00FFFFFF) | 0x55000000;
        invalidate();
    }

    /** Debug menu: draw covers only — no mirrored floor band. */
    public void setNoReflections(boolean on) {
        if (noReflections == on) return;
        noReflections = on;
        bakeCache.clear();
        if (!on && items != null && !items.isEmpty()) {
            scheduleBakesAround(engine.getFocusIndex(), 2);
        }
        invalidate();
    }

    public boolean isNoReflections() {
        return noReflections;
    }

    /**
     * Measured NP→Flow prep — hide duplicate center draw only; labels and side covers stay visible.
     */
    public void prepareHandoffFlyerOnly() {
        handoffDrawSuppressed = false;
        handoffRevealActive = false;
        handoffTitleRevealStartMs = 0L;
        handoffReflectRevealStartMs = 0L;
        if (!FlowPlayerHandoff.isHandoffAnimating()) {
            handoffCenterRevealAlpha = 0f;
        }
    }

    /**
     * Unmeasured reverse fallback — hide album labels; carousel keeps drawing (pinned center art).
     */
    public void prepareHandoffHidden() {
        handoffDrawSuppressed = false;
        handoffRevealActive = false;
        handoffTitleRevealStartMs = 0L;
        handoffReflectRevealStartMs = 0L;
        // Don't zero landing crossfade mid-reverse — flyer→carousel handshake would flash.
        if (!FlowPlayerHandoff.isHandoffAnimating()) {
            handoffCenterRevealAlpha = 0f;
        }
        handoffSideRevealAlpha = 0f;
        handoffReflectRevealAlpha = 0f;
        titleAlpha = 0f;
        titleFadeFrom = -1;
    }

    public void setHandoffPin(String coverKey, Bitmap bitmap) {
        handoffPinKey = coverKey;
        handoffPinBitmap = bitmap;
    }

    public void clearHandoffPin() {
        handoffPinKey = null;
        handoffPinBitmap = null;
    }

    private boolean centerCoverReadyForReveal() {
        if (handoffPinBitmap != null && !handoffPinBitmap.isRecycled()) return true;
        int idx = engine.getVisualCenterIndex();
        if (idx < 0 || idx >= items.size() || cache == null) return false;
        FlowItem item = items.get(idx);
        if (item == null || item.coverKey == null) return false;
        Bitmap b = cache.get(item.coverKey);
        return b != null && !b.isRecycled();
    }

    /** Debug session — center slot has bitmap before fade completes. */
    boolean centerCoverReadyForRevealDebug() {
        return centerCoverReadyForReveal();
    }

    private Bitmap coverBitmapForDraw(int index, FlowItem item, Bitmap cached) {
        if (cached != null && !cached.isRecycled()) return cached;
        boolean isVisualCenter = index == engine.getVisualCenterIndex();
        if (!isVisualCenter || handoffPinBitmap == null || handoffPinBitmap.isRecycled()) {
            return cached;
        }
        if (handoffPinKey == null || handoffPinKey.isEmpty()
                || handoffPinKey.equals(item.coverKey)) {
            return handoffPinBitmap;
        }
        return cached;
    }

    /** Drive center cover alpha during reverse landing crossfade. */
    public void setHandoffCenterRevealAlpha(float alpha) {
        handoffCenterRevealAlpha = Math.max(0f, Math.min(1f, alpha));
        postInvalidateOnAnimation();
    }

    /** Side carousel covers fade in while NP chrome fades out. */
    public void setHandoffSideRevealAlpha(float alpha) {
        handoffSideRevealAlpha = Math.max(0f, Math.min(1f, alpha));
        postInvalidateOnAnimation();
    }

    /** Reverse chrome stagger start — labels hidden; carousel already visible under flyer. */
    public void beginHandoffReveal() {
        handoffRevealActive = true;
        handoffDrawSuppressed = false;
        handoffTitleRevealStartMs = 0L;
        titleAlpha = 0f;
    }

    /**
     * Stagger album title fade during reverse handoff — artwork stays on the flyer overlay.
     */
    public void setHandoffRevealProgress(float eased) {
        handoffRevealActive = eased < 1f;
        handoffDrawSuppressed = false;
        float labelT = Math.max(0f, (eased - 0.2f) / 0.8f);
        titleAlpha = labelT * (2f - labelT);
        postInvalidateOnAnimation();
    }

    /** Chrome labels done — pin/reflection handled in {@link #finishHandoffLanding()}. */
    public void finishHandoffChromeReveal() {
        handoffRevealActive = false;
        handoffDrawSuppressed = false;
        handoffTitleRevealStartMs = 0L;
        titleAlpha = 1f;
        invalidate();
    }

    /** Flyer removed — clear pin, schedule bakes, fade floor reflection in. */
    public void finishHandoffLanding() {
        handoffCenterRevealAlpha = 1f;
        handoffSideRevealAlpha = 1f;
        titleAlpha = 1f;
        clearHandoffPin();
        handoffReflectRevealStartMs = System.currentTimeMillis();
        handoffReflectRevealAlpha = 0f;
        invalidate();
        scheduleBakesAround(engine.getFocusIndex(), 2);
        postOnAnimation(animTick);
    }

    public void finishHandoffReveal() {
        finishHandoffChromeReveal();
        finishHandoffLanding();
    }

    /** @deprecated use {@link #beginHandoffReveal()} */
    public void beginHandoffTitleReveal() {
        beginHandoffReveal();
    }

    private void tickHandoffReflectReveal(long nowMs) {
        if (handoffReflectRevealStartMs <= 0L) return;
        float t = (nowMs - handoffReflectRevealStartMs) / (float) REFLECT_REVEAL_MS;
        if (t >= 1f) {
            handoffReflectRevealStartMs = 0L;
            handoffReflectRevealAlpha = 1f;
        } else {
            handoffReflectRevealAlpha = t * t;
        }
    }

    private void tickHandoffTitleReveal(long nowMs) {
        if (handoffTitleRevealStartMs <= 0L) return;
        float t = (nowMs - handoffTitleRevealStartMs) / (float) TITLE_FADE_MS;
        if (t >= 1f) {
            handoffTitleRevealStartMs = 0L;
            titleAlpha = 1f;
        } else {
            titleAlpha = t;
        }
    }

    public void resetFlip() {
        flip.reset();
        invalidate();
    }

    public FlowItem itemAt(int index) {
        if (index < 0 || index >= items.size()) return null;
        return items.get(index);
    }

    private int pendingCarouselDelta;
    /** Back-from-NP: scroll carousel to the playing album before forward handoff. */
    private int guidedScrollTarget = -1;
    private Runnable guidedScrollComplete;
    /** Deferred after last step — cleared by {@link #cancelGuidedScroll()}. */
    private Runnable guidedScrollPendingComplete;
    /** Optional tick haptic while NP-back guided scroll steps the carousel. */
    private Runnable guidedScrollStepFeedback;
    private long guidedScrollStartMs;

    public boolean isGuidedScrolling() {
        return guidedScrollTarget >= 0 || guidedScrollPendingComplete != null;
    }

    public void cancelGuidedScroll() {
        guidedScrollTarget = -1;
        guidedScrollComplete = null;
        guidedScrollPendingComplete = null;
        guidedScrollStepFeedback = null;
        guidedScrollStartMs = 0L;
    }

    /**
     * Wheel-style scroll to {@code targetIndex}, one album per step — same physics as user scroll.
     * {@code onComplete} runs on the UI thread when the target is centered and idle.
     */
    public void animateScrollToIndex(int targetIndex, Runnable onComplete) {
        animateScrollToIndex(targetIndex, onComplete, null);
    }

    /**
     * @param onStep optional haptic/click each time the carousel advances one album toward target
     */
    public void animateScrollToIndex(int targetIndex, Runnable onComplete, Runnable onStep) {
        if (items.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        int clamped = Math.max(0, Math.min(items.size() - 1, targetIndex));
        guidedScrollTarget = clamped;
        guidedScrollComplete = onComplete;
        guidedScrollStepFeedback = onStep;
        guidedScrollStartMs = System.currentTimeMillis();
        advanceGuidedScrollStep();
        postAnimTick();
    }

    /**
     * NP-back handoff — zip to one album shy of target, then one wheel scroll into morph.
     * Avoids slow multi-step scroll with per-step haptics.
     */
    public void animateScrollToIndexForHandoff(int targetIndex, Runnable onComplete) {
        if (items.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        int clamped = Math.max(0, Math.min(items.size() - 1, targetIndex));
        int focus = engine.isAnimating()
                ? engine.getVisualCenterIndex()
                : engine.getFocusIndex();
        if (focus == clamped && !engine.isCarouselScrolling()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        int remaining = Math.abs(clamped - focus);
        if (remaining > 1) {
            int sign = clamped > focus ? 1 : -1;
            engine.setFocusIndex(clamped - sign);
            notifyFocusIfChanged();
            prefetchAround(engine.getFocusIndex());
            snapCarouselToVisualCenter();
        }
        guidedScrollTarget = clamped;
        guidedScrollComplete = onComplete;
        guidedScrollStepFeedback = null;
        guidedScrollStartMs = System.currentTimeMillis();
        advanceGuidedScrollStep();
        postAnimTick();
    }

    private void advanceGuidedScrollStep() {
        if (guidedScrollTarget < 0) return;
        if (engine.isCarouselScrolling()) return;
        int focus = engine.getFocusIndex();
        if (focus == guidedScrollTarget) {
            finishGuidedScrollToHandoff();
            return;
        }
        int remaining = Math.abs(guidedScrollTarget - focus);
        long budgetLeft = FlowGuidedScrollBudget.MAX_MS
                - (System.currentTimeMillis() - guidedScrollStartMs);
        int sign = guidedScrollTarget > focus ? 1 : -1;
        int stepDelta = FlowGuidedScrollBudget.stepDelta(remaining, budgetLeft);
        if (guidedScrollStepFeedback != null) guidedScrollStepFeedback.run();
        if (stepDelta > 1) {
            engine.setFocusIndex(focus + sign * stepDelta);
            notifyFocusIfChanged();
            prefetchAround(engine.getFocusIndex());
        } else {
            // Always wheel-scroll single steps near target — chains into forward NP handoff.
            engine.scrollBy(sign);
        }
        postInvalidateOnAnimation();
        postAnimTick();
    }

    /** Target centered and idle — snap pose, then run handoff on next vsync. */
    private void finishGuidedScrollToHandoff() {
        guidedScrollTarget = -1;
        guidedScrollStepFeedback = null;
        guidedScrollStartMs = 0L;
        notifyFocusIfChanged();
        final Runnable done = guidedScrollComplete;
        guidedScrollComplete = null;
        if (done == null) return;
        guidedScrollPendingComplete = done;
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (engine.isCarouselScrolling()) {
                    postOnAnimation(this);
                    return;
                }
                snapCarouselToVisualCenter();
                Runnable handoff = guidedScrollPendingComplete;
                guidedScrollPendingComplete = null;
                if (handoff != null) handoff.run();
            }
        });
    }

    public boolean scrollWheel(int delta) {
        if (guidedScrollTarget >= 0 || guidedScrollPendingComplete != null) return false;
        if (flip.getState() == FlowFlipController.STATE_BACK) {
            boolean moved = flip.scrollBackBy(delta);
            if (!moved) {
                flipToFrontThenScroll(delta);
                return true;
            }
            invalidate();
            return true;
        }
        if (flip.blocksCarouselScroll()) return false;
        engine.setViewport(viewW, viewH);
        boolean moved = engine.scrollByReturningMoved(delta);
        if (moved) {
            if (engine.isCarouselScrolling()) {
                deferredPrefetchCenter = engine.getFocusIndex();
                if (callback != null) {
                    callback.prefetchCarouselCovers(engine.getVisualCenterIndex(), 3);
                }
                // #region agent log
                if (com.solar.launcher.DebugSessionLog.ENABLED) {
                    long now = System.currentTimeMillis();
                    if (now - lastScrollWheelLogMs >= 300L) {
                        lastScrollWheelLogMs = now;
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("visualCenter", engine.getVisualCenterIndex());
                            d.put("prefetchRadius", 3);
                            com.solar.launcher.DebugSessionLog.log("FlowView.scrollWheel",
                                    "scroll prefetch", "H3", d);
                        } catch (Exception ignored) {}
                    }
                }
                // #endregion
            } else {
                prefetchAround(engine.getFocusIndex());
            }
            invalidate();
            postAnimTick();
        }
        return moved;
    }

    public void flipToBack(String title, String subtitle, List<FlowScreenHost.FlowBackRow> rows) {
        flip.setBackContent(title, subtitle, rows);
        flip.startFlipToBack(null);
        invalidate();
        postAnimTick();
    }

    /** Restore flipped tracklist after Now Playing (no flip animation). */
    public void restoreBackFace(FlowFlipController.Snapshot snap) {
        if (snap == null) {
            flip.reset();
        } else {
            flip.restoreSnapshot(snap);
        }
        invalidate();
    }

    /** Restore flipped tracklist with short flip-in after screen crossfade. */
    public void restoreBackFaceAnimated(FlowFlipController.Snapshot snap) {
        if (snap == null) {
            flip.reset();
        } else {
            flip.animateRestoreSnapshot(snap);
            postAnimTick();
        }
        invalidate();
    }

    private void flipToFrontThenScroll(final int delta) {
        pendingCarouselDelta = delta;
        if (flip.getState() != FlowFlipController.STATE_BACK) {
            applyPendingCarouselScroll();
            return;
        }
        flip.startFlipToFront(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onFlipDismissed();
                applyPendingCarouselScroll();
            }
        });
        invalidate();
        postAnimTick();
    }

    private void applyPendingCarouselScroll() {
        int delta = pendingCarouselDelta;
        pendingCarouselDelta = 0;
        if (delta == 0) return;
        engine.setViewport(viewW, viewH);
        if (engine.scrollByReturningMoved(delta)) {
            if (engine.isCarouselScrolling()) {
                deferredPrefetchCenter = engine.getFocusIndex();
                if (callback != null) {
                    callback.prefetchCarouselCovers(engine.getVisualCenterIndex(), 3);
                }
            } else {
                prefetchAround(engine.getFocusIndex());
            }
            postAnimTick();
        }
        invalidate();
    }

    public boolean flipToFront() {
        if (!flip.isFlippedOrFlipping()) return false;
        if (flip.getState() == FlowFlipController.STATE_BACK
                || flip.getState() == FlowFlipController.STATE_FLIP_TO_BACK) {
            flip.startFlipToFront(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) callback.onFlipDismissed();
                }
            });
            invalidate();
            postAnimTick();
            return true;
        }
        return false;
    }

    public boolean isFlipped() {
        return flip.getState() == FlowFlipController.STATE_BACK
                || flip.getState() == FlowFlipController.STATE_FLIP_TO_BACK;
    }

    public boolean handleCenterOk() {
        if (flip.getState() == FlowFlipController.STATE_BACK) {
            FlowScreenHost.FlowBackRow row = flip.selectedRow();
            if (row != null && callback != null) {
                callback.onBackRowSelected(row, flip.backIndex());
            }
            return true;
        }
        return false;
    }

    /** Screen rect of center cover for handoff animation. */
    public RectF getCenterCoverScreenRect() {
        return getHandoffCoverScreenRect();
    }

    /** Square center cover — handoff landing pose and carousel front face. */
    public RectF getHandoffCoverScreenRect() {
        FlowEngine.SlotTransform t = engine.centerSlotTransform(viewW, viewH);
        return FlowAlbumArt3d.screenRectFromSlot(this, t);
    }

    /** Visible back-face screen rect when flipped — handoff start / reverse target. */
    public RectF getHandoffFromScreenRect() {
        if (!flip.isFlippedOrFlipping() || viewW <= 0f || viewH <= 0f) {
            return getHandoffCoverScreenRect();
        }
        CoverFlowLayout.Metrics metrics = viewportMetrics();
        FlowEngine.SlotTransform t = centerTransformWithBackEncroach(metrics, 1f);
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        float halfW = t.width * 0.5f;
        float halfH = t.height * 0.5f;
        return new RectF(
                loc[0] + t.centerX - halfW,
                loc[1] + t.centerY - halfH,
                loc[0] + t.centerX + halfW,
                loc[1] + t.centerY + halfH);
    }

    private FlowEngine.SlotTransform centerTransformWithBackEncroach(
            CoverFlowLayout.Metrics metrics, float flipPastMid) {
        FlowEngine.SlotTransform t = engine.centerSlotTransform(metrics.viewW, metrics.viewH);
        if (flipPastMid > 0f) {
            float density = getResources().getDisplayMetrics().density;
            float topInset = getResources().getDimension(R.dimen.y1_status_bar_height) + 4f * density;
            float bottomMargin = CoverFlowLayout.BACK_FACE_BOTTOM_MARGIN_DP * density;
            CoverFlowLayout.applyBackFaceSouthEncroach(t, metrics, flipPastMid, topInset, bottomMargin);
        }
        return t;
    }

    /** Y rotation of center cover at handoff start (0 = front, 180 = flipped back). */
    public float getHandoffStartRotationY() {
        if (!flip.isFlippedOrFlipping()) return 0f;
        float flipP = flip.flipProgress();
        if (flip.getState() == FlowFlipController.STATE_BACK
                || flip.getState() == FlowFlipController.STATE_HANDOFF) {
            return 180f;
        }
        return flipP * 180f;
    }

    public Bitmap getCenterCoverBitmap() {
        int focus = engine.getVisualCenterIndex();
        if (focus < 0 || focus >= items.size() || cache == null) return null;
        return cache.get(items.get(focus).coverKey);
    }

    /** Snap carousel to on-screen center cover before flip / handoff. */
    public void snapCarouselToVisualCenter() {
        engine.snapToVisualCenter();
        notifyFocusIfChanged();
        invalidate();
    }

    private CoverFlowLayout.Metrics viewportMetrics() {
        if (cachedMetrics == null || viewW != cachedMetricsW || viewH != cachedMetricsH) {
            cachedMetrics = CoverFlowLayout.metricsForViewport(viewW, viewH);
            cachedMetricsW = viewW;
            cachedMetricsH = viewH;
            engine.setViewportMetrics(cachedMetrics, viewW, viewH);
            bakeCache.clear();
        }
        return cachedMetrics;
    }

    private void flushDeferredCarouselSideEffects() {
        if (deferredPrefetchCenter >= 0) {
            int center = deferredPrefetchCenter;
            deferredPrefetchCenter = -1;
            prefetchAround(center);
            if (cache != null) cache.evictFarFrom(center, items);
            if (callback != null) callback.scheduleCenterBakes(center, 2);
        }
        if (deferredFocusNotify >= 0 && callback != null) {
            int idx = deferredFocusNotify;
            deferredFocusNotify = -1;
            callback.onFocusIndexChanged(idx);
        }
    }

    public void prefetchAround(int center) {
        if (callback == null || cache == null) return;
        if (!engine.isCarouselScrolling()) {
            callback.warmCarouselCovers(center, 2);
        }
        for (int d = -3; d <= 3; d++) {
            int idx = center + d;
            if (idx < 0 || idx >= items.size()) continue;
            FlowItem item = items.get(idx);
            if (item == null || item.coverKey == null) continue;
            if (cache.get(item.coverKey) == null) {
                callback.requestCover(idx, item.coverKey, item);
            }
        }
    }

    private int thumbPx() {
        if (cache != null) return cache.thumbSizePx(viewW, viewH);
        return CoverFlowLayout.metricsForViewport(viewW, viewH).thumbSizePx();
    }

    private void notifyFocusIfChanged() {
        int focus = engine.isAnimating() ? engine.getVisualCenterIndex() : engine.getFocusIndex();
        if (focus != lastNotifiedFocus) {
            if (lastNotifiedFocus >= 0 && focus != lastNotifiedFocus) {
                titleFadeFrom = lastNotifiedFocus;
                titleFadeTo = focus;
                titleFadeStartMs = System.currentTimeMillis();
                titleAlpha = 0f;
            }
            lastNotifiedFocus = focus;
            if (engine.isCarouselScrolling()) {
                deferredFocusNotify = focus;
                deferredPrefetchCenter = focus;
            } else {
                prefetchAround(focus);
                if (callback != null) callback.onFocusIndexChanged(focus);
            }
        }
    }

    private void tickTitleFade(long nowMs) {
        if (titleFadeFrom < 0) {
            titleAlpha = 1f;
            return;
        }
        float t = (nowMs - titleFadeStartMs) / (float) TITLE_FADE_MS;
        if (t >= 1f) {
            titleFadeFrom = -1;
            titleAlpha = 1f;
        } else {
            titleAlpha = t;
        }
    }

    private void postAnimTick() {
        removeCallbacks(animTick);
        post(animTick);
    }

    /** Coalesced choreographer tick — idle cover decodes and scroll share one loop. */
    public void scheduleBakesAround(int center, int radius) {
        if (cache == null || items.isEmpty()) return;
        CoverFlowLayout.Metrics m = viewportMetrics();
        int n = items.size();
        for (int d = -radius; d <= radius; d++) {
            int idx = center + d;
            if (idx < 0 || idx >= n) continue;
            FlowItem item = items.get(idx);
            if (item == null || item.coverKey == null || item.coverKey.isEmpty()) continue;
            Bitmap bmp = cache.get(item.coverKey);
            if (bmp == null || bmp.isRecycled()) continue;
            if (bakeCache.peek(item.coverKey, m, true) != null) continue;
            bakeCache.scheduleBake(item.coverKey, bmp, m, true);
        }
    }

    public void scheduleCarouselFrame() {
        postAnimTick();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;
        engine.setViewport(viewW, viewH);
        cachedMetrics = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // #region agent log
        final boolean perfLog = com.solar.launcher.DebugSessionLog.ENABLED;
        final long drawStartMs = perfLog ? System.currentTimeMillis() : 0L;
        // #endregion
        super.onDraw(canvas);
        if (handoffDrawSuppressed) return;
        float w = getWidth();
        float h = getHeight();
        if (w != viewW || h != viewH) {
            viewW = w;
            viewH = h;
            engine.setViewport(w, h);
        }
        if (w <= 0 || h <= 0 || items.isEmpty()) {
            drawEmptyHint(canvas, w, h);
            return;
        }

        // #region agent log
        if (perfLog && debugDrawFramesLeft > 0) {
            debugDrawFramesLeft--;
            try {
                int centerIdx = engine.getFocusIndex();
                FlowItem center = centerIdx >= 0 && centerIdx < items.size() ? items.get(centerIdx) : null;
                String key = center != null ? center.coverKey : "";
                boolean hasBmp = cache != null && key != null && cache.get(key) != null;
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("itemCount", items.size());
                d.put("centerIdx", centerIdx);
                d.put("centerCoverCached", hasBmp);
                d.put("flipped", flip.isFlippedOrFlipping());
                com.solar.launcher.DebugSessionLog.log("FlowView.onDraw", "carousel frame", "H4-H5", d);
            } catch (Exception ignored) {}
        }
        // #endregion

        CoverFlowLayout.Metrics metrics = viewportMetrics();
        int count = engine.fillDrawOrder(drawOrder, drawDepth);
        int centerIdx = engine.getVisualCenterIndex();
        float flipP = flip.flipProgress();

        for (int k = 0; k < count; k++) {
            int idx = drawOrder[k];
            if (idx == centerIdx && flip.isFlippedOrFlipping()) {
                drawFlippingCenter(canvas, idx, metrics, flipP);
            } else {
                // ponytail: one dim factor via sideDimAlpha — art + reflection share t.alpha in drawCoverAt.
                float dim = (flip.isFlippedOrFlipping() && idx != centerIdx)
                        ? flip.sideDimAlpha() : 1f;
                drawSlot(canvas, idx, metrics, dim, flipP, centerIdx);
            }
        }

        if (!flip.isFlippedOrFlipping() || flip.getState() == FlowFlipController.STATE_FLIP_TO_FRONT) {
            int focus = engine.getVisualCenterIndex();
            if (focus >= 0 && focus < items.size()) {
                FlowItem item = items.get(focus);
                drawTitle(canvas, item.title, item.subtitle, w, h, metrics);
            }
        } else if (flip.getState() == FlowFlipController.STATE_BACK) {
            scheduleBackFaceMarqueeTick();
        }
        // #region agent log
        if (drawStartMs > 0L) {
            logDrawMsIfSlow(drawStartMs);
        }
        // #endregion
    }

    private void logDrawMsIfSlow(long drawStartMs) {
        if (drawStartMs <= 0L || !com.solar.launcher.DebugSessionLog.ENABLED) return;
        long drawMs = System.currentTimeMillis() - drawStartMs;
        if (drawMs > 16L) {
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("drawMs", drawMs);
                d.put("itemCount", items.size());
                d.put("scrolling", engine.isCarouselScrolling());
                d.put("pendingDecodes", callback != null && callback.hasPendingCoverDecodes());
                com.solar.launcher.DebugSessionLog.log(
                        "FlowView.onDraw", "slow carousel draw", "H-PERF", d);
            } catch (Exception ignored) {}
        }
    }

    private void scheduleFrontFaceMarqueeTick() {
        removeCallbacks(frontMarqueeTick);
        postDelayed(frontMarqueeTick, 48);
    }

    private void stopFrontFaceMarqueeTick() {
        removeCallbacks(frontMarqueeTick);
    }

    private void scheduleBackFaceMarqueeTick() {
        if (flip.getState() == FlowFlipController.STATE_BACK) {
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (flip.getState() == FlowFlipController.STATE_BACK) invalidate();
                }
            }, 48);
        }
    }

    private void drawSlot(Canvas canvas, int index, CoverFlowLayout.Metrics metrics,
            float alphaMul, float flipP, int centerIdx) {
        FlowEngine.SlotTransform t = engine.slotTransform(index, metrics.viewW, metrics.viewH, 0f);
        if (t.alpha <= 0.01f) return;
        if (flipP > 0f && index != centerIdx) {
            CoverFlowLayout.SlidePose pose = engine.poseForItemPublic(index);
            CoverFlowLayout.applyFlipFanOut(t, pose != null ? pose.cx : 0f, flipP);
        }
        t.alpha *= alphaMul;
        drawCoverAt(canvas, index, t, metrics, 0f, false, 1f);
    }

    private void drawFlippingCenter(Canvas canvas, int index, CoverFlowLayout.Metrics metrics, float flipP) {
        float angle = flipP * 180f;
        boolean showBack = angle >= 90f;
        float drawAngle = showBack ? angle - 180f : angle;
        // ponytail: south encroach after midpoint — tall tracklist card; handoff morphs back to square.
        float flipPastMid = flipP > 0.5f ? (flipP - 0.5f) * 2f : 0f;
        float encroach = showBack ? 1f : flipPastMid;
        FlowEngine.SlotTransform t = centerTransformWithBackEncroach(metrics, encroach);
        drawCoverAt(canvas, index, t, metrics, drawAngle, showBack, 1f);
    }

    private void drawCoverAt(Canvas canvas, int index, FlowEngine.SlotTransform t,
            CoverFlowLayout.Metrics metrics, float extraRotY, boolean backFace, float enlarge) {
        FlowItem item = items.get(index);
        Bitmap bmp = cache != null ? cache.get(item.coverKey) : null;
        bmp = coverBitmapForDraw(index, item, bmp);
        if (bmp == null && callback != null && !backFace) {
            boolean isVisualCenter = index == engine.getVisualCenterIndex();
            if (!(isVisualCenter && (FlowPlayerHandoff.isHandoffAnimating() || handoffRevealActive)
                    && handoffPinBitmap != null)) {
                callback.requestCover(index, item.coverKey, item);
            }
        }

        float left = t.centerX - t.width * 0.5f;
        float top = t.centerY - t.height * 0.5f;
        drawRect.set(left, top, left + t.width, top + t.height);
        if (enlarge != 1f) {
            float cx = drawRect.centerX();
            float cy = drawRect.centerY();
            float halfW = drawRect.width() * enlarge * 0.5f;
            float halfH = drawRect.height() * enlarge * 0.5f;
            drawRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        }

        canvas.save();
        camera.save();
        camera.translate(0, 0, t.zDepth);
        camera.rotateY(t.rotationYDeg + extraRotY);
        matrix.reset();
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-t.centerX, -t.centerY);
        matrix.postTranslate(t.centerX, t.centerY);
        canvas.concat(matrix);

        if (backFace) {
            drawBackFace(canvas, drawRect);
        } else {
            boolean isVisualCenter = index == engine.getVisualCenterIndex();
            float slotAlpha = t.alpha * coverHandoffAlpha(isVisualCenter);
            if (slotAlpha <= 0f) {
                canvas.restore();
                coverPaint.setAlpha(255);
                return;
            }
            coverPaint.setAlpha((int) (255 * slotAlpha));
            if (bmp != null && !bmp.isRecycled()) {
                // iPod Classic: center cover keeps floor reflection while idle; deferred after handoff.
                boolean skipReflect = shouldSkipCoverReflection(noReflections,
                        FlowPlayerHandoff.isHandoffAnimating(), engine.isCarouselScrolling(),
                        isVisualCenter, handoffReflectRevealAlpha);
                // Scale reflection band with perspective-shrunk cover — baked bitmap is displaySize-native.
                float coverScale = metrics.displaySize > 0f
                        ? drawRect.width() / metrics.displaySize : 1f;
                float reflectH = metrics.reflectHeight * coverScale;
                // #region agent log
                if (com.solar.launcher.DebugSessionLog.ENABLED) {
                    int vCenter = engine.getVisualCenterIndex();
                    boolean logSlot = isVisualCenter || index == vCenter + 1;
                    long nowMs = System.currentTimeMillis();
                    if (logSlot && nowMs - debugReflectLogMs > 400L) {
                        debugReflectLogMs = nowMs;
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("index", index);
                            d.put("visualCenter", vCenter);
                            d.put("isVisualCenter", isVisualCenter);
                            d.put("skipReflect", skipReflect);
                            d.put("drawRectW", drawRect.width());
                            d.put("drawRectH", drawRect.height());
                            d.put("displaySize", metrics.displaySize);
                            d.put("reflectH", skipReflect ? 0 : reflectH);
                            d.put("reflectHRaw", metrics.reflectHeight);
                            d.put("coverScale", coverScale);
                            d.put("tWidth", t.width);
                            d.put("tHeight", t.height);
                            d.put("scrolling", engine.isCarouselScrolling());
                            d.put("handoff", FlowPlayerHandoff.isHandoffAnimating());
                            d.put("path", skipReflect ? "coverOnly" : "reflect");
                            com.solar.launcher.DebugSessionLog.log(
                                    "FlowView.drawCoverAt", "cover draw", "H-A-H-B", d);
                        } catch (Exception ignored) {}
                    }
                }
                // #endregion
                if (skipReflect) {
                    FlowAlbumArt3d.drawCover(canvas, bmp, drawRect, 0f, slotAlpha, coverPaint);
                } else {
                    Bitmap baked = bakeCache.peek(item.coverKey, metrics, true);
                    if (baked != null && !baked.isRecycled()) {
                        bakedDestRect.set(drawRect.left, drawRect.top,
                                drawRect.right, drawRect.top + drawRect.width() + reflectH);
                        canvas.drawBitmap(baked, null, bakedDestRect, coverPaint);
                    } else {
                        FlowAlbumArt3d.drawCoverWithReflection(canvas, bmp, drawRect, 0f, slotAlpha,
                                reflectH, metrics.reflectTable, coverPaint, reflectionPaint);
                        bakeCache.scheduleBake(item.coverKey, bmp, metrics, true);
                    }
                }
            } else {
                if (isVisualCenter && handoffPinBitmap != null && !handoffPinBitmap.isRecycled()
                        && (handoffPinKey == null || handoffPinKey.equals(item.coverKey))) {
                    bmp = handoffPinBitmap;
                }
                if (bmp != null && !bmp.isRecycled()) {
                    FlowAlbumArt3d.drawCover(canvas, bmp, drawRect, 0f, slotAlpha, coverPaint);
                } else {
                Bitmap empty = cache != null
                        ? cache.emptyPlaceholder((int) metrics.displaySize) : null;
                placeholderPaint.setAlpha((int) (255 * slotAlpha));
                if (empty != null && !empty.isRecycled()) {
                    canvas.drawBitmap(empty, null, FlowAlbumArt3d.squareBounds(drawRect), placeholderPaint);
                } else {
                    canvas.drawRect(FlowAlbumArt3d.squareBounds(drawRect), placeholderPaint);
                }
                }
            }
        }
        canvas.restore();
        coverPaint.setAlpha(255);
    }

    private void drawBackFace(Canvas canvas, RectF rect) {
        float density = getResources().getDisplayMetrics().density;
        float pad = rect.width() * 0.05f;
        // Taller gradient header — album title + artist like iPod flipped cover.
        float headerH = rect.height() * 0.22f;
        float menuRowH = getResources().getDimension(R.dimen.y1_menu_item_height);
        float rowH = Math.max(menuRowH * 0.82f, (rect.height() - headerH) / 6f);
        float menuTextPx = getResources().getDimension(R.dimen.y1_menu_text_size);
        float rowTextPx = menuTextPx * Math.min(1f, rect.width() / (480f * density));
        float headerTitlePx = Math.max(rowTextPx * 1.18f, headerH * 0.32f);
        float headerSubPx = Math.max(rowTextPx * 0.88f, headerH * 0.20f);

        int maxFit = Math.max(1, (int) ((rect.height() - headerH) / rowH));
        flip.setMaxVisibleRows(maxFit);

        // #region agent log
        if (!debugBackFaceLogged) {
            debugBackFaceLogged = true;
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("maxFit", maxFit);
                d.put("rowTextPx", rowTextPx);
                d.put("backRows", flip.backRows().size());
                d.put("backIndex", flip.backIndex());
                com.solar.launcher.DebugSessionLog.log("FlowView.drawBackFace", "back face layout", "H5-H6", d);
            } catch (Exception ignored) {}
        }
        // #endregion

        canvas.save();
        canvas.clipRect(rect);

        backPaint.setColor(0xF2E8ECF0);
        canvas.drawRect(rect, backPaint);

        RectF header = new RectF(rect.left, rect.top, rect.right, rect.top + headerH);
        Paint headerGrad = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerGrad.setShader(new LinearGradient(
                header.left, header.top, header.left, header.bottom,
                0xFF4A90D9, 0xFF2B6CB0, Shader.TileMode.CLAMP));
        canvas.drawRect(header, headerGrad);

        backRowPaint.setTextSize(headerTitlePx);
        backRowPaint.setTypeface(Typeface.create(ThemeManager.getCustomFont(), Typeface.BOLD));
        backRowPaint.setColor(0xFFFFFFFF);
        String title = flip.headerTitle() != null ? flip.headerTitle() : "";
        backTitleMarquee.draw(canvas, title, rect.left + pad,
                rect.top + headerH * 0.42f, rect.width() - pad * 2f, backRowPaint);

        String subtitle = flip.headerSubtitle();
        if (subtitle != null && !subtitle.isEmpty()) {
            backRowPaint.setTextSize(headerSubPx);
            backRowPaint.setTypeface(Typeface.create(ThemeManager.getCustomFont(), Typeface.NORMAL));
            backRowPaint.setColor(0xFFE0E8F0);
            backSubtitleMarquee.draw(canvas, subtitle, rect.left + pad,
                    rect.top + headerH * 0.76f, rect.width() - pad * 2f, backRowPaint);
        }

        int count = flip.visibleBackRowCount();
        int start = flip.visibleBackRowStart();
        List<FlowScreenHost.FlowBackRow> rows = flip.backRows();
        backRowPaint.setTypeface(Typeface.create(ThemeManager.getCustomFont(), Typeface.NORMAL));
        backRowPaint.setTextSize(rowTextPx);
        backSelPaint.setColor(0xFF0066CC);
        for (int i = 0; i < count; i++) {
            int rowIdx = start + i;
            if (rowIdx >= rows.size()) break;
            FlowScreenHost.FlowBackRow row = rows.get(rowIdx);
            float y0 = rect.top + headerH + i * rowH;
            RectF rowRect = new RectF(rect.left, y0, rect.right, y0 + rowH);
            if (rowIdx == flip.backIndex()) {
                canvas.drawRect(rowRect, backSelPaint);
                backRowPaint.setColor(0xFFFFFFFF);
            } else {
                backRowPaint.setColor(0xFF1A1A22);
            }
            String line = row.title != null ? row.title : "";
            backRowMarquee.draw(canvas, line, rect.left + pad, y0 + rowH * 0.68f,
                    rect.width() - pad * 2f, backRowPaint);
        }
        backRowPaint.setColor(0xFFFFFFFF);
        canvas.restore();
    }

    private void drawTitle(Canvas canvas, String title, String subtitle, float w, float h,
            CoverFlowLayout.Metrics metrics) {
        // Classic: title in lower reflection floor, below mirrored art fade.
        float titleY = metrics.reflectTop + metrics.reflectHeight * 0.45f;
        float maxW = w * 0.88f;
        float x = w * 0.5f - maxW * 0.5f;
        textPaint.setAlpha((int) (255 * titleAlpha));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(w * 0.068f);
        textPaint.setTypeface(Typeface.create(ThemeManager.getCustomFont(), Typeface.BOLD));
        textPaint.setColor(0xFFFFFFFF);
        titleMarquee.draw(canvas, title != null ? title : "", x, titleY, maxW, textPaint);
        boolean titleOverflow = title != null && textPaint.measureText(title) > maxW;
        if (subtitle != null && !subtitle.isEmpty()) {
            textPaint.setTextSize(w * 0.046f);
            textPaint.setTypeface(Typeface.create(ThemeManager.getCustomFont(), Typeface.NORMAL));
            textPaint.setColor(ThemeManager.getTextColorSecondary());
            subtitleMarquee.draw(canvas, subtitle, x, titleY + h * 0.055f, maxW, textPaint);
            if (!titleOverflow) {
                titleOverflow = textPaint.measureText(subtitle) > maxW;
            }
            textPaint.setColor(0xFFFFFFFF);
        }
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAlpha(255);
        if (titleOverflow) {
            scheduleFrontFaceMarqueeTick();
        } else {
            stopFrontFaceMarqueeTick();
        }
        if (titleAlpha < 1f) scheduleFrontFaceMarqueeTick();
    }

    private void drawEmptyHint(Canvas canvas, float w, float h) {
        if (w <= 0 || h <= 0) return;
        textPaint.setTextSize(w * 0.05f);
        canvas.drawText(getContext().getString(com.solar.launcher.R.string.flow_empty),
                w * 0.5f, h * 0.5f, textPaint);
    }

    /** Per-slot alpha during reverse handoff — center on flyer until landing crossfade. */
    private float coverHandoffAlpha(boolean isVisualCenter) {
        if (isVisualCenter) {
            return handoffCenterRevealAlpha;
        }
        return handoffSideRevealAlpha;
    }

    /** Center reflection when idle only; skip during handoff and reflection fade-in. */
    static boolean shouldSkipCoverReflection(boolean noReflections, boolean handoffAnimating,
            boolean carouselScrolling, boolean isVisualCenter) {
        return shouldSkipCoverReflection(noReflections, handoffAnimating, carouselScrolling,
                isVisualCenter, handoffAnimating ? 0f : 1f);
    }

    static boolean shouldSkipCoverReflection(boolean noReflections, boolean handoffAnimating,
            boolean carouselScrolling, boolean isVisualCenter, float reflectRevealAlpha) {
        if (noReflections || !isVisualCenter || carouselScrolling) return true;
        if (handoffAnimating || reflectRevealAlpha < 1f) return true;
        return false;
    }
}
