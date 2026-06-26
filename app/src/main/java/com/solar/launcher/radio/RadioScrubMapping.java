package com.solar.launcher.radio;

/** Maps horizontal scrub position (0..1) to FM kHz or buffer offset ms. */
public final class RadioScrubMapping {
  private RadioScrubMapping() {}

  /**
   * @param position scrub fraction 0 (start/oldest) .. 1 (end/live)
   * @return kHz when mode is TUNE_FM, else 0
   */
  public static int positionToKhz(float position, FmBandPlan plan) {
    if (plan == null) plan = FmBandPlan.fromRegionCode("US");
    float p = clamp01(position);
    int min = plan.minKhz();
    int max = plan.maxKhz();
    int step = plan.stepKhz();
    int steps = Math.max(1, (max - min) / step);
    int idx = Math.round(p * steps);
    return plan.clampKhz(min + idx * step);
  }

  /**
   * Inverse of {@link #positionToKhz} for highlighting scrub thumb on current frequency.
   */
  public static float khzToPosition(int khz, FmBandPlan plan) {
    if (plan == null) plan = FmBandPlan.fromRegionCode("US");
    int clamped = plan.clampKhz(khz);
    int min = plan.minKhz();
    int max = plan.maxKhz();
    int step = plan.stepKhz();
    int steps = Math.max(1, (max - min) / step);
    int idx = (clamped - min) / step;
    if (idx < 0) idx = 0;
    if (idx > steps) idx = steps;
    return steps == 0 ? 0f : (float) idx / (float) steps;
  }

  /**
   * @param position scrub fraction 0 (oldest buffered) .. 1 (live edge)
   * @return milliseconds from buffer start; 0 = oldest available sample
   */
  public static long positionToBufferMs(float position, long bufferedDurationMs) {
    if (bufferedDurationMs <= 0) return 0L;
    float p = clamp01(position);
    return Math.round(p * (float) bufferedDurationMs);
  }

  /**
   * Inverse of {@link #positionToBufferMs}.
   */
  public static float bufferMsToPosition(long offsetMs, long bufferedDurationMs) {
    if (bufferedDurationMs <= 0) return 1f;
    if (offsetMs <= 0) return 0f;
    if (offsetMs >= bufferedDurationMs) return 1f;
    return (float) offsetMs / (float) bufferedDurationMs;
  }

  /** Dispatch by scrub mode — ponytail: single entry for UI wheel handler. */
  public static long mapScrub(RadioScrubMode mode, float position, FmBandPlan plan,
      long bufferedDurationMs) {
    if (mode == RadioScrubMode.TUNE_FM) return positionToKhz(position, plan);
    if (mode == RadioScrubMode.REWIND_BUFFER) return positionToBufferMs(position, bufferedDurationMs);
    return 0L;
  }

  static float clamp01(float position) {
    if (position < 0f) return 0f;
    if (position > 1f) return 1f;
    return position;
  }
}
