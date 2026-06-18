package com.solar.launcher;

import org.junit.Test;

public class ScrubUtilTest {
    @Test
    public void clampScrubPositionMs_respectsDuration() {
        if (MainActivity.clampScrubPositionMs(-1000, 60000) != 0) throw new AssertionError("floor");
        if (MainActivity.clampScrubPositionMs(90000, 60000) != 60000) throw new AssertionError("ceiling");
        if (MainActivity.clampScrubPositionMs(30000, 60000) != 30000) throw new AssertionError("mid");
    }

    @Test
    public void clampScrubPositionMs_bufferShorterThanDisplayDuration() {
        if (MainActivity.clampScrubPositionMs(120000, 60000) != 60000) {
            throw new AssertionError("buffer cap");
        }
    }
}
