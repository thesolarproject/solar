package com.solar.launcher.flow;

/**
 * Rockbox PictureFlow layout + scroll math (pictureflow.c).
 * ponytail: float port of 16.16 fixed-point; column projection approximated in FlowView via Camera.
 */
public final class PictureFlowLayout {

    /** Rockbox {@code IANGLE_MAX} — full circle in angle table units. */
    public static final int IANGLE_MAX = 1024;
    /** Default side stacks (Rockbox {@code num_slides = 4}). */
    public static final int SIDE_SLIDES = 4;
    /** 70° cover tilt ({@code itilt = 70 * IANGLE_MAX / 360}). */
    public static final int ITILT = 70 * IANGLE_MAX / 360;

    private PictureFlowLayout() {}

    /** Viewport metrics mirroring Rockbox {@code DISPLAY_*} / {@code CAM_DIST} macros. */
    public static final class Metrics {
        public final float viewW;
        public final float viewH;
        /** Cover width in px ({@code DISPLAY_WIDTH}). */
        public final float displayWidth;
        /** Cover height in px ({@code DISPLAY_HEIGHT = REFLECT_TOP}). */
        public final float displayHeight;
        /** Y where reflection begins ({@code REFLECT_TOP}). */
        public final float reflectTop;
        /** Reflection band height ({@code REFLECT_HEIGHT}). */
        public final float reflectHeight;
        public final float slideSpacing;
        public final float centerMargin;
        public final float camDist;
        public final float offsetX;
        public final float offsetY;
        /** Per-row reflection alpha 0..768 (Rockbox {@code reflect_table}). */
        public final int[] reflectTable;

        Metrics(float viewW, float viewH, float displayWidth, float displayHeight,
                float reflectTop, float reflectHeight, float slideSpacing, float centerMargin,
                float camDist, float offsetX, float offsetY, int[] reflectTable) {
            this.viewW = viewW;
            this.viewH = viewH;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.reflectTop = reflectTop;
            this.reflectHeight = reflectHeight;
            this.slideSpacing = slideSpacing;
            this.centerMargin = centerMargin;
            this.camDist = camDist;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.reflectTable = reflectTable;
        }

        /** Thumb decode size — cover width in px, clamped for memory. */
        public int thumbSizePx() {
            return Math.min(256, Math.max(128, Math.round(displayWidth)));
        }
    }

    /** Per-cover pose during idle or scroll (Rockbox {@code slide_data}). */
    public static final class SlidePose {
        public int itemIndex = -1;
        /** Horizontal offset from screen center in px ({@code cx}). */
        public float cx;
        /** Vertical offset from screen center in px ({@code cy}). */
        public float cy;
        /** Rockbox angle units ({@code IANGLE_MAX} = 360°). */
        public int angle;
        /** Rockbox alpha 0..256. */
        public int alpha = 256;
        /** Draw bucket: 0=left back … 3=center (drawn last). */
        public int drawBucket;
        public int sideRank;
    }

    public static Metrics metricsForViewport(float viewW, float viewH) {
        if (viewW <= 0f || viewH <= 0f) {
            return metricsForViewport(480f, 360f);
        }
        float reflectTop = viewH * 2f / 3f;
        float reflectHeight = viewH - reflectTop;
        float displayHeight = reflectTop;
        // Rockbox: MAX(h*aspect/2, w*2/5) — Y1 is square pixels so aspect term ≈ h/2.
        float displayWidth = Math.max(viewH * 0.5f, viewW * 0.4f);
        float slideSpacing = displayWidth / 4f;
        float centerMargin = (viewW - displayWidth) / 12f;
        float camDist = Math.max(Math.min(viewW, viewH), 120f);
        float zoom = 100f;
        float offsetX = recalcOffsetX(displayWidth, centerMargin, zoom, camDist);
        float offsetY = displayWidth * 0.5f * (fsin(ITILT) + 0.5f);
        int[] reflectTable = buildReflectTable(reflectHeight);
        return new Metrics(viewW, viewH, displayWidth, displayHeight, reflectTop, reflectHeight,
                slideSpacing, centerMargin, camDist, offsetX, offsetY, reflectTable);
    }

