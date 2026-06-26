package com.solar.launcher.radio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FmBandPlanTest {

  @Test
  public void usBand_includesCommercialLowEnd() {
    FmBandPlan us = FmBandPlan.fromRegionCode("US");
    assertEquals(87900, us.clampKhz(87900));
    assertEquals(107900, us.clampKhz(107900));
    assertEquals("87.9", FmBandPlan.khzToFraction(87900, us));
  }

  @Test
  public void jpBand_lowerRange() {
    FmBandPlan jp = FmBandPlan.fromRegionCode("JP");
    assertEquals(76000, jp.minKhz());
    assertEquals(90000, jp.maxKhz());
    assertEquals("76.0", FmBandPlan.formatMhz(76.0f));
  }

  @Test
  public void unknownRegion_fallsBackToUs() {
    FmBandPlan plan = FmBandPlan.fromRegionCode("ZZ");
    assertEquals("US", plan.regionCode);
    assertEquals(87900, plan.minKhz());
  }

  @Test
  public void clampSnapsToStep() {
    FmBandPlan eu = FmBandPlan.fromRegionCode("EU");
    assertEquals(101100, eu.clampKhz(101105));
  }

  @Test
  public void radioSettings_isoMapping() {
    assertEquals("US", RadioSettings.isoToFmRegion("us"));
    assertEquals("JP", RadioSettings.isoToFmRegion("jp"));
    assertEquals("EU", RadioSettings.isoToFmRegion("de"));
  }
}
