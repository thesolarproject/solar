package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Growing Reach streams: header duration vs decodable byte fraction. */
public class ReachDecodeEdgeTest {

    @Test
    public void atTwentyPercentDownload_decodeEdgeIsTwentyPercentOfHeader() {
        int mpDur = 200_000;
        long total = 1_000_000;
        long fileLen = 200_000;
        assertEquals(40_000, MainActivity.reachDecodeEdgeMs(mpDur, fileLen, total, true));
    }

    @Test
    public void whenDownloadComplete_returnsFullHeaderDuration() {
        int mpDur = 200_000;
        assertEquals(200_000, MainActivity.reachDecodeEdgeMs(mpDur, 1_000_000, 1_000_000, false));
    }
}
