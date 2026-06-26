package com.solar.launcher.radio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RadioScrubMappingTest {

  @Test
  public void fmTune_endpointsMatchBand() {
    FmBandPlan us = FmBandPlan.fromRegionCode("US");
    assertEquals(us.minKhz(), RadioScrubMapping.positionToKhz(0f, us));
    assertEquals(us.maxKhz(), RadioScrubMapping.positionToKhz(1f, us));
  }

  @Test
  public void fmTune_roundTrip() {
    FmBandPlan eu = FmBandPlan.fromRegionCode("EU");
    int khz = RadioScrubMapping.positionToKhz(0.5f, eu);
    float pos = RadioScrubMapping.khzToPosition(khz, eu);
    int again = RadioScrubMapping.positionToKhz(pos, eu);
    assertEquals(khz, again);
  }

  @Test
  public void rewindBuffer_mapsTimeline() {
    long dur = 120_000L;
    assertEquals(0L, RadioScrubMapping.positionToBufferMs(0f, dur));
    assertEquals(dur, RadioScrubMapping.positionToBufferMs(1f, dur));
    assertEquals(60_000L, RadioScrubMapping.positionToBufferMs(0.5f, dur));
  }

  @Test
  public void rewindBuffer_roundTrip() {
    long dur = 90_000L;
    long ms = RadioScrubMapping.positionToBufferMs(0.25f, dur);
    float pos = RadioScrubMapping.bufferMsToPosition(ms, dur);
    assertTrue(pos >= 0.24f && pos <= 0.26f);
  }

  @Test
  public void mapScrub_dispatchesByMode() {
    FmBandPlan us = FmBandPlan.fromRegionCode("US");
    assertTrue(RadioScrubMapping.mapScrub(RadioScrubMode.TUNE_FM, 1f, us, 0L) > 0);
    assertEquals(30_000L, RadioScrubMapping.mapScrub(RadioScrubMode.REWIND_BUFFER, 0.5f, us, 60_000L));
    assertEquals(0L, RadioScrubMapping.mapScrub(RadioScrubMode.NONE, 0.5f, us, 60_000L));
  }
}
