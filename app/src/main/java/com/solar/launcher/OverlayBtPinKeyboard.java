package com.solar.launcher;

import android.content.Context;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.solar.launcher.theme.ThemeManager;

/** Wheel-only Bluetooth PIN/passkey keyboard inside the global overlay. */
final class OverlayBtPinKeyboard {

    private final Context context;
    private final ViewGroup parent;
    private final Runnable onDismissKeyboardOnly;

    private View shellRoot;
    private SolarKeyboardShellHost shellHost;
    private SolarWheelKeyboardController controller;
    private String targetAddress;
    private String deviceName;
    private String prefill;
    private String keyboardTitle;
    private int pairingMode = BluetoothPairingCoordinator.MODE_PIN;
    private long playPauseDownAt;

    OverlayBtPinKeyboard(Context context, ViewGroup parent, Runnable onDismissKeyboardOnly) {
        this.context = context.getApplicationContext();
        this.parent = parent;
        this.onDismissKeyboardOnly = onDismissKeyboardOnly;
    }

    boolean isShowing() {
        return shellRoot != null;
    }

    /** Paint a digit-only keyboard for legacy PIN or six-digit passkey entry. */
    void show(int mode, String address, String name, String pinPrefill) {
        dismiss();
        pairingMode = mode;
        targetAddress = address;
        deviceName = name != null && name.length() > 0 ? name : address;
        prefill = mode == BluetoothPairingCoordinator.MODE_PASSKEY_ENTRY
                ? ""
                : BluetoothAudioRepair.normalizePairingPin(pinPrefill);
        controller = new SolarWheelKeyboardController();
        controller.setGroupedMode(WheelKeyboardLayout.isGrouped(context));
        controller.setPasswordMode(true);
        controller.setDigitOnlyMode(true);
        if (prefill.length() > 0) {
            controller.setBuffer(prefill);
        }
        controller.setListener(new SolarWheelKeyboardController.Listener() {
            @Override
            public void onStateChanged() {
                int maxLength = pairingMode == BluetoothPairingCoordinator.MODE_PASSKEY_ENTRY
                        ? 6 : 16;
                String value = controller.getBuffer();
                if (value != null && value.length() > maxLength) {
                    controller.setBuffer(value.substring(0, maxLength));
                    return;
                }
                refreshUi();
            }

            @Override
            public void onEnterRequested() {
                String value = controller.getBuffer();
                boolean submitted;
                if (pairingMode == BluetoothPairingCoordinator.MODE_PASSKEY_ENTRY) {
                    if (BluetoothPairingCoordinator.parsePasskey(value) < 0) {
                        Toast.makeText(context, R.string.bt_pairing_passkey_invalid,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    submitted = BluetoothPairingCoordinator.submitPasskeyFromOverlay(
                            context, targetAddress, value);
                } else {
                    submitted = BluetoothPairingCoordinator.submitPinFromOverlay(
                            context, targetAddress, value);
                }
                if (submitted) {
                    dismissKeyboardOnly();
                } else {
                    Toast.makeText(context, R.string.bt_pairing_submit_failed,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        LayoutInflater inflater = LayoutInflater.from(context);
        shellRoot = inflater.inflate(R.layout.layout_solar_keyboard_shell, parent, false);
        int titleRes = pairingMode == BluetoothPairingCoordinator.MODE_PASSKEY_ENTRY
                ? R.string.keyboard_bt_pairing_passkey
                : R.string.keyboard_bt_pairing_pin;
        keyboardTitle = context.getString(titleRes, deviceName);
        shellHost = new SolarKeyboardShellHost(
                context, shellRoot, context.getString(R.string.bt_pairing_passkey_ok));
        parent.addView(shellRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshUi();
        ThemeManager.ensureOverlayPaintableMinimum(context);
        SolarImeRouteArbiter.setOverlayCredentialActive(true);
    }

    boolean handleKeyDown(int keyCode) {
        if (controller == null || shellRoot == null) return false;
        if (Y1InputKeys.isBackKey(keyCode)) {
            BluetoothPairingCoordinator.cancelPairing(context, targetAddress);
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
            return true;
        }
        return false;
    }

    boolean handleKeyUp(int keyCode) {
        if (!isShowing()) return false;
        if (Y1InputKeys.isPlayPauseKey(keyCode)) {
            playPauseDownAt = 0L;
            controller.requestEnter();
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
        targetAddress = null;
        deviceName = null;
        prefill = null;
        keyboardTitle = null;
        pairingMode = BluetoothPairingCoordinator.MODE_PIN;
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
        shellHost.applyShellTheme("", true);
        String buffer = controller.getBuffer();
        boolean empty = buffer == null || buffer.length() == 0;
        String input = empty
                ? context.getString(
                        pairingMode == BluetoothPairingCoordinator.MODE_PASSKEY_ENTRY
                                ? R.string.bt_pairing_passkey_placeholder
                                : R.string.bt_pairing_pin_placeholder)
                : controller.renderBuffer(true);
        shellHost.getKeyboardUi().refresh(controller, keyboardTitle, input, empty);
    }
}
