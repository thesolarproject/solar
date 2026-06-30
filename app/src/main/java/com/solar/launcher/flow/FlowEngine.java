package com.solar.launcher.flow;

/**
 * Cover Flow scroll engine — 16.16 slide_frame + sin deceleration (PictureFlow physics),
 * Apple/Classipod pose layout via {@link CoverFlowLayout}.
 */
public final class FlowEngine {

    public static final int VISIBLE_RADIUS = CoverFlowLayout.SIDE_SLIDES;

    private int itemCount;
    private int centerIndex;
    private int targetIndex;
    private int step;
    private int slideFrameInt;
    private int fade;

    private final CoverFlowLayout.SlidePose centerSlide = new CoverFlowLayout.SlidePose();
    private final CoverFlowLayout.SlidePose[] leftSlides =
            new CoverFlowLayout.SlidePose[CoverFlowLayout.SIDE_SLIDES];
    private final CoverFlowLayout.SlidePose[] rightSlides =
            new CoverFlowLayout.SlidePose[CoverFlowLayout.SIDE_SLIDES];

    private float viewportW = 480f;
    private float viewportH = 360f;

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

    public void setViewport(float w, float h) {
        if (w > 0f) viewportW = w;
        if (h > 0f) viewportH = h;
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
        updateScrollAnimation();
    }

    public boolean isAnimating() {
        return step != 0;
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
    }

    public SlotTransform slotTransform(int itemIndex, float viewW, float viewH, float ignoredVisual) {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(viewW, viewH);
        CoverFlowLayout.SlidePose pose = poseForItem(itemIndex);
        boolean center = pose == centerSlide;
        return CoverFlowLayout.toSlotTransform(pose, m, center);
    }

