package com.solar.launcher;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Y2InputRoutingTest {

    private static final int STATE_FLOW = 50;
    private static final int STATE_WIFI_KEYBOARD = 7;
    private static final int STATE_PLAYER = 2;
    private static final int STATE_VIDEO_PLAYER = 60;
    private static final int STATE_SETTINGS = 5;

    @Test
    public void y2PowerKeyIsDedicatedSleepLockButton() {
        assertTrue(Y1InputKeys.isPowerKey(KeyEvent.KEYCODE_POWER));
        assertFalse(Y1InputKeys.isPowerKey(KeyEvent.KEYCODE_BACK));
        assertFalse(Y1InputKeys.isWheelKey(KeyEvent.KEYCODE_POWER));
    }

    @Test
    public void unifiedSideKeysUseMediaCodesOnly() {
        assertTrue(Y1InputKeys.isTrackPreviousKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertTrue(Y1InputKeys.isTrackNextKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertFalse(Y1InputKeys.isTrackPreviousKey(KeyEvent.KEYCODE_DPAD_LEFT));
        assertFalse(Y1InputKeys.isTrackNextKey(KeyEvent.KEYCODE_DPAD_RIGHT));
    }

    /** 2026-08-02 — Generic Android keymaps: DPAD/arrow left (21) and QWERTY S (47) → prev. */
    @Test
    public void genericKeymapsMapToTrackSkip() {
        assertTrue(Y1InputKeys.isGenericTrackPreviousKey(KeyEvent.KEYCODE_DPAD_LEFT));
        assertTrue(Y1InputKeys.isGenericTrackPreviousKey(KeyEvent.KEYCODE_S));
        assertTrue(Y1InputKeys.isGenericTrackPreviousKey(21));
        assertTrue(Y1InputKeys.isGenericTrackPreviousKey(47));
        assertTrue(Y1InputKeys.isGenericTrackNextKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertTrue(Y1InputKeys.isGenericTrackNextKey(KeyEvent.KEYCODE_D));
        assertTrue(Y1InputKeys.isGenericTrackNextKey(22));
        assertTrue(Y1InputKeys.isGenericTrackNextKey(32));
        assertFalse(Y1InputKeys.isGenericTrackPreviousKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertFalse(Y1InputKeys.isGenericTrackNextKey(KeyEvent.KEYCODE_DPAD_LEFT));
        // Dedicated side codes are not generic keys — both paths stay distinct.
        assertFalse(Y1InputKeys.isGenericTrackPreviousKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertFalse(Y1InputKeys.isGenericTrackNextKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertTrue(Y1InputKeys.isGenericTrackSkipKey(KeyEvent.KEYCODE_S));
        assertTrue(Y1InputKeys.isGenericTrackSkipKey(KeyEvent.KEYCODE_D));
        assertTrue(Y1InputKeys.isGenericTrackSkipKey(KeyEvent.KEYCODE_DPAD_LEFT));
    }

    /** 2026-08-02 — Emulator host keyboard: S/D become the scrollwheel prev/next side buttons. */
    @Test
    public void emulatorHostMapsSToPrevAndDToNext() {
        assertTrue(EmulatorInputMap.mapKeyCode(KeyEvent.KEYCODE_S)
                == KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        assertTrue(EmulatorInputMap.mapKeyCode(KeyEvent.KEYCODE_D)
                == KeyEvent.KEYCODE_MEDIA_NEXT);
        assertTrue(EmulatorInputMap.mapKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
                == KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(EmulatorInputMap.mapKeyCode(KeyEvent.KEYCODE_W)
                == KeyEvent.KEYCODE_BACK);
        assertTrue(EmulatorInputMap.mapKeyCode(KeyEvent.KEYCODE_DPAD_LEFT)
                == KeyEvent.KEYCODE_DPAD_LEFT);
        EmulatorInputMap.selfCheck();
    }

    @Test
    public void shouldRouteSkipOnSettingsWithQueueAndMusic() {
        assertTrue(MainActivity.shouldRouteMediaSkipKeysForTest(
                true, STATE_SETTINGS, STATE_FLOW, STATE_WIFI_KEYBOARD,
                STATE_PLAYER, STATE_VIDEO_PLAYER,
                false, true, false, false));
    }

    @Test
    public void shouldRouteSkipOnKeyboardWithQueue() {
        assertTrue(MainActivity.shouldRouteMediaSkipKeysForTest(
                true, STATE_WIFI_KEYBOARD, STATE_FLOW, STATE_WIFI_KEYBOARD,
                STATE_PLAYER, STATE_VIDEO_PLAYER,
                false, false, false, false));
    }

    @Test
    public void shouldNotRouteSkipOnFlow() {
        assertFalse(MainActivity.shouldRouteMediaSkipKeysForTest(
                true, STATE_FLOW, STATE_FLOW, STATE_WIFI_KEYBOARD,
                STATE_PLAYER, STATE_VIDEO_PLAYER,
                true, true, false, false));
    }

    @Test
    public void shouldNotRouteSkipWithoutQueue() {
        assertFalse(MainActivity.shouldRouteMediaSkipKeysForTest(
                false, STATE_SETTINGS, STATE_FLOW, STATE_WIFI_KEYBOARD,
                STATE_PLAYER, STATE_VIDEO_PLAYER,
                false, true, false, false));
    }

    /** 2026-07-15 — YouTube / video player must scrub even with no music queue. */
    @Test
    public void shouldRouteSkipOnVideoPlayerWithoutQueue() {
        assertTrue(MainActivity.shouldRouteMediaSkipKeysForTest(
                false, STATE_VIDEO_PLAYER, STATE_FLOW, STATE_WIFI_KEYBOARD,
                STATE_PLAYER, STATE_VIDEO_PLAYER,
                false, false, false, false));
    }

    @Test
    public void shouldRouteSkipOnNowPlaying() {
        assertTrue(MainActivity.shouldRouteMediaSkipKeysForTest(
                true, STATE_PLAYER, STATE_FLOW, STATE_WIFI_KEYBOARD,
                STATE_PLAYER, STATE_VIDEO_PLAYER,
                false, false, false, false));
    }

    @Test
    public void phoneChromeWheelEventsRemainAContinuousStream() {
        KeyEvent down = new KeyEvent(1L, 1L, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY, 0);
        KeyEvent repeat = new KeyEvent(1L, 2L, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY, 3);
        KeyEvent up = new KeyEvent(1L, 3L, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PLAY, 0);
        assertTrue(MainActivity.isPhoneChromeWheelEvent(down));
        assertTrue(MainActivity.isPhoneChromeWheelEvent(repeat));
        assertTrue(MainActivity.isPhoneChromeWheelEvent(up));
        assertFalse(MainActivity.isPhoneChromeWheelEvent(new KeyEvent(
                1L, 1L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0)));
    }

    @Test
    public void dispatchInterceptsWheelOnMenuNotPlayer() {
        assertTrue(MainActivity.shouldDispatchInterceptWheelForTest(
                0, STATE_WIFI_KEYBOARD, STATE_PLAYER, STATE_VIDEO_PLAYER, false));
        assertFalse(MainActivity.shouldDispatchInterceptWheelForTest(
                STATE_PLAYER, STATE_WIFI_KEYBOARD, STATE_PLAYER, STATE_VIDEO_PLAYER, false));
    }

    @Test
    public void dispatchInterceptsMediaSideExceptKeyboard() {
        assertTrue(MainActivity.shouldDispatchInterceptMediaSideForTest(0, STATE_WIFI_KEYBOARD));
        assertFalse(MainActivity.shouldDispatchInterceptMediaSideForTest(
                STATE_WIFI_KEYBOARD, STATE_WIFI_KEYBOARD));
    }
}
