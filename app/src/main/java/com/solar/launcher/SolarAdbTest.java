package com.solar.launcher;

import android.util.Log;

/** Logcat probe for {@code scripts/test_context_queue_adb.sh} — tag {@code SolarAdbTest}. */
final class SolarAdbTest {
    static final String TAG = "SolarAdbTest";

    private SolarAdbTest() {}

    static void queueScroll(int focusIndex, int count, int topPad, int scrollY, int maxScroll, int slot) {
        queueScroll(focusIndex, count, topPad, scrollY, maxScroll, slot, -1);
    }

    static void queueScroll(int focusIndex, int count, int topPad, int scrollY, int maxScroll, int slot,
            int nowPlaying) {
        Log.i(TAG, "queueScroll focus=" + focusIndex + " count=" + count + " topPad=" + topPad
                + " scrollY=" + scrollY + " maxScroll=" + maxScroll + " slot=" + slot
                + " nowPlaying=" + nowPlaying);
    }

    static void queuePlaybackState(boolean playing) {
        Log.i(TAG, "queuePlaybackState playing=" + playing);
    }

    static void queueOpen(int count, boolean listVisible, int scrollHeight, int childCount) {
        Log.i(TAG, "queueOpen count=" + count + " listVisible=" + listVisible
                + " scrollHeight=" + scrollHeight + " childCount=" + childCount);
    }

    static void fail(String reason) {
        Log.i(TAG, "FAIL " + reason);
    }

    static void pass(String reason) {
        Log.i(TAG, "PASS " + reason);
    }
}
