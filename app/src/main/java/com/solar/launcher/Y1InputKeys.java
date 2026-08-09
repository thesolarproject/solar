package com.solar.launcher;

import android.view.KeyEvent;

/**
 * Y1/Y2 hardware keys — unified Y1-Rockbox / Y2-Rockbox keylayout on both devices.
 * Wheel: 105/106 → MEDIA_PLAY/PAUSE (126/127); legacy path 114/115 → DPAD_UP/DOWN (19/20).
 * Side: mtk-kpd 165/163 → MEDIA_PREVIOUS/NEXT (88/87).
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
        if (keyCode == KEY_BACK || keyCode == 4) return true;
        // 2026-07-14 — A5 side power reports MEDIA_STOP(86); fail-open if remap missed.
        // Was: only KEYCODE_BACK. Now: A5 also accepts 86 as Back. Reversal: drop 86 branch.
        if (DeviceFeatures.isA5()
                && (keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == 86)) {
            return true;
        }
        // 2026-07-11 — Emulator Esc/Backspace and A5 soft keys leave menus/NP/Flow.
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_DEL
                || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            return DeviceFeatures.isA5() || EmulatorInputMap.isEmulator();
        }
        return false;
    }

    public static boolean isCenterKey(int keyCode) {
        return keyCode == KEY_CENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == 66 || keyCode == 23;
    }

    /** Y2 sleep/lock GPIO (scancode 116) — must reach PhoneWindowManager for GlobalActions. */
    public static boolean isPowerKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_POWER;
    }

    public static boolean isPlayPauseKey(int keyCode) {
        // 2026-07-14 — A5 power is MEDIA_STOP(86) → Back, not play/pause.
        // Was: 86 counted as play/pause on all families. Now: skip on A5.
        // Reversal: restore || MEDIA_STOP || 86 without isA5 guard.
        if (DeviceFeatures.isA5()
                && (keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == 86)) {
            return false;
        }
        return keyCode == KEY_PLAY_PAUSE || keyCode == 85
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == 86;
    }

    /**
     * Physical Stem top/bottom pad aliases. Stock Y1/A5 uses scan codes 103/108 for
     * DPAD_UP/DOWN; Y1 wheel uses 114/115, so callers must inspect the event scan code.
     * 2026-08-03
     */
    public static boolean isTopPadEvent(KeyEvent event) {
        return event != null && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                && event.getScanCode() == 103;
    }

    public static boolean isBottomPadEvent(KeyEvent event) {
        return event != null && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                && event.getScanCode() == 108;
    }

    /** Wheel up — Y1-Rockbox.kl 114 → DPAD_UP (19) or 105 → MEDIA_PLAY (126). */
    public static boolean isWheelUp(int keyCode) {
        return keyCode == KEY_WHEEL_UP || keyCode == 126
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == 19;
    }

    /** Wheel down — Y1-Rockbox.kl 115 → DPAD_DOWN (20) or 106 → MEDIA_PAUSE (127). */
    public static boolean isWheelDown(int keyCode) {
        return keyCode == KEY_WHEEL_DOWN || keyCode == 127
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == 20;
    }

    /** Side previous — mtk-kpd scancode 165 → MEDIA_PREVIOUS (88). */
    public static boolean isTrackPreviousKey(int keyCode) {
        return keyCode == KEY_TRACK_PREV || keyCode == 88;
    }

    /** Side next — mtk-kpd scancode 163 → MEDIA_NEXT (87). */
    public static boolean isTrackNextKey(int keyCode) {
        return keyCode == KEY_TRACK_NEXT || keyCode == 87;
    }

    /** Y2 wheel-as-track keys — scancode 105/106 map to MEDIA_PREVIOUS/NEXT on Y2-Rockbox.kl. */
    public static boolean isY2TrackPreviousKey(int keyCode) {
        return keyCode == 105;
    }

    public static boolean isY2TrackNextKey(int keyCode) {
        return keyCode == 106;
    }

    /** 2026-08-02 — Generic Android keymaps: DPAD_LEFT / arrow-left (21) or QWERTY S (47) → previous track. */
    public static boolean isGenericTrackPreviousKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == 21
                || keyCode == KeyEvent.KEYCODE_S || keyCode == 47;
    }

    /** 2026-08-02 — Generic Android keymaps: DPAD_RIGHT / arrow-right (22) or QWERTY D (32) → next track. */
    public static boolean isGenericTrackNextKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == 22
                || keyCode == KeyEvent.KEYCODE_D || keyCode == 32;
    }

    public static boolean isGenericTrackSkipKey(int keyCode) {
        return isGenericTrackPreviousKey(keyCode) || isGenericTrackNextKey(keyCode);
    }

    public static boolean isWheelKey(int keyCode) {
        return isWheelUp(keyCode) || isWheelDown(keyCode);
    }

    /**
     * 2026-07-19 — Wheel notch candidate for list/menu focus (DOWN only).
     * 2026-07-20 — Repeats allowed again: continuous turns often arrive as EV_KEY value=2
     * after the first DOWN; dropping them made ~1 row per full wheel revolution.
     * Layman: finger-up does not move; clicks and keep-turning clicks do.
     * Technical: ACTION_DOWN + wheel keycode; callers use {@link WheelNavPolicy} + KEY_UP
     * hard-stop so post-release ghost repeats do not walk the list.
     * Reversal: reject repeatCount!=0 here (pre-2026-07-20).
     */
    public static boolean isWheelNavAction(KeyEvent event) {
        if (event == null) return false;
        return isWheelNavAction(event.getAction(), event.getKeyCode(), event.getRepeatCount());
    }

    /**
     * 2026-07-19/20 — Primitive form for unit tests + callers that already unpacked the KeyEvent.
     * Reversal: also require repeatCount==0 (old anti-coast filter).
     */
    public static boolean isWheelNavAction(int action, int keyCode, int repeatCount) {
        if (action != KeyEvent.ACTION_DOWN) return false;
        // 2026-07-20 — repeatCount ignored here; WheelNavPolicy.acceptNotch gates held repeats.
        return isWheelKey(keyCode);
    }

    public static int wheelMenuDelta(int keyCode) {
        if (isWheelUp(keyCode)) return -1;
        if (isWheelDown(keyCode)) return 1;
        return 0;
    }

    /** AVRCP PASSTHROUGH PLAY → MEDIA_PLAY (126) only; not DPAD_UP (19) from Y1 wheel. */
    public static boolean isDiscreteMediaPlay(int keyCode) {
        return keyCode == KEY_WHEEL_UP || keyCode == 126;
    }

    /** AVRCP PASSTHROUGH PAUSE → MEDIA_PAUSE (127) only; not DPAD_DOWN (20) from Y1 wheel. */
    public static boolean isDiscreteMediaPause(int keyCode) {
        return keyCode == KEY_WHEEL_DOWN || keyCode == 127;
    }

    /** Discrete skip from AVRCP.kl — same keycodes as Y1 side keys (87/88). */
    public static boolean isAvrcpSkipNext(int keyCode) {
        return keyCode == KEY_TRACK_NEXT || keyCode == 87;
    }

    public static boolean isAvrcpSkipPrevious(int keyCode) {
        return keyCode == KEY_TRACK_PREV || keyCode == 88;
    }

    public static boolean isAvrcpSkipKey(int keyCode) {
        return isAvrcpSkipNext(keyCode) || isAvrcpSkipPrevious(keyCode);
    }

    /** Keycodes BT remotes may send via AVRCP.kl or ACTION_MEDIA_BUTTON. */
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
                || keyCode == 25 || keyCode == 160;
    }

    public static boolean isVolumeUpKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == 24 || keyCode == 161;
    }

    static void selfCheckWheelMapping() {
        if (wheelMenuDelta(126) != -1) throw new AssertionError("wheel MEDIA_PLAY");
        if (wheelMenuDelta(127) != 1) throw new AssertionError("wheel MEDIA_PAUSE");
        if (wheelMenuDelta(19) != -1) throw new AssertionError("wheel DPAD_UP");
        if (wheelMenuDelta(20) != 1) throw new AssertionError("wheel DPAD_DOWN");
        if (isDiscreteMediaPlay(19)) throw new AssertionError("dpad up not avrcp play");
        if (isDiscreteMediaPause(20)) throw new AssertionError("dpad down not avrcp pause");
        if (wheelMenuDelta(88) != 0) throw new AssertionError("track prev not wheel");
        if (wheelMenuDelta(87) != 0) throw new AssertionError("track next not wheel");
        if (!isTrackPreviousKey(88)) throw new AssertionError("track prev media");
        if (!isTrackNextKey(87)) throw new AssertionError("track next media");
        if (isTrackPreviousKey(21)) throw new AssertionError("dpad left not track prev");
        if (isTrackNextKey(22)) throw new AssertionError("dpad right not track next");
        if (!isTopPadEvent(new KeyEvent(1L, 1L, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_UP, 0, 0, 0, 103, 0))) {
            throw new AssertionError("scan103 top pad");
        }
        if (!isBottomPadEvent(new KeyEvent(1L, 1L, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN, 0, 0, 0, 108, 0))) {
            throw new AssertionError("scan108 bottom pad");
        }
        if (isTopPadEvent(new KeyEvent(1L, 1L, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_UP, 0, 0, 0, 114, 0))) {
            throw new AssertionError("scan114 remains wheel");
        }
        if (!isGenericTrackPreviousKey(21)) throw new AssertionError("generic dpad-left prev");
        if (!isGenericTrackPreviousKey(47)) throw new AssertionError("generic S prev");
        if (!isGenericTrackNextKey(22)) throw new AssertionError("generic dpad-right next");
        if (!isGenericTrackNextKey(32)) throw new AssertionError("generic D next");
        if (isGenericTrackSkipKey(88)) throw new AssertionError("media prev not generic");
    }
}
