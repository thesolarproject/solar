package com.solar.launcher.flow;

/**
 * Apple / Classipod Cover Flow layout — 45° square covers, glossy floor reflection.
 * Scroll physics stay in {@link PictureFlowLayout#rockboxScrollSpeed}; this is pose + metrics only.
 */
public final class CoverFlowLayout {

    public static final int IANGLE_MAX = 1024;
    /** Rockbox default — idle shows ranks 2,1,0 per side + center (~7 visible). */
    public static final int SIDE_SLIDES = 4;
    /** ±58° side tilt — Classipod ~0.9 rad; more face visible than 70° Rockbox tuck. */
    public static final int ITILT = 58 * IANGLE_MAX / 360;
    /** Nearest neighbor offset — fraction of cover width from screen center (iPod fan-out). */
    public static final float NEIGHBOR_CX_FRAC = 0.70f;
    /** Rank 2+ horizontal step — keeps outer neighbors peeking on 480×360. */
    public static final float OUTER_SPACING_FRAC = 0.34f;
    /** Center cover size — room for ±2 tilted neighbors. */
    public static final float DISPLAY_FRAC = 0.42f;
    /** Side tilt — gentler angle so hinge face stays readable. */
    public static final int SIDE_TILT = 50 * IANGLE_MAX / 360;
    /** Side cover scale at full tilt (center stays 1.0). */
    public static final float SIDE_SCALE = 0.92f;
    /** Flipped tracklist card — iPod Classic: ~90% tall, ~84% wide, bottom flush with floor.
     *  Tune against local {@code /reference} Classipod on 480×360 if side margins look off. */
    public static final float BACK_FACE_HEIGHT_FRAC = 0.90f;
    public static final float BACK_FACE_WIDTH_FRAC = 0.84f;
    /** South margin below card bottom (dp) — small gap above screen edge / transport chrome. */
    public static final float BACK_FACE_BOTTOM_MARGIN_DP = 4f;

    private CoverFlowLayout() {}

    public static final class Metrics {
        public final float viewW;
        public final float viewH;
        /** Square cover edge in px. */
        public final float displaySize;
        public final float reflectTop;
        public final float reflectHeight;
        public final float slideSpacing;
        public final float offsetX;
        public final float offsetY;
        public final float camDist;
        public final float centerY;
        public final int[] reflectTable;
        /** Perspective scale for side covers at full tilt (center stays 1.0). */
        public final float sideScale;
        /** Rank 2+ horizontal step — small racks fan wider for 3–5 album peek. */
        public final float outerSpacingFrac;
        /** Side tilt in Rockbox angle units — small racks use gentler face angle. */
        public final int sideTilt;

        Metrics(float viewW, float viewH, float displaySize, float reflectTop, float reflectHeight,
                float slideSpacing, float offsetX, float offsetY, float camDist, float centerY,
                int[] reflectTable, float sideScale, float outerSpacingFrac, int sideTilt) {
            this.viewW = viewW;
            this.viewH = viewH;
            this.displaySize = displaySize;
            this.reflectTop = reflectTop;
            this.reflectHeight = reflectHeight;
            this.slideSpacing = slideSpacing;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.camDist = camDist;
            this.centerY = centerY;
            this.reflectTable = reflectTable;
            this.sideScale = sideScale;
            this.outerSpacingFrac = outerSpacingFrac;
            this.sideTilt = sideTilt;
        }

        /** Decode size for carousel — slightly above on-screen cover, capped for MT6572 RAM. */
        public int thumbSizePx() {
            int px = (int) (displaySize * 1.12f);
            return Math.min(144, Math.max(96, px));
        }
    }

    /** Per-cover pose (compatible with Rockbox scroll animation in {@link FlowEngine}). */
    public static final class SlidePose {
        public int itemIndex = -1;
        public float cx;
        public float cy;
        public int angle;
        /** Continuous Y-rotation for render — avoids int angle stutter on the left rack. */
        public float angleDeg;
        public int alpha = 256;
        public int drawBucket;
        public int sideRank;
    }

