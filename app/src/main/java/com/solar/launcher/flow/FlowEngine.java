package com.solar.launcher.flow;

/**
 * Cover Flow scroll engine — time-based ease per album step (Classipod ~300ms),
 * Apple/Classipod pose layout via {@link CoverFlowLayout}.
 */
public final class FlowEngine {

    public static final int VISIBLE_RADIUS = CoverFlowLayout.SIDE_SLIDES;
    /** One wheel detent — frame-rate independent on MT6572. */
    public static final long SCROLL_MS = 290L;

    private int itemCount;
    private int centerIndex;
    private int targetIndex;
    private int step;
    private int slideFrameInt;
    private int fade;

    /** Time-based scroll segment — restarts when target queues mid-animation. */
    private long scrollAnimStartMs;
    private int scrollAnimFromFixed;
    private int scrollAnimToFixed;
    private boolean scrollAnimPendingStart;

    private final CoverFlowLayout.SlidePose centerSlide = new CoverFlowLayout.SlidePose();
    private final CoverFlowLayout.SlidePose[] leftSlides =
            new CoverFlowLayout.SlidePose[CoverFlowLayout.SIDE_SLIDES];
    private final CoverFlowLayout.SlidePose[] rightSlides =
            new CoverFlowLayout.SlidePose[CoverFlowLayout.SIDE_SLIDES];

    private float viewportW = 480f;
    private float viewportH = 360f;
    private CoverFlowLayout.Metrics viewportMetrics;

    public FlowEngine() {
        for (int i = 0; i < CoverFlowLayout.SIDE_SLIDES; i++) {
            leftSlides[i] = new CoverFlowLayout.SlidePose();
            rightSlides[i] = new CoverFlowLayout.SlidePose();
        }
        resetSlides(null);
    }

    public void setItemCount(int count) {
        itemCount = Math.max(0, count);
        if (centerIndex >= itemCount) {
            centerIndex = Math.max(0, itemCount - 1);
            targetIndex = centerIndex;
            slideFrameInt = centerIndex << 16;
            step = 0;
            resetSlides(null);
        }
    }

    public int getItemCount() {
        return itemCount;
    }

    public int getFocusIndex() {
        return centerIndex;
    }

    /**
     * Album visually nearest carousel center — may differ from {@link #getFocusIndex()} mid-scroll.
     * OK / flip must use this so fast wheel + select flips the cover on screen, not the target index.
     */
    public int getVisualCenterIndex() {
        if (itemCount <= 0) return 0;
        float visual = getVisualOffset();
        int lo = clamp((int) Math.floor(visual), 0, itemCount - 1);
        int hi = clamp(lo + 1, 0, itemCount - 1);
        if (hi > lo) {
            return (visual - lo) < 0.5f ? lo : hi;
        }
        return lo;
    }

    /** End scroll animation on the cover the user sees at center (wheel OK during motion). */
    public void snapToVisualCenter() {
        if (step == 0) return;
        int visual = getVisualCenterIndex();
        centerIndex = visual;
        targetIndex = visual;
        slideFrameInt = visual << 16;
        step = 0;
        scrollAnimStartMs = 0L;
        scrollAnimPendingStart = false;
        resetSlides(null);
    }

    public void setViewport(float w, float h) {
        if (w > 0f) viewportW = w;
        if (h > 0f) viewportH = h;
    }

    /** Cached metrics from {@link FlowView} — avoids rebuilding reflect_table each tick. */
    public void setViewportMetrics(CoverFlowLayout.Metrics metrics, float w, float h) {
        viewportMetrics = metrics;
        setViewport(w, h);
    }

    private CoverFlowLayout.Metrics metrics() {
        if (viewportMetrics != null) {
            return viewportMetrics;
        }
        return CoverFlowLayout.metricsForViewport(viewportW, viewportH);
    }

    public float getVisualOffset() {
        return slideFrameInt / 65536f;
    }

    float getVisualOffsetAt(long nowMs) {
        return getVisualOffset();
    }

    public void setFocusIndex(int index) {
        if (itemCount <= 0) {
            centerIndex = 0;
            targetIndex = 0;
            slideFrameInt = 0;
            step = 0;
            resetSlides(null);
            return;
        }
        centerIndex = clamp(index, 0, itemCount - 1);
        targetIndex = centerIndex;
        slideFrameInt = centerIndex << 16;
        step = 0;
        resetSlides(null);
    }

    public void scrollBy(int delta) {
        if (itemCount <= 0 || delta == 0) return;
        if (delta > 0) showNextSlide();
        else showPreviousSlide();
    }

    /** True when wheel input started or continued scroll animation. */
    public boolean scrollByReturningMoved(int delta) {
        if (itemCount <= 0 || delta == 0) return false;
        int before = centerIndex;
        boolean animBefore = step != 0;
        scrollBy(delta);
        return step != 0 || centerIndex != before || animBefore;
    }

