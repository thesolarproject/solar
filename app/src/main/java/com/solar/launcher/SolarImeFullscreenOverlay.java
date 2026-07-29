package com.solar.launcher;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import com.solar.launcher.theme.ThemeManager;
import com.solar.launcher.theme.ThemeSnapshotBridge;

/**
 * 2026-07-05 — Full-screen themed keyboard WM overlay for system IME.
 * Layman: same keyboard screen as in Solar app, drawn over Rockbox/apps.
 * Technical: shell layout + text-only commits; keys routed via SolarImeKeyGate.
 */
public final class SolarImeFullscreenOverlay {

    private final Context context;
    private final SolarWheelKeyboardController controller;
    private final String enterLabel;

    private WindowManager windowManager;
    private View shellRoot;
    private SolarKeyboardShellHost shellHost;

    public SolarImeFullscreenOverlay(Context context, SolarWheelKeyboardController controller) {
        this.context = context.getApplicationContext();
        this.controller = controller;
        this.enterLabel = context.getString(R.string.keyboard_enter);
        ThemeSnapshotBridge.loadIntoThemeManager(this.context);
    }

    /** Paint full-screen keyboard shell — opaque themed background covers app entirely. */
    public void show(String title) {
        if (shellRoot != null) {
            refresh(title);
            return;
        }
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        LayoutInflater inflater = LayoutInflater.from(context);
        shellRoot = inflater.inflate(R.layout.layout_solar_keyboard_shell, null);
        shellHost = new SolarKeyboardShellHost(context, shellRoot, enterLabel);
        refresh(title);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                SolarOverlayService.globalOverlayWindowFlags(),
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        windowManager.addView(shellRoot, lp);
        SolarImeRouteArbiter.setTrayUiVisible(true);
        ThemeManager.ensureOverlayPaintableMinimum(context);
        // Touch attach lives on A5 real input view (SolarInputMethodService) — WM overlay
        // is Y1/Y2 and has no touchscreen; centerPress alone would skip InputConnection commit.
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("title", title);
            d.put("shellAttached", shellRoot != null);
            d.put("trayUi", SolarImeRouteArbiter.isTrayUiVisible());
            DebugImeLog.log(context, "SolarImeFullscreenOverlay.show", "wm addView", "H4", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /** Remove WM overlay. */
    public void dismiss() {
        boolean hadShell = shellRoot != null;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("hadShell", hadShell);
            d.put("hadWm", windowManager != null);
            d.put("trayUiBefore", SolarImeRouteArbiter.isTrayUiVisible());
            DebugImeLog.log(context, "SolarImeFullscreenOverlay.dismiss", "enter", "H5", d);
        } catch (Exception ignored) {}
        // #endregion
        if (windowManager != null && shellRoot != null) {
            try {
                windowManager.removeView(shellRoot);
            } catch (Exception e) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("err", e.getClass().getSimpleName());
                    DebugImeLog.log(context, "SolarImeFullscreenOverlay.dismiss", "removeView failed", "H5", d);
                } catch (Exception ignored) {}
                // #endregion
            }
        }
        shellRoot = null;
        shellHost = null;
        windowManager = null;
        SolarImeRouteArbiter.setTrayUiVisible(false);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("trayUiAfter", SolarImeRouteArbiter.isTrayUiVisible());
            DebugImeLog.log(context, "SolarImeFullscreenOverlay.dismiss", "exit", "H5", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    public boolean isShowing() {
        return shellRoot != null;
    }

    /** Sync strip + input preview from controller buffer. */
    public void refresh(String title) {
        if (shellHost == null || controller == null) return;
        // 2026-07-20 — Status clock via shell; no purpose subtitle; type hint only in field.
        shellHost.applyShellTheme(title != null ? title : "");
        String buffer = controller.getBuffer();
        String placeholder = context.getString(R.string.solar_ime_type_hint);
        String display = buffer.length() == 0
                ? placeholder : controller.renderBuffer(true);
        shellHost.getKeyboardUi().refresh(controller, null, display, buffer.length() == 0);
    }
}