    /** One iPod-class layout for all catalog sizes — no small-rack fork. */
    public static Metrics metricsForViewport(float viewW, float viewH) {
        if (viewW <= 0f || viewH <= 0f) {
            return metricsForViewport(480f, 360f);
        }
        // Classic 2/3 art band, 1/3 reflection floor — tuned for 480×360 (no top clip).
        float reflectHeight = viewH / 3f;
        float reflectTop = viewH - reflectHeight;
        float displaySize = Math.min(viewW * DISPLAY_FRAC, viewH * 0.56f);
        float slideSpacing = displaySize * OUTER_SPACING_FRAC;
        float camDist = Math.max(Math.min(viewW, viewH), 120f);
        float offsetX = displaySize * NEIGHBOR_CX_FRAC;
        float offsetY = displaySize * 0.04f;
        float centerY = viewH * 0.33f - viewH * 0.5f;
        int[] reflectTable = PictureFlowLayout.buildReflectTable(reflectHeight);
        return new Metrics(viewW, viewH, displaySize, reflectTop, reflectHeight,
                slideSpacing, offsetX, offsetY, camDist, centerY, reflectTable, SIDE_SCALE,
                OUTER_SPACING_FRAC, SIDE_TILT);
    }

    public static float angleToDegrees(int angleUnits) {
        return angleUnits * 360f / IANGLE_MAX;
    }

    /** iPod inner-edge hinge — positive rotY pivots right, negative pivots left. */
    public static float pivotXForCoverFlow(float rotationYDeg, float left, float right) {
        if (right <= left) return (left + right) * 0.5f;
        float center = (left + right) * 0.5f;
        float t = Math.min(1f, Math.abs(rotationYDeg) / 55f);
        if (rotationYDeg > 0.01f) return center + (right - center) * t;
        if (rotationYDeg < -0.01f) return center + (left - center) * t;
        return center;
    }

    /** How far toward center (0=side, 1=focused) — drives scale and paint order during scroll. */
    public static float centernessFromPose(SlidePose pose, Metrics m) {
        if (pose == null || m.offsetX <= 0f) return 0f;
        float fromCx = 1f - Math.min(1f, Math.abs(pose.cx) / m.offsetX);
        float fromAngle = 1f - Math.min(1f, Math.abs(pose.angleDeg) / angleToDegrees(m.sideTilt));
        return Math.min(1f, Math.max(fromCx, fromAngle));
    }

    /**
     * Classipod / PageView-style pose from continuous carousel offset.
     * {@code relativePos} 0 = front center, +1 = first slot on the right, −1 = first on the left.
     */
    public static SlidePose poseFromRelative(float relativePos, Metrics m) {
        SlidePose out = new SlidePose();
        float abs = Math.abs(relativePos);
        // Deep scroll fade — slots far past the visible rack.
        float deepFadeStart = SIDE_SLIDES + 0.55f;
        float deepFadeEnd = SIDE_SLIDES + 1.05f;
        if (abs > deepFadeEnd) {
            out.alpha = 0;
            return out;
        }
        // Unified floor lerp — negative rel must mirror positive (Classipod continuous page).
        int i0 = (int) Math.floor(relativePos);
        int i1 = i0 + 1;
        float frac = relativePos - i0;
        SlidePose a = keyframePose(i0, m);
        SlidePose b = keyframePose(i1, m);
        lerpPose(out, a, b, frac);
        out.sideRank = Math.min(SIDE_SLIDES, (int) Math.floor(abs));
        // Outer rack egress — endmost covers drift off-screen into shadow instead of popping.
        applyOutboundEgress(out, relativePos, abs, m);
        if (abs > deepFadeStart) {
            float edgeT = (abs - deepFadeStart) / (deepFadeEnd - deepFadeStart);
            out.alpha = (int) (out.alpha * (1f - edgeT));
        }
        return out;
    }

    /** Rank ±2+ slide outward, fade, and tilt away during scroll — iPod-class depth cue. */
    private static void applyOutboundEgress(SlidePose out, float relativePos, float abs, Metrics m) {
        float egressStart = 1.80f;
        float egressEnd = 3.30f;
        if (abs <= egressStart) return;
        float egressT = Math.min(1f, (abs - egressStart) / (egressEnd - egressStart));
        float eased = egressT * egressT * (3f - 2f * egressT);
        float sign = relativePos >= 0f ? 1f : -1f;
        // Keep drifting past the idle rack — no margin clamp so motion continues off-screen.
        out.cx += sign * m.displaySize * (0.28f + 0.62f * eased);
        out.alpha = (int) (out.alpha * (1f - eased * 0.90f));
        float tiltDeg = angleToDegrees(m.sideTilt) * 0.22f * eased;
        out.angleDeg += sign < 0f ? tiltDeg : -tiltDeg;
        out.angle = Math.round(out.angleDeg * IANGLE_MAX / 360f);
        out.cy += m.offsetY * 0.30f * eased;
    }

    /** Horizontal pose offset for side rank (1 = nearest neighbor). Fans outward past rank 1. */
    static float cxForSideRank(int rank, Metrics m) {
        if (rank <= 0) return 0f;
        float first = m.offsetX;
        if (rank == 1) return first;
        // ponytail: quadratic fan — rank 3+ hug viewport margins for scroll cues.
        float step = m.displaySize * (m.outerSpacingFrac > 0f ? m.outerSpacingFrac : OUTER_SPACING_FRAC);
        float cx = first + step * (rank - 1) * (1f + 0.35f * Math.max(0, rank - 2));
        return cx;
    }

