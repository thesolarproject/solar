package com.solar.launcher.keyboard;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solar.launcher.contracts.SolarSkin;
import com.solar.launcher.theme.ThemeSolarSkin;

/** Wheel keyboard surface — FULL (in launcher) or COMPACT (IME modal). */
public final class SolarKeyboardView extends LinearLayout {
    public enum Mode { FULL, COMPACT }

    private final Mode mode;
    private final KeyboardEngine engine;
    private final TextView preview;
    private final SolarSkin skin = ThemeSolarSkin.INSTANCE;

    public SolarKeyboardView(Context context, Mode mode, KeyboardEngine engine) {
        super(context);
        this.mode = mode;
        this.engine = engine;
        setOrientation(VERTICAL);
        int pad = mode == Mode.COMPACT ? 6 : 12;
        setPadding(pad, pad, pad, pad);
        setBackgroundColor(skin.getOverlayBackgroundColor());

        preview = new TextView(context);
        preview.setTextSize(mode == Mode.COMPACT ? 14f : 18f);
        skin.applyThemedText(preview, true);
        preview.setText(engine.displayText());
        addView(preview, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(context);
        hint.setTextSize(12f);
        skin.applyThemedText(hint, false);
        hint.setText(mode == Mode.COMPACT ? "Solar wheel keyboard" : "Wheel keyboard");
        addView(hint, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setGravity(Gravity.CENTER_HORIZONTAL);
    }

    public void refreshPreview() {
        preview.setText(engine.displayText());
    }

    public Mode getMode() { return mode; }
    public KeyboardEngine getEngine() { return engine; }
}