    /**
     * Rockbox {@code recalc_offsets} — slide center offset so side covers tuck with correct margin.
     */
    static float recalcOffsetX(float displayWidth, float centerMargin, float zoomPercent, float camDist) {
        float xs = displayWidth * 0.5f;
        float xp = (displayWidth * 0.5f + centerMargin) * zoomPercent / 100f;
        float itiltRad = (float) Math.toRadians(70);
        float cosr = (float) Math.cos(-itiltRad);
        float sinr = (float) Math.sin(-itiltRad);
        float maxSlideLeft = displayWidth * 0.5f;
        float zo = camDist * 100f / zoomPercent - camDist + maxSlideLeft * Math.abs(sinr);
        return xp - xs * cosr + xp * (zo + xs * sinr) / camDist;
    }

    /** Max reflection bands — full viewH/3 (~120) is too heavy for bake + per-row draw on MT6572. */
    static final int REFLECT_TABLE_MAX_ROWS = 28;

    /** Rockbox {@code init_reflect_table} — capped row count for carousel perf. */
    static int[] buildReflectTable(float reflectHeight) {
        int h = Math.max(1, Math.min(REFLECT_TABLE_MAX_ROWS, Math.round(reflectHeight)));
        int[] table = new int[h];
        for (int i = 0; i < h; i++) {
            table[i] = (768 * (h - i)) / h;
        }
        return table;
    }

    /** Rockbox sin table sample — {@code fsin(ia) / PFREAL_ONE}. */
    static float fsin(int iangle) {
        double deg = (iangle & (IANGLE_MAX - 1)) * 360.0 / IANGLE_MAX;
        return (float) Math.sin(Math.toRadians(deg));
    }

    /** Scroll speed in 16.16 units per timer tick ({@code update_scroll_animation}). */
    public static int rockboxScrollSpeed(int slideFrameInt, int targetIndex) {
        int speed = 16384;
        int max = 2 * 65536;
        int fi = slideFrameInt - (targetIndex << 16);
        if (fi < 0) fi = -fi;
        if (fi > max) fi = max;
        int ia = IANGLE_MAX * (fi - max / 2) / (max * 2);
        speed = 512 + (16384 * (1024 + (int) (fsin(ia) * 1024))) / 1024;
        return speed;
    }

    /** Convert Rockbox angle units to degrees for Android {@code Camera.rotateY}. */
    public static float angleToDegrees(int angleUnits) {
        return angleUnits * 360f / IANGLE_MAX;
    }

    /** Map slide pose to screen-space transform for FlowView. */
    public static FlowEngine.SlotTransform toSlotTransform(SlidePose pose, Metrics m) {
        FlowEngine.SlotTransform t = new FlowEngine.SlotTransform();
        if (pose == null || pose.itemIndex < 0 || pose.alpha <= 0) {
            t.alpha = 0f;
            return t;
        }
        float angleRad = (float) Math.toRadians(angleToDegrees(pose.angle));
        float sinr = (float) Math.sin(angleRad);
        float cosr = (float) Math.cos(angleRad);
        float maxSlideLeft = m.displayWidth * 0.5f;
        float zo = m.camDist * 0f + maxSlideLeft * Math.abs(sinr);
        float persp = m.camDist / (m.camDist + zo);
        float colScale = persp * (float) Math.sqrt(Math.abs(cosr) + 0.15f);

        t.width = m.displayWidth * colScale;
        t.height = m.displayHeight * colScale;
        t.centerX = m.viewW * 0.5f + pose.cx;
        t.centerY = m.viewH * 0.5f + pose.cy;
        t.rotationYDeg = -angleToDegrees(pose.angle);
        t.zDepth = -zo;
        t.alpha = pose.alpha / 256f;
        // Center drawn last; left/right back covers first.
        t.depthOrder = pose.drawBucket * 10f + pose.sideRank;
        return t;
    }
}