    public void tick(long nowMs) {
        if (step == 0) return;
        if (scrollAnimPendingStart) {
            scrollAnimStartMs = nowMs;
            scrollAnimPendingStart = false;
        }
        updateScrollAnimation(nowMs);
    }

    public boolean isAnimating() {
        return step != 0;
    }

    /** True while carousel scroll animation is running (step != 0). */
    public boolean isCarouselScrolling() {
        return step != 0;
    }

    public int getScrollStep() {
        return step;
    }

    /** Queued carousel destination — visible to flow unit tests. */
    int getTargetIndex() {
        return targetIndex;
    }

    public int visibleSlotMin() {
        return centerIndex - VISIBLE_RADIUS - 1;
    }

    public int visibleSlotMax() {
        return centerIndex + VISIBLE_RADIUS + 1;
    }

    public static final class SlotTransform {
        public float centerX;
        public float centerY;
        public float width;
        public float height;
        public float alpha;
        public float rotationYDeg;
        public float zDepth;
        public float depthOrder;
        /** 0 = bright front rack, 1 = deep shadow at carousel edge. */
        public float shadowStrength;
        /** 0 = rotate on left edge, 1 = right edge — Classipod inner-edge pivot. */
        public float pivotXFrac = 0.5f;
    }

    public SlotTransform slotTransform(int itemIndex, float viewW, float viewH, float ignoredVisual) {
        if (itemIndex < 0 || itemIndex >= itemCount) {
            SlotTransform empty = new SlotTransform();
            empty.alpha = 0f;
            return empty;
        }
        float rel = itemIndex - getVisualOffset();
        CoverFlowLayout.SlidePose pose = CoverFlowLayout.poseFromRelative(rel, metrics());
        pose.itemIndex = itemIndex;
        return CoverFlowLayout.toSlotTransform(pose, metrics());
    }

    public SlotTransform centerSlotTransform(float viewW, float viewH) {
        return slotTransform(getVisualCenterIndex(), viewW, viewH, 0f);
    }

    public int findIndexForKey(java.util.List<FlowItem> items, String focusKey) {
        if (focusKey == null || focusKey.isEmpty() || items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            FlowItem item = items.get(i);
            if (focusKey.equals(item.matchKey)) return i;
        }
        for (int i = 0; i < items.size(); i++) {
            FlowItem item = items.get(i);
            if (focusKey.equalsIgnoreCase(item.title)) return i;
            if (item.matchKey == null) continue;
            int pipe = item.matchKey.indexOf('|');
            if (pipe <= 0) continue;
            String itemAlbum = item.matchKey.substring(0, pipe);
            // Album-only key or playing artist differs from catalog primary artist.
            if (focusKey.equals(itemAlbum)) return i;
            int focusPipe = focusKey.indexOf('|');
            if (focusPipe > 0 && focusKey.substring(0, focusPipe).equals(itemAlbum)) return i;
        }
        return -1;
    }

    private void showNextSlide() {
        if (step == 0) {
            if (centerIndex < itemCount - 1) {
                targetIndex = centerIndex + 1;
                startAnimation();
            }
        } else if (step < 0) {
            targetIndex = centerIndex;
            step = 1;
            restartScrollSegment();
        } else {
            // Queue one album ahead of active target — never skip using stale centerIndex.
            int next = Math.min(targetIndex + 1, itemCount - 1);
            if (next != targetIndex) {
                targetIndex = next;
                restartScrollSegment();
            }
        }
    }

    private void showPreviousSlide() {
        if (step == 0) {
            if (centerIndex > 0) {
                targetIndex = centerIndex - 1;
                startAnimation();
            }
        } else if (step > 0) {
            targetIndex = centerIndex;
            step = -1;
            restartScrollSegment();
        } else {
            int next = Math.max(targetIndex - 1, 0);
            if (next != targetIndex) {
                targetIndex = next;
                restartScrollSegment();
            }
        }
    }

    private void startAnimation() {
        step = targetIndex > (slideFrameInt >> 16) ? 1 : -1;
        restartScrollSegment();
    }

    /** New ease segment from current frame toward queued targetIndex. */
    private void restartScrollSegment() {
        restartScrollSegment(0L);
    }

    private void restartScrollSegment(long nowMs) {
        scrollAnimFromFixed = slideFrameInt;
        scrollAnimToFixed = targetIndex << 16;
        if (nowMs > 0L) {
            scrollAnimStartMs = nowMs;
            scrollAnimPendingStart = false;
        } else {
            scrollAnimStartMs = 0L;
            scrollAnimPendingStart = true;
        }
        step = scrollAnimToFixed >= scrollAnimFromFixed ? 1 : -1;
        if (scrollAnimToFixed == scrollAnimFromFixed) step = 0;
    }