    /** Idle / scroll keyframe at integer offset from center (0 = focused cover). */
    static SlidePose keyframePose(int rank, Metrics m) {
        SlidePose p = new SlidePose();
        p.alpha = 256;
        if (rank == 0) {
            p.angle = 0;
            p.angleDeg = 0f;
            p.cx = 0f;
            p.cy = 0f;
            p.sideRank = 0;
            return p;
        }
        if (rank > 0) {
            p.angle = -m.sideTilt;
            p.angleDeg = angleToDegrees(p.angle);
            p.cx = cxForSideRank(rank, m);
            p.cy = m.offsetY;
            p.alpha = 256;
            p.sideRank = rank;
        } else {
            p.angle = m.sideTilt;
            p.angleDeg = angleToDegrees(p.angle);
            int k = -rank;
            p.cx = -cxForSideRank(k, m);
            p.cy = m.offsetY;
            p.alpha = 256;
            p.sideRank = k;
        }
        return p;
    }

    private static void lerpPose(SlidePose out, SlidePose a, SlidePose b, float t) {
        if (t <= 0f) {
            copyPose(out, a);
            return;
        }
        if (t >= 1f) {
            copyPose(out, b);
            return;
        }
        out.cx = a.cx + (b.cx - a.cx) * t;
        out.cy = a.cy + (b.cy - a.cy) * t;
        out.angleDeg = a.angleDeg + (b.angleDeg - a.angleDeg) * t;
        out.angle = Math.round(out.angleDeg * IANGLE_MAX / 360f);
        out.alpha = (int) (a.alpha + (b.alpha - a.alpha) * t);
    }

    private static void copyPose(SlidePose out, SlidePose in) {
        out.cx = in.cx;
        out.cy = in.cy;
        out.angle = in.angle;
        out.angleDeg = in.angleDeg;
        out.alpha = in.alpha;
    }

    public static FlowEngine.SlotTransform toSlotTransformFromRelative(float relativePos, Metrics m) {
        return toSlotTransform(poseFromRelative(relativePos, m), m);
    }

    /** Map slide pose to screen transform for {@link FlowView}. */
    public static FlowEngine.SlotTransform toSlotTransform(SlidePose pose, Metrics m) {
        FlowEngine.SlotTransform t = new FlowEngine.SlotTransform();
        if (pose == null || pose.alpha <= 0) {
            t.alpha = 0f;
            return t;
        }
        float angleRad = (float) Math.toRadians(pose.angleDeg);
        float sinr = (float) Math.sin(angleRad);
        float zo = m.displaySize * 0.5f * Math.abs(sinr);
        float persp = m.camDist / (m.camDist + zo);
        float sideScale = m.sideScale > 0f ? m.sideScale : SIDE_SCALE;
        float centerScale = 1.0f;
        float centerness = centernessFromPose(pose, m);
        float scale = persp * (sideScale + (centerScale - sideScale) * centerness);
        // Nearest neighbors nudge forward so center paint pass does not swallow tilted faces.
        float zForward = 0f;
        if (centerness > 0.05f && centerness < 0.85f) {
            zForward = m.displaySize * 0.06f * (1f - centerness);
        }

        t.width = m.displaySize * scale;
        t.height = m.displaySize * scale;
        t.centerX = m.viewW * 0.5f + pose.cx;
        t.centerY = m.viewH * 0.5f + m.centerY + pose.cy;
        t.rotationYDeg = pose.angleDeg;
        t.zDepth = -zo + zForward;
        t.alpha = pose.alpha / 256f;
        // Shadow recession — outer rack dims and sinks as covers leave the lit center.
        float rackDist = m.offsetX > 0f ? Math.abs(pose.cx) / m.offsetX : 0f;
        t.shadowStrength = Math.min(1f, Math.max(0f, (rackDist - 0.55f) / 1.65f));
        if (t.shadowStrength > 0.01f) {
            float sh = t.shadowStrength;
            t.zDepth -= m.displaySize * 0.10f * sh;
            float shrink = 1f - sh * 0.07f;
            t.width *= shrink;
            t.height *= shrink;
            t.alpha *= 1f - sh * 0.18f;
        }
        // Inner-edge pivot like Classipod — avoids full-texture spin that reads as a 3D cube.
        if (pose.angle > m.sideTilt / 6) {
            t.pivotXFrac = 1f;
        } else if (pose.angle < -m.sideTilt / 6) {
            t.pivotXFrac = 0f;
        } else {
            t.pivotXFrac = 0.5f;
        }
        // Continuous depth: focused cover advances with scroll, no end-of-scroll pop.
        t.depthOrder = depthOrderFromPose(pose, m);
        return t;
    }

