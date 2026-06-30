package com.solar.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/** Low-res ordered-dither album art — hi-fi LCD / CD player display look. */
public final class AlbumArtLcdFilter {
    /** Target width before upscale (ponytail: fixed cell size, blocky on upscale). */
    private static final int LCD_WIDTH = 80;

    /** 4×4 Bayer matrix for ordered dithering. */
    private static final int[][] BAYER = {
            {0, 8, 2, 10},
            {12, 4, 14, 6},
            {3, 11, 1, 9},
            {15, 7, 13, 5}
    };

    /** Mean luminance above this → treat cover as light-dominant and invert before dither. */
    private static final int LIGHT_COVER_AVG_LUM = 132;
    /** Luminance bucket for "dark" pixels when measuring cover dominance. */
    private static final int DARK_LUM_CUTOFF = 96;

    private AlbumArtLcdFilter() {}

    static int luminance(int argb) {
        return (Color.red(argb) * 299 + Color.green(argb) * 587 + Color.blue(argb) * 114) / 1000;
    }

    /** Testable dither level: 0 off, 1 mid, 2 on. */
    static int ditherLevel(int lum, int x, int y) {
        int threshold = (BAYER[y & 3][x & 3] * 255) / 16;
        if (lum > threshold + 40) return 2;
        if (lum > threshold) return 1;
        return 0;
    }

    /**
     * Light covers (mostly white) invert so highlights become transparent negative space
     * and dark subject lines read as opaque LCD pixels.
     */
    static boolean shouldInvertLuminance(int[] lums) {
        if (lums == null || lums.length == 0) return false;
        long sum = 0;
        int bright = 0;
        int dark = 0;
        for (int lum : lums) {
            sum += lum;
            if (lum >= 190) bright++;
            else if (lum <= DARK_LUM_CUTOFF) dark++;
        }
        int avg = (int) (sum / lums.length);
        if (avg >= LIGHT_COVER_AVG_LUM) return true;
        // High-key with sparse dark subject (e.g. white cover + portrait silhouette).
        return bright > lums.length / 3 && bright > dark * 2;
    }

    /** 0–255: how dark-dominant the source is — drives mid-tone alpha crush. */
    static int darkDominance(int[] lums) {
        if (lums == null || lums.length == 0) return 0;
        int dark = 0;
        for (int lum : lums) {
            if (lum <= DARK_LUM_CUTOFF) dark++;
        }
        return (dark * 255) / lums.length;
    }

    /**
     * Mid-tone dither alpha scales with source luminance — dark, frequent cells fade out
     * so pixel art floats on the Now Playing background instead of filling a gray box.
     */
    static int midAlphaForLuminance(int lum, int darkDominance) {
        if (lum <= 56) return 0;
        if (lum >= 192) return scaleByDominance(0x99, darkDominance);
        int t = lum - 56;
        int span = 192 - 56;
        int base = (t * t * 0x99) / (span * span);
        return scaleByDominance(base, darkDominance);
    }

    /** Dark-dominant covers crush mid alphas so the frequent shadow tones melt into the bg. */
    private static int scaleByDominance(int alpha, int darkDominance) {
        if (alpha <= 0 || darkDominance <= 0) return alpha;
        int keep = 255 - (darkDominance * 3) / 4;
        if (keep < 48) keep = 48;
        return (alpha * keep) / 255;
    }

    static int pixelArgb(int level, int lum, LcdArtPalette palette, int darkDominance) {
        if (level == 2) {
            int alpha = onAlphaForLuminance(lum, darkDominance);
            if (alpha <= 0) return 0;
            return (alpha << 24) | (palette.onColor & 0x00FFFFFF);
        }
        if (level == 1) {
            int alpha = midAlphaForLuminance(lum, darkDominance);
            if (alpha <= 0) return 0;
            return (alpha << 24) | (palette.midColor & 0x00FFFFFF);
        }
        return palette.offColor;
    }

    /** Highlight pixels stay punchy; borderline dither "on" cells fade on dark-dominant covers. */
    static int onAlphaForLuminance(int lum, int darkDominance) {
        if (lum < 140) {
            if (darkDominance > 160) return 0;
            return lum < 100 ? 0 : 0xAA;
        }
        if (lum < 210) return 0xEE;
        return 0xFF;
    }

    public static Bitmap apply(Bitmap source, int tintColor, int targetW, int targetH) {
        return apply(source, LcdArtPalette.fromTheme(), targetW, targetH);
    }

    /**
     * Apply LCD dither filter and upscale to target size.
     * @param palette theme on/mid/off (off = transparent negative space)
     */
    public static Bitmap apply(Bitmap source, LcdArtPalette palette, int targetW, int targetH) {
        if (source == null || targetW <= 0 || targetH <= 0) return source;
        if (palette == null) palette = LcdArtPalette.fromTheme();
        int sw = source.getWidth();
        int sh = source.getHeight();
        if (sw <= 0 || sh <= 0) return source;

        int smallW = LCD_WIDTH;
        int smallH = Math.max(1, (int) (sh * (smallW / (float) sw)));
        Bitmap small = Bitmap.createScaledBitmap(source, smallW, smallH, false);

        int[] pixels = new int[smallW * smallH];
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH);
        if (small != source) small.recycle();

        int[] lums = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            lums[i] = luminance(pixels[i]);
        }
        boolean invert = shouldInvertLuminance(lums);
        int dominance = invert ? 0 : darkDominance(lums);

        for (int y = 0; y < smallH; y++) {
            for (int x = 0; x < smallW; x++) {
                int idx = y * smallW + x;
                int lum = invert ? (255 - lums[idx]) : lums[idx];
                int level = ditherLevel(lum, x, y);
                pixels[idx] = pixelArgb(level, lum, palette, dominance);
            }
        }

        Bitmap dithered = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888);
        dithered.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH);

        Bitmap out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        out.eraseColor(Color.TRANSPARENT);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setFilterBitmap(false);
        canvas.drawBitmap(dithered, null,
                new android.graphics.Rect(0, 0, targetW, targetH), paint);
        dithered.recycle();
        return out;
    }
}