    public SlotTransform centerSlotTransform(float viewW, float viewH) {
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(viewW, viewH);
        return CoverFlowLayout.toSlotTransform(centerSlide, m, true);
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
            int pipe = item.matchKey.indexOf('|');
            if (pipe > 0 && focusKey.equals(item.matchKey.substring(0, pipe))) return i;
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
            step = targetIndex < centerSlide.itemIndex ? -1 : 1;
            if (step > 0) updateScrollAnimation();
        } else {
            targetIndex = Math.min(centerIndex + 2, itemCount - 1);
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
            step = targetIndex <= centerSlide.itemIndex ? -1 : 1;
            if (step < 0) updateScrollAnimation();
        } else {
            targetIndex = Math.max(0, centerIndex - 2);
        }
    }

    private void startAnimation() {
        step = targetIndex < centerSlide.itemIndex ? -1 : 1;
    }

    private void resetSlides(CoverFlowLayout.Metrics m) {
        if (m == null) m = CoverFlowLayout.metricsForViewport(viewportW, viewportH);
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
            si.cx = -(m.offsetX + m.slideSpacing * i);
            si.cy = m.offsetY;
            si.alpha = (i == 0) ? 128 : 256;
            si.drawBucket = 0;
            si.sideRank = i;

            si = rightSlides[i];
            si.itemIndex = centerIndex + 1 + i;
            si.angle = -CoverFlowLayout.ITILT;
            si.cx = m.offsetX + m.slideSpacing * i;
            si.cy = m.offsetY;
            si.alpha = (i == 0) ? 128 : 256;
            si.drawBucket = 1;
            si.sideRank = i;
        }
    }

    private void updateScrollAnimation() {
        if (step == 0) return;
        CoverFlowLayout.Metrics m = CoverFlowLayout.metricsForViewport(viewportW, viewportH);

        int speed = PictureFlowLayout.rockboxScrollSpeed(slideFrameInt, targetIndex);
        slideFrameInt += speed * step;

        int index = slideFrameInt >> 16;
        int pos = slideFrameInt & 0xffff;
        int neg = 65536 - pos;
        int tick = step < 0 ? neg : pos;
        float ftick = tick / 65536f;
        fade = pos / 256;

        if (step < 0) index++;
        if (centerIndex != index) {
            centerIndex = index;
            slideFrameInt = index << 16;
            centerSlide.itemIndex = centerIndex;
            for (int i = 0; i < CoverFlowLayout.SIDE_SLIDES; i++) {
                leftSlides[i].itemIndex = centerIndex - 1 - i;
                rightSlides[i].itemIndex = centerIndex + 1 + i;
            }
        }

        centerSlide.angle = (step * tick * CoverFlowLayout.ITILT) >> 16;
        centerSlide.cx = -step * m.offsetX * ftick;
        centerSlide.cy = m.offsetY * ftick;

        if (centerIndex == targetIndex) {
            resetSlides(m);
            slideFrameInt = centerIndex << 16;
            step = 0;
            fade = 256;
            return;
        }

        for (int i = 0; i < CoverFlowLayout.SIDE_SLIDES; i++) {
            CoverFlowLayout.SlidePose si = leftSlides[i];
            si.angle = CoverFlowLayout.ITILT;
            si.cx = -(m.offsetX + m.slideSpacing * i + step * m.slideSpacing * ftick);
            si.cy = m.offsetY;

            si = rightSlides[i];
            si.angle = -CoverFlowLayout.ITILT;
            si.cx = m.offsetX + m.slideSpacing * i - step * m.slideSpacing * ftick;
            si.cy = m.offsetY;
        }

        if (step > 0) {
            float ftickNeg = neg / 65536f;
            rightSlides[0].angle = -(neg * CoverFlowLayout.ITILT) >> 16;
            rightSlides[0].cx = m.offsetX * ftickNeg;
            rightSlides[0].cy = m.offsetY * ftickNeg;
        } else {
            float ftickPos = pos / 65536f;
            leftSlides[0].angle = (pos * CoverFlowLayout.ITILT) >> 16;
            leftSlides[0].cx = -m.offsetX * ftickPos;
            leftSlides[0].cy = m.offsetY * ftickPos;
        }

        if (targetIndex < centerIndex && step > 0) step = -1;
        if (targetIndex > centerIndex && step < 0) step = 1;

        applyScrollAlphas();
    }

    private void applyScrollAlphas() {
        int n = CoverFlowLayout.SIDE_SLIDES;
        if (step == 0) {
            for (int i = 0; i < n; i++) {
                leftSlides[i].alpha = (i == 0) ? 128 : 256;
                rightSlides[i].alpha = (i == 0) ? 128 : 256;
            }
            centerSlide.alpha = 256;
            return;
        }

        int alpha = ((step > 0) ? 0 : 128) - fade / 2;
        for (int index = n - 1; index >= 0; index--) {
            leftSlides[index].alpha = Math.max(0, Math.min(256, alpha));
            alpha += 128;
            if (alpha > 256) alpha = 256;
        }
        alpha = ((step > 0) ? 128 : 0) + fade / 2;
        for (int index = n - 1; index >= 0; index--) {
            rightSlides[index].alpha = Math.max(0, Math.min(256, alpha));
            alpha += 128;
            if (alpha > 256) alpha = 256;
        }
        centerSlide.alpha = 256;
    }

    public int fillDrawOrder(int[] out) {
        int n = 0;
        int startSide = step != 0 ? CoverFlowLayout.SIDE_SLIDES - 1 : CoverFlowLayout.SIDE_SLIDES - 2;
        if (startSide < 0) startSide = 0;
        for (int i = startSide; i >= 0; i--) {
            if (n >= out.length) break;
            CoverFlowLayout.SlidePose p = leftSlides[i];
            if (p.itemIndex >= 0 && p.itemIndex < itemCount && p.alpha > 0) out[n++] = p.itemIndex;
        }
        for (int i = startSide; i >= 0; i--) {
            if (n >= out.length) break;
            CoverFlowLayout.SlidePose p = rightSlides[i];
            if (p.itemIndex >= 0 && p.itemIndex < itemCount && p.alpha > 0) out[n++] = p.itemIndex;
        }
        if (n < out.length && centerSlide.itemIndex >= 0 && centerSlide.itemIndex < itemCount
                && centerSlide.alpha > 0) {
            out[n++] = centerSlide.itemIndex;
        }
        return n;
    }

    private CoverFlowLayout.SlidePose poseForItem(int itemIndex) {
        if (centerSlide.itemIndex == itemIndex) return centerSlide;
        for (int i = 0; i < CoverFlowLayout.SIDE_SLIDES; i++) {
            if (leftSlides[i].itemIndex == itemIndex) return leftSlides[i];
            if (rightSlides[i].itemIndex == itemIndex) return rightSlides[i];
        }
        CoverFlowLayout.SlidePose empty = new CoverFlowLayout.SlidePose();
        empty.itemIndex = -1;
        empty.alpha = 0;
        return empty;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
