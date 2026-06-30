package com.solar.launcher;

import com.solar.launcher.theme.ThemeManager;

/**
 * Theme-derived colours for LCD dither album art.
 * ponytail: list row bg + tint; off pixels transparent (negative space).
 */
public final class LcdArtPalette {

    public final int onColor;
    public final int midColor;
    /** Always transparent — dark dither cells show theme bg through ImageView. */
    public final int offColor;

    public LcdArtPalette(int onColor, int midColor, int offColor) {
        this.onColor = onColor;
        this.midColor = midColor;
        this.offColor = offColor;
    }

    /** Build palette from current theme list bg + Now Playing tint. */
    public static LcdArtPalette fromTheme() {
        int bg = ThemeManager.getListButtonNormalBg();
        int on = ThemeManager.getAlbumArtTintColor();
        // RGB only — per-pixel alpha comes from source luminance in AlbumArtLcdFilter.
        int mid = blend(on, bg, 0.45f);
        return new LcdArtPalette(on, mid, 0);
    }

    /** Test / injectable factory. */
    public static LcdArtPalette of(int bg, int tint) {
        int mid = blend(tint, bg, 0.45f);
        return new LcdArtPalette(tint, mid, 0);
    }

    private static int blend(int fg, int bg, float fgWeight) {
        float w = fgWeight < 0f ? 0f : (fgWeight > 1f ? 1f : fgWeight);
        int r = (int) (red(fg) * w + red(bg) * (1f - w));
        int g = (int) (green(fg) * w + green(bg) * (1f - w));
        int b = (int) (blue(fg) * w + blue(bg) * (1f - w));
        return rgb(clamp8(r), clamp8(g), clamp8(b));
    }

    private static int red(int c) { return (c >> 16) & 0xFF; }
    private static int green(int c) { return (c >> 8) & 0xFF; }
    private static int blue(int c) { return c & 0xFF; }
    private static int rgb(int r, int g, int b) { return (r << 16) | (g << 8) | b; }

    private static int clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
