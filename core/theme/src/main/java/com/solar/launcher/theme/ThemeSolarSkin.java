package com.solar.launcher.theme;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import com.solar.launcher.contracts.SolarSkin;

/** SolarSkin facade over static ThemeManager — single theming entry for feature modules. */
public final class ThemeSolarSkin implements SolarSkin {
    public static final ThemeSolarSkin INSTANCE = new ThemeSolarSkin();
    private static Context appContext;
    private static final int ROW_W = 480;
    private static final int ROW_H = 56;

    private ThemeSolarSkin() {}

    public static void bind(Context context) {
        appContext = context != null ? context.getApplicationContext() : null;
    }

    private static Resources res() {
        return appContext != null ? appContext.getResources() : null;
    }

    @Override public int getTextColorPrimary() { return ThemeManager.getTextColorPrimary(); }
    @Override public int getTextColorSecondary() { return ThemeManager.getTextColorSecondary(); }
    @Override public int getOverlayBackgroundColor() { return ThemeManager.getOverlayBackgroundColor(); }
    @Override public int getStatusBarBackgroundColor() { return ThemeManager.getStatusBarBackgroundColor(); }
    @Override public int getStatusBarTextColor() { return ThemeManager.getStatusBarTextColor(); }
    @Override public int getRowSelectionFillColor() { return ThemeManager.getRowSelectionFillColor(); }
    @Override public int getContextMenuPanelColor() { return ThemeManager.getContextMenuPanelColor(); }
    @Override public int getListButtonNormalBg() { return ThemeManager.getListButtonNormalBg(); }
    @Override public int getListButtonFocusedBg() { return ThemeManager.getListButtonFocusedBg(); }
    @Override public int getListButtonFocusedTextColor() { return ThemeManager.getListButtonFocusedTextColor(); }
    @Override public Typeface getCustomFont() { return ThemeManager.getCustomFont(); }
    @Override public void applyThemedText(TextView view, boolean primary) {
        ThemeManager.applyThemedTextStyle(view, primary ? getTextColorPrimary() : getTextColorSecondary());
    }
    @Override public Drawable getMenuRowBackground() {
        Resources r = res();
        return r != null ? ThemeManager.getMenuRowBackgroundScaled(r, false, ROW_W, ROW_H) : null;
    }
    @Override public Drawable getItemRowBackground() {
        Resources r = res();
        return r != null ? ThemeManager.getItemRowBackgroundScaled(r, false, ROW_W, ROW_H) : null;
    }
    @Override public Drawable getHomeRowBackground() { return getItemRowBackground(); }
    @Override public Drawable rowBackground(Y1RowStyle style) {
        if (style == Y1RowStyle.MENU) return getMenuRowBackground();
        return getItemRowBackground();
    }
}
