package com.solar.launcher.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.solar.launcher.contracts.SolarSkin;

/** Shared Y1-style list row builder for feature modules. */
public final class Y1RowFactory {
    private Y1RowFactory() {}

    public static TextView createLabelRow(Context ctx, SolarSkin skin, String title, String subtitle) {
        TextView row = new TextView(ctx);
        row.setFocusable(true);
        row.setClickable(true);
        if (subtitle != null && subtitle.length() > 0) {
            row.setText(title + "\n" + subtitle);
        } else {
            row.setText(title);
        }
        skin.applyThemedText(row, true);
        View bg = new View(ctx);
        if (row.getBackground() == null) {
            row.setBackground(skin.rowBackground(SolarSkin.Y1RowStyle.ITEM));
        }
        return row;
    }
}
