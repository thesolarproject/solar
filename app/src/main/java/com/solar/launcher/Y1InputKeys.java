package com.solar.launcher;

import android.view.KeyEvent;

/**
 * Y1 keys under {@code Y1-Rockbox.kl} (Generic.kl) — matches RockboxFramebuffer scroll keys
 * MEDIA_PLAY/PAUSE (126/127) on mtk-tpd-kpd wheel scancodes 105/106.
 */
public final class Y1InputKeys {

    public static final int KEY_BACK = KeyEvent.KEYCODE_BACK; // 4
    public static final int KEY_TRACK_PREV = KeyEvent.KEYCODE_MEDIA_PREVIOUS; // 88
    public static final int KEY_TRACK_NEXT = KeyEvent.KEYCODE_MEDIA_NEXT; // 87
    public static final int KEY_CENTER = KeyEvent.KEYCODE_ENTER; // 66
    public static final int KEY_PLAY_PAUSE = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; // 85
    public static final int KEY_WHEEL_UP = KeyEvent.KEYCODE_MEDIA_PLAY; // 126
    public static final int KEY_WHEEL_DOWN = KeyEvent.KEYCODE_MEDIA_PAUSE; // 127

    private Y1InputKeys() {}

    public static boolean isBackKey(int keyCode) {
        return keyCode == KEY_BACK || keyCode == 4;
    }

    public static boolean isCenterKey(int keyCode) {
        return keyCode == KEY_CENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == 66 || keyCode == 23;
    }

    public static boolean isPlayPauseKey(int keyCode) {
        return keyCode == KEY_PLAY_PAUSE || keyCode == 85
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == 86;
    }

    /** Wheel up — Rockbox.kl 105/103 → MEDIA_PLAY (126). */
    public static boolean isWheelUp(int keyCode) {
        return keyCode == KEY_WHEEL_UP || keyCode == 126;
    }

    /** Wheel down — Rockbox.kl 106/108 → MEDIA_PAUSE (127). */
    public static boolean isWheelDown(int keyCode) {
        return keyCode == KEY_WHEEL_DOWN || keyCode == 127;
    }

    /** Side previous — Y1-Rockbox.kl scancode 165 → DPAD_LEFT (21); Rockbox-y1 transport key. */
    public static boolean isTrackPreviousKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == 21
                || keyCode == KEY_TRACK_PREV || keyCode == 88;
    }

    /** Side next — Y1-Rockbox.kl scancode 163 → DPAD_RIGHT (22); Rockbox-y1 transport key. */
    public static boolean isTrackNextKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == 22
                || keyCode == KEY_TRACK_NEXT || keyCode == 87;
    }

    public static boolean isWheelKey(int keyCode) {
        return isWheelUp(keyCode) || isWheelDown(keyCode);
    }

    public static int wheelMenuDelta(int keyCode) {
        if (isWheelUp(keyCode)) return -1;
        if (isWheelDown(keyCode)) return 1;
        return 0;
    }

    /** AVRCP PASSTHROUGH PLAY → same Android keycode as wheel up; only remap when device is AVRCP. */
    public static boolean isDiscreteMediaPlay(int keyCode) {
        return isWheelUp(keyCode);
    }

    /** AVRCP PASSTHROUGH PAUSE → same Android keycode as wheel down; only remap when device is AVRCP. */
    public static boolean isDiscreteMediaPause(int keyCode) {
        return isWheelDown(keyCode);
    }

    /** Discrete skip from AVRCP.kl — not Y1 side keys (21/22). */
    public static boolean isAvrcpSkipNext(int keyCode) {
        return keyCode == KEY_TRACK_NEXT || keyCode == 87;
    }

    public static boolean isAvrcpSkipPrevious(int keyCode) {
        return keyCode == KEY_TRACK_PREV || keyCode == 88;
    }

    public static boolean isAvrcpSkipKey(int keyCode) {
        return isAvrcpSkipNext(keyCode) || isAvrcpSkipPrevious(keyCode);
    }

    /** Keycodes BT remotes may send via AVRCP.kl or ACTION_MEDIA_BUTTON (not hardware 21/22). */
    public static boolean isAvrcpMediaTransportKeyCode(int keyCode) {
        return isDiscreteMediaPlay(keyCode) || isDiscreteMediaPause(keyCode)
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == 86
                || isAvrcpSkipKey(keyCode)
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == 79;
    }

    /** Volume from BT remote / ACTION_MEDIA_BUTTON — not Y1 wheel. */
    public static boolean isBluetoothVolumeKeyCode(int keyCode) {
        return isVolumeDownKey(keyCode) || isVolumeUpKey(keyCode);
    }

    /** Keys Solar handles from ACTION_MEDIA_BUTTON (AVRCP + volume). */
    public static boolean isBluetoothMediaButtonKeyCode(int keyCode) {
        return isAvrcpMediaTransportKeyCode(keyCode) || isBluetoothVolumeKeyCode(keyCode);
    }

    public static boolean isVolumeDownKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == 25 || keyCode == 114 || keyCode == 160;
    }

    public static boolean isVolumeUpKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == 24 || keyCode == 115 || keyCode == 161;
    }

    static void selfCheckWheelMapping() {
        if (wheelMenuDelta(126) != -1) throw new AssertionError("wheel MEDIA_PLAY");
        if (wheelMenuDelta(127) != 1) throw new AssertionError("wheel MEDIA_PAUSE");
        if (wheelMenuDelta(88) != 0) throw new AssertionError("track prev not wheel");
        if (wheelMenuDelta(87) != 0) throw new AssertionError("track next not wheel");
        if (!isTrackPreviousKey(21)) throw new AssertionError("track prev dpad");
        if (!isTrackNextKey(22)) throw new AssertionError("track next dpad");
    }
}
