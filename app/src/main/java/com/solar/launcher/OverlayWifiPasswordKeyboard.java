package com.solar.launcher;

import android.content.Context;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.solar.launcher.theme.ThemeManager;

/**
 * 2026-07-06 — Wi-Fi password wheel keyboard inside global :overlay (mirrors BT PIN tier).
 * Layman: type a network password over Rockbox/JJ without leaving that app.
 * Technical: SolarWheelKeyboardController full alphabet; WifiConnector on enter.
 * Reversal: remove; overlay dismisses to MainActivity for password (legacy path).
 */
final class OverlayWifiPasswordKeyboard {

    private final Context context;
    private final ViewGroup parent;
    private final Runnable onDismissKeyboardOnly;

    private View shellRoot;
    private SolarKeyboardShellHost shellHost;
    private SolarWheelKeyboardController controller;
    private String targetSsid;
    private long playPauseDownAt;

    OverlayWifiPasswordKeyboard(Context context, ViewGroup parent,
            Runnable onDismissKeyboardOnly) {
        this.context = context.getApplicationContext();
        this.parent = parent;
        this.onDismissKeyboardOnly = onDismissKeyboardOnly;
    }

    boolean isShowing() {
        return shellRoot != null;
    }

    /** Paint full keyboard for secured SSID — overlay quick bar stays underneath. */
    void show(String ssid) {
        dismiss();
        targetSsid = ssid;
        controller = new SolarWheelKeyboardController();
        controller.setGroupedMode(WheelKeyboardLayout.isGrouped(context));
        controller.setDigitOnlyMode(false);
        controller.setPasswordMode(true);
        controller.setListener(new SolarWheelKeyboardController.Listener() {
            @Override
            public void onStateChanged() {
                refreshUi();
            }

            @Override
            public void onEnterRequested() {
                String password = controller.getBuffer();
                if (password == null) password = "";
                WifiConnector.connectDetailed(context, targetSsid, password, false,
                        new WifiConnector.DetailedCallback() {
                            @Override
                            public void onComplete(WifiConnector.ConnectionResult result) {
                                if (result != null && result.success) {
                                    dismissKeyboardOnly();
                                    return;
                                }
                                if (result != null
                                        && result.failure != WifiConnector.Failure.CANCELED) {
                                    Toast.makeText(context,
                                            context.getString(WifiConnector.failureMessageResId(
                                                    result.failure)),
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        });
            }
        });

        LayoutInflater inflater = LayoutInflater.from(context);
        shellRoot = inflater.inflate(R.layout.layout_solar_keyboard_shell, parent, false);
        shellHost = new SolarKeyboardShellHost(context, shellRoot,
                context.getString(R.string.keyboard_enter_wifi_password));
        parent.addView(shellRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshUi();
        ThemeManager.ensureOverlayPaintableMinimum(context);
        SolarImeRouteArbiter.setOverlayCredentialActive(true);
    }

    boolean handleKeyDown(int keyCode) {
        if (controller == null || shellRoot == null) return false;
        if (Y1InputKeys.isBackKey(keyCode)) {
            dismissKeyboardOnly();
            return true;
        }
        if (Y1InputKeys.isWheelUp(keyCode)) {
            controller.wheelUp();
            return true;
        }
        if (Y1InputKeys.isWheelDown(keyCode)) {
            controller.wheelDown();
            return true;
        }
        if (Y1InputKeys.isCenterKey(keyCode)) {
            controller.centerPress();
            return true;
        }
        if (Y1InputKeys.isPlayPauseKey(keyCode)) {
            if (playPauseDownAt == 0L) playPauseDownAt = SystemClock.uptimeMillis();
            return true;
        }
        if (Y1InputKeys.isTrackPreviousKey(keyCode)) {
            controller.mediaDelete();
            return true;
        }
        if (Y1InputKeys.isTrackNextKey(keyCode)) {
            controller.mediaSpace();
            return true;
        }
        return false;
    }

    boolean handleKeyUp(int keyCode) {
        if (!isShowing()) return false;
        if (Y1InputKeys.isPlayPauseKey(keyCode)) {
            long held = playPauseDownAt > 0L
                    ? SystemClock.uptimeMillis() - playPauseDownAt : 0L;
            playPauseDownAt = 0L;
            if (held >= 500L) controller.playPauseLongPress();
            else controller.requestEnter();
            return true;
        }
        return Y1InputKeys.isCenterKey(keyCode) || Y1InputKeys.isBackKey(keyCode)
                || Y1InputKeys.isWheelKey(keyCode)
                || Y1InputKeys.isTrackPreviousKey(keyCode)
                || Y1InputKeys.isTrackNextKey(keyCode);
    }

    void dismiss() {
        SolarImeRouteArbiter.setOverlayCredentialActive(false);
        if (shellRoot != null && parent != null) {
            try {
                parent.removeView(shellRoot);
            } catch (Exception ignored) {}
        }
        shellRoot = null;
        shellHost = null;
        controller = null;
        targetSsid = null;
        playPauseDownAt = 0L;
    }

    private void dismissKeyboardOnly() {
        dismiss();
        if (onDismissKeyboardOnly != null) {
            onDismissKeyboardOnly.run();
        }
    }

    private void refreshUi() {
        if (shellHost == null || controller == null) return;
        // 2026-07-20 — Status = clock (shell); no subtitle title; password hint only in field.
        shellHost.applyShellTheme("", true);
        String buffer = controller.getBuffer();
        boolean empty = buffer == null || buffer.length() == 0;
        String input = empty
                ? context.getString(R.string.keyboard_enter_wifi_password)
                : controller.renderBuffer(true);
        shellHost.getKeyboardUi().refresh(controller, null, input, empty);
    }
}
