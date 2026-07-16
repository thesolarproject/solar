package com.solar.launcher.contracts;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

/** Read-only themed decoration facade — wraps ThemeManager for feature UI. */
public interface SolarSkin {
    int getTextColorPrimary();
    int getTextColorSecondary();
    int getOverlayBackgroundColor();
    int getStatusBarBackgroundColor();
    int getStatusBarTextColor();
    int getRowSelectionFillColor();
    int getContextMenuPanelColor();
    int getListButtonNormalBg();
    int getListButtonFocusedBg();
    int getListButtonFocusedTextColor();

    Typeface getCustomFont();

    void applyThemedText(TextView view, boolean primary);

    Drawable getMenuRowBackground();
    Drawable getItemRowBackground();
    Drawable getHomeRowBackground();

    enum Y1RowStyle { HOME, MENU, ITEM }
    Drawable rowBackground(Y1RowStyle style);
}
