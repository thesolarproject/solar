package com.solar.launcher.flow;

/**
 * Apple / Classipod Cover Flow layout — 45° square covers, glossy floor reflection.
 * Scroll physics stay in {@link PictureFlowLayout#rockboxScrollSpeed}; this is pose + metrics only.
 */
public final class CoverFlowLayout {

    public static final int IANGLE_MAX = 1024;
    /** 3 side covers each direction + center = 7 visible. */
    public static final int SIDE_SLIDES = 3;
    /** ±45° side tilt (Addy Osmani / iPod Classic keyframes). */
    public static final int ITILT = 45 * IANGLE_MAX / 360;

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

        Metrics(float viewW, float viewH, float displaySize, float reflectTop, float reflectHeight,
                float slideSpacing, float offsetX, float offsetY, float camDist, float centerY,
                int[] reflectTable) {
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
        }

        public int thumbSizePx() {
            return Math.min(256, Math.max(128, Math.round(displaySize * 1.05f)));
        }
    }

    /** Per-cover pose (compatible with Rockbox scroll animation in {@link FlowEngine}). */
    public static final class SlidePose {
        public int itemIndex = -1;
        public float cx;
        public float cy;
        public int angle;
        public int alpha = 256;
        public int drawBucket;
        public int sideRank;
    }

    public static Metrics metricsForViewport(float viewW, float viewH) {
        if (viewW <= 0f || viewH <= 0f) {
            return metricsForViewport(480f, 360f);
        }
        float reflectHeight = viewH * 0.35f;
        float reflectTop = viewH - reflectHeight;
        float displaySize = Math.min(viewW * 0.36f, viewH * 0.48f);
        float slideSpacing = displaySize * 0.28f;
        float offsetX = displaySize * 0.52f + slideSpacing * 0.15f;
        float offsetY = displaySize * 0.06f;
        float camDist = Math.max(Math.min(viewW, viewH), 120f);
        float centerY = viewH * 0.42f - viewH * 0.5f;
        int[] reflectTable = PictureFlowLayout.buildReflectTable(reflectHeight);
        return new Metrics(viewW, viewH, displaySize, reflectTop, reflectHeight,
                slideSpacing, offsetX, offsetY, camDist, centerY, reflectTable);
    }

    public static float angleToDegrees(int angleUnits) {
        return angleUnits * 360f / IANGLE_MAX;
    }

    /** Map slide pose to screen transform for {@link FlowView}. */
    public static FlowEngine.SlotTransform toSlotTransform(SlidePose pose, Metrics m, boolean isCenter) {
        FlowEngine.SlotTransform t = new FlowEngine.SlotTransform();
        if (pose == null || pose.itemIndex < 0 || pose.alpha <= 0) {
            t.alpha = 0f;
            return t;
        }
        float angleRad = (float) Math.toRadians(angleToDegrees(pose.angle));
        float sinr = (float) Math.sin(angleRad);
        float cosr = (float) Math.cos(angleRad);
        float zo = m.displaySize * 0.5f * Math.abs(sinr);
        float persp = m.camDist / (m.camDist + zo);
        float scale = persp * (isCenter ? 1.08f : 0.92f);

        t.width = m.displaySize * scale;
        t.height = m.displaySize * scale;
        t.centerX = m.viewW * 0.5f + pose.cx;
        t.centerY = m.viewH * 0.5f + m.centerY + pose.cy;
        t.rotationYDeg = -angleToDegrees(pose.angle);
        t.zDepth = -zo;
        t.alpha = pose.alpha / 256f;
        t.depthOrder = pose.drawBucket * 10f + pose.sideRank;
        return t;
    }

    public static int reflectionAlpha(Metrics m, int row, float coverAlpha) {
        if (m.reflectTable == null || row < 0 || row >= m.reflectTable.length) return 0;
        int lalpha = m.reflectTable[row];
        return (lalpha * Math.round(coverAlpha * 256) + 129) >> 8;
    }
}
