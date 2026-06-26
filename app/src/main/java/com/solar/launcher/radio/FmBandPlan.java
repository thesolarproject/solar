package com.solar.launcher.radio;

import java.util.Locale;

/** FM broadcast band limits per regulatory region. */
public final class FmBandPlan {
  public final String regionCode;
  public final float minMhz;
  public final float maxMhz;
  public final float stepMhz;

  public FmBandPlan(String regionCode, float minMhz, float maxMhz, float stepMhz) {
    this.regionCode = regionCode;
    this.minMhz = minMhz;
    this.maxMhz = maxMhz;
    this.stepMhz = stepMhz;
  }

  /** Resolve plan for region code; unknown codes fall back to US. */
  public static FmBandPlan fromRegionCode(String regionCode) {
    String r = regionCode == null ? "US" : regionCode.trim().toUpperCase(Locale.US);
    if ("EU".equals(r)) return new FmBandPlan("EU", 87.5f, 108.0f, 0.1f);
    if ("JP".equals(r)) return new FmBandPlan("JP", 76.0f, 90.0f, 0.1f);
    if ("AU".equals(r)) return new FmBandPlan("AU", 87.5f, 108.0f, 0.1f);
    if ("KR".equals(r)) return new FmBandPlan("KR", 88.0f, 108.0f, 0.1f);
    if ("RU".equals(r)) return new FmBandPlan("RU", 65.9f, 74.0f, 0.1f);
    // US / CA / default — includes 87.9 commercial low end
    return new FmBandPlan("US", 87.9f, 107.9f, 0.1f);
  }

  public int minKhz() {
    return Math.round(minMhz * 1000f);
  }

  public int maxKhz() {
    return Math.round(maxMhz * 1000f);
  }

  public int stepKhz() {
    return Math.max(1, Math.round(stepMhz * 1000f));
  }

  /** Clamp and snap frequency to band grid. */
  public int clampKhz(int khz) {
    int min = minKhz();
    int max = maxKhz();
    int step = stepKhz();
    if (khz < min) khz = min;
    if (khz > max) khz = max;
    int offset = khz - min;
    int steps = Math.round((float) offset / (float) step);
    return min + steps * step;
  }

  /** Human label e.g. {@code 101.1}. */
  public static String formatMhz(float mhz) {
    return String.format(Locale.US, "%.1f", mhz);
  }

  /**
   * Fractional MHz display for kHz input — e.g. 101100 → {@code 101.1}.
   * ponytail: one decimal place matches 0.1 MHz step UI.
   */
  public static String khzToFraction(int khz, FmBandPlan plan) {
    if (plan == null) plan = fromRegionCode("US");
    int clamped = plan.clampKhz(khz);
    return formatMhz(clamped / 1000f);
  }
}
