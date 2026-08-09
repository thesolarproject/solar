package com.solar.launcher.phone;

import android.view.KeyEvent;

import org.junit.Test;

import com.solar.launcher.Y1InputKeys;

/**
 * 2026-07-20 — Click-wheel zone + notch math (no Android View needed).
 */
public class PhoneClickWheelTest {

    private static final float R = 100f;

    @Test
    public void centerZone() {
        PhoneClickWheel.Zone z = PhoneClickWheel.zoneAt(0, 0, R);
        if (z != PhoneClickWheel.Zone.CENTER) throw new AssertionError("center got " + z);
        if (PhoneClickWheel.keyCodeForZone(z) != Y1InputKeys.KEY_CENTER) {
            throw new AssertionError("center key");
        }
    }

    @Test
    public void cardinalZones() {
        // Top (MENU / Back): dx=0, dy negative
        PhoneClickWheel.Zone menu = PhoneClickWheel.zoneAt(0, -70, R);
        if (menu != PhoneClickWheel.Zone.MENU) throw new AssertionError("menu got " + menu);
        if (PhoneClickWheel.keyCodeForZone(menu) != Y1InputKeys.KEY_BACK) {
            throw new AssertionError("menu key");
        }
        // Right (NEXT)
        PhoneClickWheel.Zone next = PhoneClickWheel.zoneAt(70, 0, R);
        if (next != PhoneClickWheel.Zone.NEXT) throw new AssertionError("next got " + next);
        if (PhoneClickWheel.keyCodeForZone(next) != Y1InputKeys.KEY_TRACK_NEXT) {
            throw new AssertionError("next key");
        }
        // Bottom (PLAY)
        PhoneClickWheel.Zone play = PhoneClickWheel.zoneAt(0, 70, R);
        if (play != PhoneClickWheel.Zone.PLAY) throw new AssertionError("play got " + play);
        if (PhoneClickWheel.keyCodeForZone(play) != Y1InputKeys.KEY_PLAY_PAUSE) {
            throw new AssertionError("play key");
        }
        // Left (PREV)
        PhoneClickWheel.Zone prev = PhoneClickWheel.zoneAt(-70, 0, R);
        if (prev != PhoneClickWheel.Zone.PREV) throw new AssertionError("prev got " + prev);
        if (PhoneClickWheel.keyCodeForZone(prev) != Y1InputKeys.KEY_TRACK_PREV) {
            throw new AssertionError("prev key");
        }
    }

    @Test
    public void chromeLongPressMarkerIsOneDownThenUp() {
        KeyEvent[] events = PhoneClickWheelPad.longPressDownUp(Y1InputKeys.KEY_TRACK_PREV);
        if (events.length != 2) throw new AssertionError("long press pair");
        if (events[0].getAction() != KeyEvent.ACTION_DOWN
                || events[0].getRepeatCount() != 1) {
            throw new AssertionError("long press marker");
        }
        if (events[1].getAction() != KeyEvent.ACTION_UP
                || events[1].getRepeatCount() != 0) {
            throw new AssertionError("long press release");
        }
        if (events[0].getKeyCode() != Y1InputKeys.KEY_TRACK_PREV
                || events[1].getKeyCode() != Y1InputKeys.KEY_TRACK_PREV) {
            throw new AssertionError("long press key");
        }
    }

    @Test
    public void ringBetweenCardinals() {
        // NE diagonal should be RING (outside cardinal wedges)
        PhoneClickWheel.Zone ring = PhoneClickWheel.zoneAt(50, -50, R);
        if (ring != PhoneClickWheel.Zone.RING) throw new AssertionError("ring got " + ring);
        if (PhoneClickWheel.keyCodeForZone(ring) != 0) {
            throw new AssertionError("ring has no discrete key");
        }
    }

    @Test
    public void clockwiseDragYieldsPositiveNotches() {
        // From north toward east = clockwise on screen = positive delta
        float d = PhoneClickWheel.angleDeltaRadians(0, -50, 50, 0);
        if (d <= 0) throw new AssertionError("clockwise delta should be >0 got " + d);
        float acc = 0f;
        // Simulate enough clockwise motion for ≥1 notch
        float step = PhoneClickWheel.NOTCH_RADIANS * 1.1f;
        acc += step;
        int n = PhoneClickWheel.notchesFromAccumulated(acc);
        if (n < 1) throw new AssertionError("expected ≥1 notch got " + n);
        if (PhoneClickWheel.wheelDownKeyCode() != Y1InputKeys.KEY_WHEEL_DOWN) {
            throw new AssertionError("down key");
        }
        if (PhoneClickWheel.wheelUpKeyCode() != Y1InputKeys.KEY_WHEEL_UP) {
            throw new AssertionError("up key");
        }
    }

    @Test
    public void counterClockwiseIsNegative() {
        float d = PhoneClickWheel.angleDeltaRadians(0, -50, -50, 0);
        if (d >= 0) throw new AssertionError("ccw delta should be <0 got " + d);
    }
}
