package com.solar.launcher;

import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * Separates Bluetooth AVRCP uinput from Y1 physical keys.
 * Koensayr's {@code libextavrcp_jni} injects into a kernel device named {@code AVRCP};
 * the scroll wheel uses {@code mtk-tpd-kpd} / {@code mtk-kpd} with the same Android keycodes
 * ({@code 126}/{@code 127}) — so we must never remap by keycode alone.
 */
public final class Y1BluetoothInput {

    private Y1BluetoothInput() {}

    /**
     * True when this {@link KeyEvent} came from the BT AVRCP uinput device, not Y1 hardware.
     * ponytail: positive AVRCP match + explicit mtk denylist; no fuzzy source-bit fallback.
     */
    public static boolean isBluetoothTransportKey(KeyEvent event) {
        if (event == null) return false;
        InputDevice device = event.getDevice();
        if (device == null) return false;
        String name = device.getName();
        if (name == null) return false;
        if (isY1HardwareInputName(name)) return false;
        return name.contains("AVRCP");
    }

    /** Hardware keypad / touchpad names on Y1 — wheel and side buttons. */
    public static boolean isY1HardwareInputName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.contains("mtk-kpd") || lower.contains("mtk-tpd");
    }

    /**
     * {@link Intent#ACTION_MEDIA_BUTTON} deliveries have no {@link InputDevice}; treat discrete
     * transport keycodes as BT when Solar is playing. Foreground wheel still uses {@link KeyEvent}
     * with a hardware device id.
     */
    public static boolean isMediaButtonTransportKeyCode(int keyCode) {
        return Y1InputKeys.isBluetoothMediaButtonKeyCode(keyCode);
    }

    static void selfCheck() {
        if (!isY1HardwareInputName("mtk-tpd-kpd")) throw new AssertionError("mtk-tpd");
        if (!isY1HardwareInputName("mtk-kpd")) throw new AssertionError("mtk-kpd");
        if (isY1HardwareInputName("AVRCP")) throw new AssertionError("avrcp not hardware");
    }
}
