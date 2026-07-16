package com.solar.keyboard;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.solar.launcher.keyboard.KeyboardEngine;
import com.solar.launcher.keyboard.SolarKeyboardView;
import com.solar.launcher.theme.ThemeManager;
import com.solar.launcher.theme.ThemeSolarSkin;

/** Compact wheel keyboard IME — shares KeyboardEngine with in-app full keyboard. */
public class SolarInputMethodService extends InputMethodService {
    private SolarKeyboardView keyboardView;
    private KeyboardEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeSolarSkin.bind(getApplicationContext());
        ThemeManager.ensureBundledDefault(this);
        ThemeManager.loadAllThemes(this);
    }

    @Override
    public View onCreateInputView() {
        engine = new KeyboardEngine(new KeyboardEngine.Callback() {
            @Override public void onTextChanged(String text) {
                if (keyboardView != null) keyboardView.refreshPreview();
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.deleteSurroundingText(Integer.MAX_VALUE, Integer.MAX_VALUE);
                    ic.commitText(text, 1);
                }
            }
            @Override public void onCommit(String text) {
                requestHideSelf(0);
            }
        }, "", 128, false);
        keyboardView = new SolarKeyboardView(this, SolarKeyboardView.Mode.COMPACT, engine);
        return keyboardView;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        if (engine != null && keyboardView != null) keyboardView.refreshPreview();
    }
}