    /** Paint-order key only — cheaper than full {@link #toSlotTransform} during sort. */
    public static float depthOrderFromPose(SlidePose pose, Metrics m) {
        if (pose == null || pose.alpha <= 0) return Float.MAX_VALUE;
        float centerness = centernessFromPose(pose, m);
        // Centerness promotes scroll-incoming covers; sideRank pushes outer rack members back.
        return centerness * 800f - pose.sideRank * 50f;
    }

    public static int reflectionAlpha(Metrics m, int row, float coverAlpha) {
        if (m.reflectTable == null || row < 0 || row >= m.reflectTable.length) return 0;
        int lalpha = m.reflectTable[row];
        return (lalpha * Math.round(coverAlpha * 256) + 129) >> 8;
    }

    /**
     * iPod Classic: once the flip passes midpoint, the back face grows and slides south so the
     * bottom edge nearly touches the content floor — "brought the record closer to read tracks."
     *
     * @param flipPastMid 0 at 90° (mid-flip), 1 when fully on back face
     * @param topInsetPx    status bar + small margin — card top stays below this
     * @param bottomMarginPx gap above physical bottom (dp→px)
     */
    public static void applyBackFaceSouthEncroach(FlowEngine.SlotTransform t, Metrics m,
            float flipPastMid, float topInsetPx, float bottomMarginPx) {
        if (t == null || m == null || flipPastMid <= 0f) return;
        float eased = flipPastMid * flipPastMid * (3f - 2f * flipPastMid);
        float contentTop = Math.max(0f, topInsetPx);
        float contentBottom = m.viewH - Math.max(0f, bottomMarginPx);
        float availableH = Math.max(0f, contentBottom - contentTop);
        float targetH = Math.min(m.viewH * BACK_FACE_HEIGHT_FRAC, availableH);
        float targetW = m.viewW * BACK_FACE_WIDTH_FRAC;
        float targetBottom = contentBottom;
        float targetCenterY = targetBottom - targetH * 0.5f;
        float targetTop = targetCenterY - targetH * 0.5f;
        if (targetTop < contentTop) {
            targetCenterY = contentTop + targetH * 0.5f;
        }
        t.centerY += (targetCenterY - t.centerY) * eased;
        t.width += (targetW - t.width) * eased;
        t.height += (targetH - t.height) * eased;
    }

    /**
     * Legacy 3D handoff path — square back face (unused; rectangle encroach is canonical).
     */
    public static void applySquareBackFaceSouthAnchor(FlowEngine.SlotTransform t, Metrics m,
            float flipPastMid, float topInsetPx, float bottomMarginPx) {
        if (t == null || m == null || flipPastMid <= 0f) return;
        float eased = flipPastMid * flipPastMid * (3f - 2f * flipPastMid);
        float contentTop = Math.max(0f, topInsetPx);
        float contentBottom = m.viewH - Math.max(0f, bottomMarginPx);
        float availableH = Math.max(0f, contentBottom - contentTop);
        float targetSize = Math.min(m.viewW * BACK_FACE_WIDTH_FRAC, availableH);
        targetSize = Math.min(targetSize, m.displaySize * 1.12f);
        float targetCenterY = contentBottom - targetSize * 0.5f;
        float targetTop = targetCenterY - targetSize * 0.5f;
        if (targetTop < contentTop) {
            targetCenterY = contentTop + targetSize * 0.5f;
        }
        t.centerY += (targetCenterY - t.centerY) * eased;
        t.width += (targetSize - t.width) * eased;
        t.height += (targetSize - t.height) * eased;
    }

    /** iPod-style: side albums fan out and recede while center card flips — extra spread on back face. */
    public static void applyFlipFanOut(FlowEngine.SlotTransform t, float poseCx, float flipP) {
        if (t == null || flipP <= 0f) return;
        float sign = poseCx >= 0f ? 1f : -1f;
        // Browse uses base rack spread; track-list back face fans wider for background cue.
        float spreadMul = 0.50f + flipP * 0.38f;
        float spread = Math.abs(poseCx) * flipP * spreadMul;
        t.centerX += sign * spread;
        float shrink = 1f - flipP * 0.14f;
        t.width *= shrink;
        t.height *= shrink;
        t.zDepth -= flipP * 35f;
        t.depthOrder -= flipP * 120f;
    }
}
