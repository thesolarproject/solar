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
        /** User scrolled — drop NP handoff pin so neighbors use catalog art. */
        void onCarouselScrollStarted();
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
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF drawRect = new RectF();
    private final RectF bakedDestRect = new RectF();
    private final RectF shadowRect = new RectF();
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
    private boolean noReflections = true;
    private boolean debugTheme;
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
    // #region agent log
    /** Session 898913 — throttle for dimmed-center detector in onDraw. */
    private long dbg898913LastDrawLogMs;
    private long dbg898913LastNeighborLogMs;
    private boolean dbg898913WasScrolling;
    /** Throttle per-slot draw-path glitch logs in drawCoverAt. */
    private long dbg898913LastGlitchLogMs;
    private long dbg898913LastPathLogMs;
    private float dbg898913LastDisplaySize = -1f;
    private int dbg898913DrawFrame;
    private final float[] dbg898913MatrixVals = new float[9];
    // #endregion
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
            // #region agent log
            if (com.solar.launcher.Debug898913Log.ENABLED && dbg898913WasScrolling && !scrolling) {
                logCarouselNeighborsDebug("FlowView.animTick", "scroll settled", "H-E", 0);
            }
            dbg898913WasScrolling = scrolling;
            // #endregion
            boolean flipping = flip.tick(now);
            tickTitleFade(now);
            tickHandoffTitleReveal(now);
            tickHandoffReflectReveal(now);
            // Idle guard — handoff prep can zero side alpha; restore when not morphing.
            if (!com.solar.launcher.flow.FlowPlayerHandoff.isHandoffAnimating()
                    && !handoffRevealActive
                    && !engine.isCarouselScrolling()) {
                if (handoffCenterRevealAlpha < 1f) handoffCenterRevealAlpha = 1f;
                if (handoffSideRevealAlpha < 1f) handoffSideRevealAlpha = 1f;
            }
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
        textPaint.setTypeface(ThemeManager.getFlowFont(debugTheme));
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
        int n = items != null ? items.size() : 0;
        int focus = n > 0 ? Math.min(engine.getFocusIndex(), n - 1) : 0;
        setItemsAndFocus(items, focus);
    }

    /** Bind catalog and focus atomically — avoids one frame of wrong covers at old index. */
    public void setItemsAndFocus(List<FlowItem> items, int focusIndex) {
        this.items = items != null ? items : java.util.Collections.<FlowItem>emptyList();
        engine.setItemCount(this.items.size());
        if (!this.items.isEmpty()) {
            int idx = focusIndex;
            if (idx < 0) idx = 0;
            if (idx >= this.items.size()) idx = this.items.size() - 1;
            engine.setFocusIndex(idx);
        }
        lastNotifiedFocus = -1;
        deferredFocusNotify = -1;
        deferredPrefetchCenter = -1;
        flip.reset();
        cancelGuidedScroll();
        debugDrawFramesLeft = 3;
        debugBackFaceLogged = false;
        cachedMetrics = null;
        if (!this.items.isEmpty()) {
            // Sync small-rack metrics before animTick — stale large-rack spacing caused 2px neighbor peek.
            viewportMetrics();
            postOnAnimation(animTick);
        }
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setDebugTheme(boolean on) {
        debugTheme = on;
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
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("centerAlphaNow", handoffCenterRevealAlpha);
            d.put("handoffAnim", FlowPlayerHandoff.isHandoffAnimating());
            com.solar.launcher.Debug898913Log.log("FlowView.prepareHandoffFlyerOnly",
                    "center alpha suppressed for morph", "H-C", d);
        } catch (Exception ignored) {}
        // #endregion
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
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("centerAlphaNow", handoffCenterRevealAlpha);
            d.put("handoffAnim", FlowPlayerHandoff.isHandoffAnimating());
            com.solar.launcher.Debug898913Log.log("FlowView.prepareHandoffHidden",
                    "all reveal alphas zeroed (unmeasured prep)", "H-C", d);
        } catch (Exception ignored) {}
        // #endregion
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
        if (handoffPinKey == null || handoffPinKey.isEmpty()) {
            return handoffPinBitmap;
        }
        if (handoffPinKey.equals(item.coverKey)
                || (item.matchKey != null && handoffPinKey.equals(item.matchKey))) {
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

    /** Flyer removed — clear pin, schedule bakes; floor reflection stays on during browse. */
    public void finishHandoffLanding() {
        handoffCenterRevealAlpha = 1f;
        handoffSideRevealAlpha = 1f;
        titleAlpha = 1f;
        clearHandoffPin();
        handoffReflectRevealStartMs = 0L;
        handoffReflectRevealAlpha = 1f;
        invalidate();
        scheduleBakesAround(engine.getFocusIndex(), 2);
        postOnAnimation(animTick);
    }

    /** Crossfade / instant reveal — all cover slots opaque; no handoff choreography. */
    public void resetHandoffRevealForDisplay() {
        handoffDrawSuppressed = false;
        handoffRevealActive = false;
        handoffTitleRevealStartMs = 0L;
        handoffReflectRevealStartMs = 0L;
        handoffCenterRevealAlpha = 1f;
        handoffSideRevealAlpha = 1f;
        handoffReflectRevealAlpha = 1f;
        titleAlpha = 1f;
        titleFadeFrom = -1;
        // Keep any handoff pin — crossfade seeds center art from NP; cleared on landing.
        invalidate();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("centerAlpha", handoffCenterRevealAlpha);
            com.solar.launcher.Debug898913Log.log("FlowView.resetHandoffRevealForDisplay",
                    "all reveal alphas reset for crossfade", "H-C", d);
        } catch (Exception ignored) {}
        // #endregion
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
            // #region agent log
            logCarouselNeighborsDebug("FlowView.scrollWheel", "wheel input", "H-E,H-A", delta);
            // #endregion
            clearHandoffPin();
            if (callback != null) callback.onCarouselScrollStarted();
            if (engine.isCarouselScrolling()) {
                deferredPrefetchCenter = engine.getVisualCenterIndex();
                if (callback != null) {
                    callback.prefetchCarouselCovers(engine.getVisualCenterIndex(), 5);
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
                deferredPrefetchCenter = engine.getVisualCenterIndex();
                if (callback != null) {
                    callback.prefetchCarouselCovers(engine.getVisualCenterIndex(), 5);
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
        // Worker warm even mid-scroll — disk hits land without waiting for scroll end.
        callback.warmCarouselCovers(center, 3);
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
                titleMarquee.reset();
                subtitleMarquee.reset();
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
        if (com.solar.launcher.Debug898913Log.ENABLED
                && handoffCenterRevealAlpha < 0.99f
                && !FlowPlayerHandoff.isHandoffAnimating()
                && System.currentTimeMillis() - dbg898913LastDrawLogMs > 2000L) {
            dbg898913LastDrawLogMs = System.currentTimeMillis();
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("centerAlpha", handoffCenterRevealAlpha);
                d.put("sideAlpha", handoffSideRevealAlpha);
                d.put("titleAlpha", titleAlpha);
                d.put("reflectAlpha", handoffReflectRevealAlpha);
                d.put("pinKey", handoffPinKey != null ? handoffPinKey : "");
                com.solar.launcher.Debug898913Log.log("FlowView.onDraw",
                        "center cover dimmed while NOT handoff-animating", "H-C", d);
            } catch (Exception ignored) {}
        }
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
        // Idle guard — handoff prep can zero side/center alphas while animTick is not posted.
        if (!com.solar.launcher.flow.FlowPlayerHandoff.isHandoffAnimating()
                && !handoffRevealActive
                && !engine.isCarouselScrolling()) {
            if (handoffCenterRevealAlpha < 1f) handoffCenterRevealAlpha = 1f;
            if (handoffSideRevealAlpha < 1f) handoffSideRevealAlpha = 1f;
            if (handoffReflectRevealStartMs <= 0L && handoffReflectRevealAlpha < 1f) {
                handoffReflectRevealAlpha = 1f;
            }
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
        // #region agent log
        dbg898913DrawFrame++;
        if (com.solar.launcher.Debug898913Log.ENABLED
                && Math.abs(metrics.displaySize - dbg898913LastDisplaySize) > 0.5f) {
            dbg898913LastDisplaySize = metrics.displaySize;
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("runId", "pre-fix");
                d.put("displaySize", metrics.displaySize);
                d.put("viewW", metrics.viewW);
                d.put("viewH", metrics.viewH);
                d.put("offsetX", metrics.offsetX);
                com.solar.launcher.Debug898913Log.logAlways(
                        "FlowView.onDraw", "metrics changed", "H-D", d);
            } catch (Exception ignored) {}
        }
        // #endregion
        // #region agent log
        if (com.solar.launcher.Debug898913Log.ENABLED && !engine.isCarouselScrolling()
                && !flip.isFlippedOrFlipping()
                && System.currentTimeMillis() - dbg898913LastNeighborLogMs > 3000L) {
            dbg898913LastNeighborLogMs = System.currentTimeMillis();
            logCarouselNeighborsDebug("FlowView.onDraw", "idle carousel", "H-C,H-D", 0);
        }
        // #endregion
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

    /** Debug 898913 — catalog index vs visible slot poses (neighbor / two-step scroll). */
    private void logCarouselNeighborsDebug(String location, String message, String hypothesisId,
            int wheelDelta) {
        if (!com.solar.launcher.Debug898913Log.ENABLED || items.isEmpty()) return;
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            int focus = engine.getFocusIndex();
            int visualCenter = engine.getVisualCenterIndex();
            float visualOff = engine.getVisualOffset();
            d.put("wheelDelta", wheelDelta);
            d.put("focusIndex", focus);
            d.put("visualCenter", visualCenter);
            d.put("visualOffset", visualOff);
            d.put("targetIndex", engine.getTargetIndex());
            d.put("scrolling", engine.isCarouselScrolling());
            d.put("itemCount", items.size());
            d.put("sideRevealAlpha", handoffSideRevealAlpha);
            d.put("centerRevealAlpha", handoffCenterRevealAlpha);
            CoverFlowLayout.Metrics m = viewportMetrics();
            float vw = m.viewW > 0f ? m.viewW : viewW;
            float vh = m.viewH > 0f ? m.viewH : viewH;
            org.json.JSONArray slots = new org.json.JSONArray();
            int visibleRight = 0;
            int visibleLeft = 0;
            for (int dIdx = -2; dIdx <= 2; dIdx++) {
                int idx = focus + dIdx;
                if (idx < 0 || idx >= items.size()) continue;
                FlowEngine.SlotTransform t = engine.slotTransform(idx, vw, vh, 0f);
                float rel = idx - visualOff;
                org.json.JSONObject slot = new org.json.JSONObject();
                slot.put("d", dIdx);
                slot.put("idx", idx);
                slot.put("title", items.get(idx).title);
                slot.put("rel", rel);
                slot.put("alpha", t.alpha);
                slot.put("cx", t.centerX);
                slot.put("width", t.width);
                slot.put("rotY", t.rotationYDeg);
                slot.put("depth", t.depthOrder);
                slot.put("drawAlpha", t.alpha * (idx == visualCenter
                        ? handoffCenterRevealAlpha : handoffSideRevealAlpha));
                if (idx == visualCenter + 1 || idx == visualCenter - 1) {
                    FlowEngine.SlotTransform c = engine.slotTransform(visualCenter, vw, vh, 0f);
                    float centerEdge = idx > visualCenter
                            ? c.centerX + c.width * 0.5f
                            : c.centerX - c.width * 0.5f;
                    float hinge = idx > visualCenter
                            ? t.centerX - t.width * 0.5f
                            : t.centerX + t.width * 0.5f;
                    double rad = Math.toRadians(t.rotationYDeg);
                    float faceEdge = idx > visualCenter
                            ? hinge + (float) (t.width * Math.cos(rad))
                            : hinge - (float) (t.width * Math.cos(rad));
                    slot.put("visiblePeekPx", Math.abs(faceEdge - centerEdge));
                }
                slots.put(slot);
                if (t.alpha > 0.05f && rel > 0.5f) visibleRight++;
                if (t.alpha > 0.05f && rel < -0.5f) visibleLeft++;
            }
            d.put("slots", slots);
            d.put("visibleLeft", visibleLeft);
            d.put("visibleRight", visibleRight);
            d.put("drawCount", engine.fillDrawOrder(drawOrder, drawDepth));
            org.json.JSONArray drawSeq = new org.json.JSONArray();
            int drawN = engine.fillDrawOrder(drawOrder, drawDepth);
            for (int k = 0; k < drawN; k++) {
                org.json.JSONObject row = new org.json.JSONObject();
                int idx = drawOrder[k];
                row.put("k", k);
                row.put("idx", idx);
                row.put("depth", drawDepth[k]);
                row.put("rel", idx - visualOff);
                row.put("sideRank", engine.poseForItemPublic(idx) != null
                        ? engine.poseForItemPublic(idx).sideRank : -1);
                drawSeq.put(row);
            }
            d.put("drawSeq", drawSeq);
            d.put("displaySize", m.displaySize);
            d.put("viewW", m.viewW);
            d.put("viewH", m.viewH);
            com.solar.launcher.Debug898913Log.log(location, message, hypothesisId, d);
        } catch (Exception ignored) {}
    }

    /** Debug 898913 — detect bake+tilt distortion, matrix blow-up, or bad slot sizing. */
    private void logDrawCoverGlitchIfNeeded(int index, float totalRotY, float pivotX,
            FlowEngine.SlotTransform t, CoverFlowLayout.Metrics metrics, boolean usingBaked) {
        long nowMs = System.currentTimeMillis();
        matrix.getValues(dbg898913MatrixVals);
        float scaleX = (float) Math.hypot(dbg898913MatrixVals[0], dbg898913MatrixVals[1]);
        float scaleY = (float) Math.hypot(dbg898913MatrixVals[3], dbg898913MatrixVals[4]);
        float aspectDistort = scaleY > 0.01f ? scaleX / scaleY : scaleX;
        boolean isCenter = index == engine.getVisualCenterIndex();
        boolean bakedOnTilt = usingBaked && Math.abs(totalRotY) > 8f;
        boolean matrixBlowup = scaleX > 3f || scaleY > 3f || scaleX < 0.12f || scaleY < 0.12f
                || aspectDistort > 4f || aspectDistort < 0.25f;
        boolean tinySide = !isCenter && drawRect.width() < 40f && t.alpha > 0.1f;
        boolean hugeSlot = drawRect.width() > metrics.viewW * 0.75f;
        // Always log bake+tilt (hypothesis A); throttle other suspects.
        if (!bakedOnTilt) {
            if (!com.solar.launcher.Debug898913Log.ENABLED) return;
            if (!matrixBlowup && !tinySide && !hugeSlot) return;
            if (nowMs - dbg898913LastGlitchLogMs < 250L) return;
        } else if (nowMs - dbg898913LastGlitchLogMs < 150L) {
            return;
        }
        dbg898913LastGlitchLogMs = nowMs;
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("runId", "pre-fix");
            d.put("frame", dbg898913DrawFrame);
            d.put("index", index);
            d.put("isCenter", isCenter);
            d.put("rotY", totalRotY);
            d.put("pivotX", pivotX);
            d.put("zDepth", t.zDepth);
            d.put("drawW", drawRect.width());
            d.put("drawH", drawRect.height());
            d.put("tWidth", t.width);
            d.put("displaySize", metrics.displaySize);
            d.put("scaleX", scaleX);
            d.put("scaleY", scaleY);
            d.put("aspectDistort", aspectDistort);
            d.put("usingBaked", usingBaked);
            d.put("bakedOnTilt", bakedOnTilt);
            d.put("matrixBlowup", matrixBlowup);
            d.put("tinySide", tinySide);
            d.put("hugeSlot", hugeSlot);
            d.put("scrolling", engine.isCarouselScrolling());
            d.put("visualOffset", engine.getVisualOffset());
            String hyp = bakedOnTilt ? "H-A" : (matrixBlowup ? "H-B" : (tinySide ? "H-C" : "H-D"));
            d.put("runId", "post-fix-egress");
            com.solar.launcher.Debug898913Log.logAlways(
                    "FlowView.drawCoverAt", "glitch suspect", hyp, d);
        } catch (Exception ignored) {}
    }

    /** Debug 898913 — which draw branch ran and whether tilt+bake could shear. */
    private void logDrawPathIfNeeded(int index, String path, float totalRotY,
            FlowEngine.SlotTransform t, CoverFlowLayout.Metrics metrics, boolean usingBaked) {
        if (!com.solar.launcher.Debug898913Log.ENABLED) return;
        long nowMs = System.currentTimeMillis();
        boolean isCenter = index == engine.getVisualCenterIndex();
        boolean suspect = usingBaked && Math.abs(totalRotY) > 8f
                || (!isCenter && "reflect".equals(path))
                || drawRect.width() > metrics.viewW * 0.75f;
        if (!suspect && nowMs - dbg898913LastPathLogMs < 2000L) return;
        if (suspect || nowMs - dbg898913LastPathLogMs >= 2000L) {
            dbg898913LastPathLogMs = nowMs;
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("runId", "pre-fix");
                d.put("index", index);
                d.put("path", path);
                d.put("isCenter", isCenter);
                d.put("rotY", totalRotY);
                d.put("zDepth", t.zDepth);
                d.put("depthOrder", t.depthOrder);
                d.put("sideRank", engine.poseForItemPublic(index) != null
                        ? engine.poseForItemPublic(index).sideRank : -1);
                d.put("drawW", drawRect.width());
                d.put("displaySize", metrics.displaySize);
                d.put("usingBaked", usingBaked);
                d.put("suspect", suspect);
                d.put("scrolling", engine.isCarouselScrolling());
                String hyp = usingBaked && Math.abs(totalRotY) > 8f ? "H-C"
                        : (!isCenter && "reflect".equals(path) ? "H-C" : "H-B");
                com.solar.launcher.Debug898913Log.log(
                        "FlowView.drawCoverAt", "draw path", hyp, d);
            } catch (Exception ignored) {}
        }
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
        final boolean isVisualCenter = index == engine.getVisualCenterIndex();
        if (bmp == null && callback != null && !backFace) {
            // Pinned NP art covers the center slot during crossfade — no async grey placeholder.
            if (!(isVisualCenter && handoffPinBitmap != null && !handoffPinBitmap.isRecycled())) {
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

        // Upright center: draw floor gloss in-slot.

        canvas.save();
        float totalRotY = t.rotationYDeg + extraRotY;
        // Center OK-flip uses full 180° on card center; carousel scroll uses edge hinge.
        float pivotX = (backFace || Math.abs(extraRotY) > 0.01f)
                ? t.centerX
                : CoverFlowLayout.pivotXForCoverFlow(totalRotY, drawRect.left, drawRect.right);
        camera.save();
        camera.translate(0, 0, t.zDepth);
        camera.rotateY(totalRotY);
        matrix.reset();
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-pivotX, -t.centerY);
        matrix.postTranslate(pivotX, t.centerY);
        // #region agent log
        boolean dbgUsingBaked = false;
        if (!backFace && bmp != null && !bmp.isRecycled()) {
            boolean skipReflectDbg = shouldSkipCoverReflection(noReflections, isVisualCenter);
            if (!skipReflectDbg) {
                Bitmap bakedDbg = bakeCache.peek(item.coverKey, metrics, true);
                dbgUsingBaked = bakedDbg != null && !bakedDbg.isRecycled()
                        && Math.abs(totalRotY) < 8f;
            }
        }
        logDrawCoverGlitchIfNeeded(index, totalRotY, pivotX, t, metrics, dbgUsingBaked);
        // #endregion
        canvas.concat(matrix);

        boolean drewDynamicCover = false;
        float reflectH = 0f;
        float slotAlpha = 0f;

        if (backFace) {
            drawBackFace(canvas, drawRect);
        } else {
            slotAlpha = t.alpha * coverHandoffAlpha(isVisualCenter);
            if (slotAlpha <= 0f) {
                canvas.restore();
                coverPaint.setAlpha(255);
                return;
            }
            // Floor shadow slab — outer covers recede into darkness during egress.
            if (t.shadowStrength > 0.05f) {
                float sh = t.shadowStrength;
                shadowPaint.setColor(0xFF000000);
                shadowPaint.setAlpha((int) (130 * sh * slotAlpha));
                float padX = drawRect.width() * (0.05f + 0.06f * sh);
                float padY = drawRect.height() * (0.08f + 0.10f * sh);
                shadowRect.set(drawRect.left + padX, drawRect.top + padY * 1.4f,
                        drawRect.right - padX * 0.4f, drawRect.bottom + padY);
                canvas.drawRect(shadowRect, shadowPaint);
            }
            coverPaint.setAlpha((int) (255 * slotAlpha));
            if (bmp != null && !bmp.isRecycled()) {
                // iPod Classic: center cover keeps floor reflection while idle; deferred after handoff.
                boolean skipReflect = shouldSkipCoverReflection(noReflections, isVisualCenter);
                // Scale reflection band with perspective-shrunk cover — baked bitmap is displaySize-native.
                float coverScale = metrics.displaySize > 0f
                        ? drawRect.width() / metrics.displaySize : 1f;
                reflectH = skipReflect ? 0f : metrics.reflectHeight * coverScale;
                // #region agent log
                if (isVisualCenter && com.solar.launcher.Debug898913Log.ENABLED
                        && System.currentTimeMillis() - debugReflectLogMs > 800L) {
                    debugReflectLogMs = System.currentTimeMillis();
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("skipReflect", skipReflect);
                        d.put("reflectH", reflectH);
                        d.put("reflectReveal", handoffReflectRevealAlpha);
                        d.put("scrolling", engine.isCarouselScrolling());
                        d.put("path", skipReflect || reflectH <= 0f ? "coverOnly" : "reflect");
                        com.solar.launcher.Debug898913Log.log("FlowView.drawCoverAt", "center reflect", "H-REFL-C", d);
                    } catch (Exception ignored) {}
                }
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
                            d.put("reflectReveal", handoffReflectRevealAlpha);
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
                if (skipReflect || reflectH <= 0f) {
                    // #region agent log
                    logDrawPathIfNeeded(index, "coverOnly", totalRotY, t, metrics, false);
                    // #endregion
                    FlowAlbumArt3d.drawCover(canvas, bmp, drawRect, 0f, slotAlpha, coverPaint);
                } else {
                    Bitmap baked = bakeCache.peek(item.coverKey, metrics, true);
                    // Tall baked composite (cover+floor) shears into vertical stripes under edge-pivot rotateY.
                    boolean bakeOkForTilt = Math.abs(totalRotY) < 8f;
                    boolean uprightCenter = isVisualCenter && bakeOkForTilt;
                    boolean useBaked = baked != null && !baked.isRecycled()
                            && bakeOkForTilt && !uprightCenter;
                    if (uprightCenter) {
                        // #region agent log
                        logDrawPathIfNeeded(index, "reflect-center", totalRotY, t, metrics, false);
                        // #endregion
                        FlowAlbumArt3d.drawCover(canvas, bmp, drawRect, 0f, slotAlpha, coverPaint);
                        drewDynamicCover = true;
                        bakeCache.scheduleBake(item.coverKey, bmp, metrics, true);
                    } else if (useBaked) {
                        // #region agent log
                        logDrawPathIfNeeded(index, "baked", totalRotY, t, metrics, true);
                        // #endregion
                        bakedDestRect.set(drawRect.left, drawRect.top,
                                drawRect.right, drawRect.top + drawRect.width() + reflectH);
                        canvas.drawBitmap(baked, null, bakedDestRect, coverPaint);
                    } else {
                        // #region agent log
                        logDrawPathIfNeeded(index, "reflect", totalRotY, t, metrics, false);
                        // #endregion
                        FlowAlbumArt3d.drawCover(canvas, bmp, drawRect, 0f, slotAlpha, coverPaint);
                        drewDynamicCover = true;
                        if (bakeOkForTilt) {
                            bakeCache.scheduleBake(item.coverKey, bmp, metrics, true);
                        }
                    }
                }
            } else {
                if (isVisualCenter && handoffPinBitmap != null && !handoffPinBitmap.isRecycled()
                        && (handoffPinKey == null || handoffPinKey.isEmpty()
                        || handoffPinKey.equals(item.coverKey)
                        || (item.matchKey != null && handoffPinKey.equals(item.matchKey)))) {
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

        if (drewDynamicCover && reflectH > 0f && bmp != null && !bmp.isRecycled()) {
            // Center cover: zDepth=0, rotY=0 → matrix is visually identity but Camera injects
            // perspective coefficients that break mapRect on Android 4.2. Draw directly in
            // screen coordinates (drawRect already contains the correct screen position).
            FlowAlbumArt3d.drawReflectionFloor(canvas, bmp, drawRect, slotAlpha * handoffReflectRevealAlpha,
                    reflectH, metrics.reflectTable, reflectionPaint, isVisualCenter ? null : matrix);
        }
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
        backRowPaint.setTypeface(Typeface.create(ThemeManager.getFlowFont(debugTheme), Typeface.BOLD));
        backRowPaint.setColor(0xFFFFFFFF);
        String title = flip.headerTitle() != null ? flip.headerTitle() : "";
        backTitleMarquee.draw(canvas, title, rect.left + pad,
                rect.top + headerH * 0.42f, rect.width() - pad * 2f, backRowPaint);

        String subtitle = flip.headerSubtitle();
        if (subtitle != null && !subtitle.isEmpty()) {
            backRowPaint.setTextSize(headerSubPx);
            backRowPaint.setTypeface(Typeface.create(ThemeManager.getFlowFont(debugTheme), Typeface.NORMAL));
            backRowPaint.setColor(0xFFE0E8F0);
            backSubtitleMarquee.draw(canvas, subtitle, rect.left + pad,
                    rect.top + headerH * 0.76f, rect.width() - pad * 2f, backRowPaint);
        }

        int count = flip.visibleBackRowCount();
        int start = flip.visibleBackRowStart();
        List<FlowScreenHost.FlowBackRow> rows = flip.backRows();
        backRowPaint.setTypeface(Typeface.create(ThemeManager.getFlowFont(debugTheme), Typeface.NORMAL));
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
        textPaint.setTypeface(Typeface.create(ThemeManager.getFlowFont(debugTheme), Typeface.BOLD));
        textPaint.setColor(0xFFFFFFFF);
        titleMarquee.draw(canvas, title != null ? title : "", x, titleY, maxW, textPaint);
        boolean titleOverflow = title != null && textPaint.measureText(title) > maxW;
        if (subtitle != null && !subtitle.isEmpty()) {
            textPaint.setTextSize(w * 0.046f);
            textPaint.setTypeface(Typeface.create(ThemeManager.getFlowFont(debugTheme), Typeface.NORMAL));
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

    /** Floor reflection on every visible cover unless debug No Reflections is on (center cover always has reflection). */
    static boolean shouldSkipCoverReflection(boolean noReflections, boolean isVisualCenter) {
        if (isVisualCenter) return false;
        return noReflections;
    }

    static boolean shouldSkipCoverReflection(boolean noReflections) {
        return shouldSkipCoverReflection(noReflections, false);
    }

    /** @deprecated use {@link #shouldSkipCoverReflection(boolean, boolean)} */
    static boolean shouldSkipCoverReflection(boolean noReflections, boolean handoffAnimating,
            boolean carouselScrolling, boolean isVisualCenter) {
        return shouldSkipCoverReflection(noReflections, isVisualCenter);
    }

    /** @deprecated use {@link #shouldSkipCoverReflection(boolean, boolean)} */
    static boolean shouldSkipCoverReflection(boolean noReflections, boolean handoffAnimating,
            boolean carouselScrolling, boolean isVisualCenter, float reflectRevealAlpha) {
        return shouldSkipCoverReflection(noReflections, isVisualCenter);
    }
}
