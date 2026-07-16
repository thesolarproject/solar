package com.solar.launcher.stage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SolarDeviceStagingTest {

    @Test
    public void certMapHasIsrg() {
        assertTrue(SolarDeviceStaging.certMapSize() >= 6);
    }

    @Test
    public void stageResultValues() {
        assertEquals(StageResult.OK, StageResult.valueOf("OK"));
        assertEquals(StageResult.SKIPPED, StageResult.valueOf("SKIPPED"));
        assertEquals(StageResult.FAILED, StageResult.valueOf("FAILED"));
    }

    @Test
    public void shellQuoteEscapes() {
        assertEquals("''", SuHelper.shellQuote(null));
        assertTrue(SuHelper.shellQuote("/tmp/a b").contains("a b"));
    }
}
