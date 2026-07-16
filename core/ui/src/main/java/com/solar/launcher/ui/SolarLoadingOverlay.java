package com.solar.launcher.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.solar.launcher.contracts.SolarSkin;

/** Themed loading overlay — attach to any feature root or window overlay. */
public final class SolarLoadingOverlay extends FrameLayout {
    public SolarLoadingOverlay(Context context, SolarSkin skin, String message) {
        super(context);
        setClickable(true);
        setBackgroundColor(skin.getOverlayBackgroundColor());
        ProgressBar spinner = new ProgressBar(context);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(spinner, lp);
        if (message != null && message.length() > 0) {
            TextView tv = new TextView(context);
            tv.setText(message);
            skin.applyThemedText(tv, true);
            LayoutParams tlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            tlp.gravity = Gravity.CENTER;
            tlp.topMargin = 48;
            addView(tv, tlp);
        }
    }
}