    private void resetSlides(CoverFlowLayout.Metrics m) {
        if (m == null) m = metrics();
        centerSlide.itemIndex = centerIndex;
        centerSlide.cx = 0f;
        centerSlide.cy = 0f;
        centerSlide.angle = 0;
        centerSlide.alpha = 256;
        centerSlide.drawBucket = 3;
        centerSlide.sideRank = 0;

        for (int i = 0; i < CoverFlowLayout.SIDE_SLIDES; i++) {
            CoverFlowLayout.SlidePose si = leftSlides[i];
            si.itemIndex = centerIndex - 1 - i;
            si.angle = CoverFlowLayout.ITILT;
            si.cx = -CoverFlowLayout.cxForSideRank(i + 1, m);
            si.cy = m.offsetY;
            si.alpha = 256;
            si.drawBucket = 0;
            si.sideRank = i;

            si = rightSlides[i];
            si.itemIndex = centerIndex + 1 + i;
            si.angle = -CoverFlowLayout.ITILT;
            si.cx = CoverFlowLayout.cxForSideRank(i + 1, m);
            si.cy = m.offsetY;
            si.alpha = 256;
            si.drawBucket = 1;
            si.sideRank = i;
        }
    }

    private void updateScrollAnimation(long nowMs) {
        if (step == 0) return;

        long elapsed = nowMs - scrollAnimStartMs;
        float t = Math.min(1f, elapsed / (float) SCROLL_MS);
        float eased = easeOutCubic(t);
        slideFrameInt = scrollAnimFromFixed
                + Math.round((scrollAnimToFixed - scrollAnimFromFixed) * eased);

        // Rockbox edge fade hint during scroll.
        int pos = slideFrameInt & 0xffff;
        fade = pos / 256;

        if (t >= 1f) {
            finishScrollAtTarget();
            return;
        }

        // Retarget direction if user reversed queue mid-segment.
        if (targetIndex < (slideFrameInt >> 16) && step > 0) {
            step = -1;
            restartScrollSegment(nowMs);
        } else if (targetIndex > (slideFrameInt >> 16) && step < 0) {
            step = 1;
            restartScrollSegment(nowMs);
        }
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private void finishScrollAtTarget() {
        centerIndex = targetIndex;
        slideFrameInt = targetIndex << 16;
        step = 0;
        fade = 256;
        scrollAnimStartMs = 0L;
        scrollAnimPendingStart = false;
        resetSlides(metrics());
    }

    public int fillDrawOrder(int[] out) {
        return fillDrawOrder(out, null);
    }

    /** Fills draw list sorted back-to-front by {@link CoverFlowLayout} depthOrder. */
    public int fillDrawOrder(int[] out, float[] depthOut) {
        int raw = fillDrawOrderUnsorted(out);
        if (raw <= 1 || depthOut == null) return raw;
        CoverFlowLayout.Metrics m = metrics();
        for (int i = 0; i < raw; i++) {
            float rel = out[i] - getVisualOffset();
            CoverFlowLayout.SlidePose pose = CoverFlowLayout.poseFromRelative(rel, m);
            pose.itemIndex = out[i];
            depthOut[i] = CoverFlowLayout.depthOrderFromPose(pose, m);
        }
        for (int i = 1; i < raw; i++) {
            int idx = out[i];
            float depth = depthOut[i];
            int j = i;
            while (j > 0 && depthOut[j - 1] > depth) {
                out[j] = out[j - 1];
                depthOut[j] = depthOut[j - 1];
                j--;
            }
            out[j] = idx;
            depthOut[j] = depth;
        }
        return raw;
    }

    private int fillDrawOrderUnsorted(int[] out) {
        if (itemCount <= 0) return 0;
        float visual = getVisualOffset();
        int lo = Math.max(0, (int) Math.floor(visual) - CoverFlowLayout.SIDE_SLIDES - 1);
        int hi = Math.min(itemCount - 1, (int) Math.ceil(visual) + CoverFlowLayout.SIDE_SLIDES + 1);
        CoverFlowLayout.Metrics m = metrics();
        int n = 0;
        for (int idx = lo; idx <= hi && n < out.length; idx++) {
            CoverFlowLayout.SlidePose pose = CoverFlowLayout.poseFromRelative(idx - visual, m);
            if (pose.alpha > 0) out[n++] = idx;
        }
        return n;
    }

    private CoverFlowLayout.SlidePose poseForItem(int itemIndex) {
        if (itemIndex < 0 || itemIndex >= itemCount) {
            CoverFlowLayout.SlidePose empty = new CoverFlowLayout.SlidePose();
            empty.itemIndex = -1;
            empty.alpha = 0;
            return empty;
        }
        CoverFlowLayout.SlidePose pose =
                CoverFlowLayout.poseFromRelative(itemIndex - getVisualOffset(), metrics());
        pose.itemIndex = itemIndex;
        return pose;
    }

    /** Exposed for flip fan-out in {@link FlowView}. */
    CoverFlowLayout.SlidePose poseForItemPublic(int itemIndex) {
        if (itemIndex < 0 || itemIndex >= itemCount) return null;
        return poseForItem(itemIndex);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
