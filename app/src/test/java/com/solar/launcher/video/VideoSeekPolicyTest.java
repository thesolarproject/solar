package com.solar.launcher.video;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VideoSeekPolicyTest {

    @Test
    public void clampsAndStepsWithinDuration() {
        assertEquals(0L, VideoSeekPolicy.clampTarget(-1L, 60_000L));
        assertEquals(60_000L, VideoSeekPolicy.clampTarget(90_000L, 60_000L));
        assertEquals(15_000L, VideoSeekPolicy.steppedTarget(10_000L, 5_000L, 60_000L));
        assertEquals(0L, VideoSeekPolicy.steppedTarget(2_000L, -5_000L, 60_000L));
        assertEquals(60_000L,
                VideoSeekPolicy.steppedTarget(Long.MAX_VALUE - 1L, 5_000L, 60_000L));
    }

    @Test
    public void recognizesCompletionAndTimeout() {
        assertTrue(VideoSeekPolicy.isComplete(30_000L, 28_500L));
        assertFalse(VideoSeekPolicy.isComplete(30_000L, 28_499L));
        assertFalse(VideoSeekPolicy.isComplete(-1L, 0L));

        assertFalse(VideoSeekPolicy.hasTimedOut(1_000L,
                1_000L + VideoSeekPolicy.SEEK_TIMEOUT_MS - 1L));
        assertTrue(VideoSeekPolicy.hasTimedOut(1_000L,
                1_000L + VideoSeekPolicy.SEEK_TIMEOUT_MS));
        assertFalse(VideoSeekPolicy.hasTimedOut(0L, Long.MAX_VALUE));
    }

    @Test
    public void formatsShortAndLongVideos() {
        assertEquals("00:00", VideoSeekPolicy.formatTime(-1L));
        assertEquals("01:05", VideoSeekPolicy.formatTime(65_000L));
        assertEquals("1:02:03", VideoSeekPolicy.formatTime(3_723_000L));
    }
}
