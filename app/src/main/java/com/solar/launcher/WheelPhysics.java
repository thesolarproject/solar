package com.solar.launcher;

/** Allocation-free flywheel model for the Y1 rotary wheel. */
public final class WheelPhysics {
    public static final long RESET_NANOS = 400_000_000L;
    public static final float SECTION_THRESHOLD = 5f;
    public static final int MAX_ROW_STEPS = 4;

    private static final float[] DECAY = {
            1.00000000f, 0.98202296f, 0.96436909f, 0.94703259f, 0.93000775f, 0.91328897f, 0.89687073f, 0.88074765f,
            0.86491442f, 0.84936582f, 0.83409673f, 0.81910214f, 0.80437711f, 0.78991679f, 0.77571643f, 0.76177134f,
            0.74807695f, 0.73462874f, 0.72142229f, 0.70845325f, 0.69571736f, 0.68321042f, 0.67092832f, 0.65886702f,
            0.64702254f, 0.63539099f, 0.62396854f, 0.61275143f, 0.60173598f, 0.59091854f, 0.58029558f, 0.56986358f,
            0.55961912f, 0.54955883f, 0.53967939f, 0.52997755f, 0.52045012f, 0.51109397f, 0.50190601f, 0.49288323f,
            0.48402265f, 0.47532135f, 0.46677648f, 0.45838522f, 0.45014481f, 0.44205254f, 0.43410575f, 0.42630181f,
            0.41863817f, 0.41111229f, 0.40372171f, 0.39646399f, 0.38933674f, 0.38233762f, 0.37546432f, 0.36871458f,
            0.36208618f, 0.35557695f, 0.34918473f, 0.34290742f, 0.33674296f, 0.33068932f, 0.32474450f, 0.31890656f
    };

    private float velocity;
    private long lastNanos;
    private int lastDirection;

    public Result tick(long nowNanos, int direction, Result out) {
        if (out == null) out = new Result();
        long elapsed = lastNanos == 0L ? RESET_NANOS : Math.max(0L, nowNanos - lastNanos);
        if (direction == 0) {
            out.set(0, false, velocity);
            return out;
        }
        if (elapsed >= RESET_NANOS || lastDirection != 0 && direction != lastDirection) {
            velocity = 0f;
        } else {
            velocity *= decay(elapsed);
        }
        velocity += 1f;
        lastNanos = nowNanos;
        lastDirection = direction;
        boolean section = velocity >= SECTION_THRESHOLD;
        int steps = section ? 0 : Math.max(1, Math.min(MAX_ROW_STEPS, (int) velocity));
        out.set(steps, section, velocity);
        return out;
    }

    public void reset() {
        velocity = 0f;
        lastNanos = 0L;
        lastDirection = 0;
    }

    static float decay(long elapsedNanos) {
        if (elapsedNanos <= 0L) return 1f;
        if (elapsedNanos >= RESET_NANOS) return 0f;
        float p = elapsedNanos * (DECAY.length - 1f) / RESET_NANOS;
        int lo = (int) p;
        int hi = Math.min(DECAY.length - 1, lo + 1);
        float f = p - lo;
        return DECAY[lo] + (DECAY[hi] - DECAY[lo]) * f;
    }

    public static final class Result {
        public int rowSteps;
        public boolean sectionJump;
        public float velocity;

        private void set(int steps, boolean section, float value) {
            rowSteps = steps;
            sectionJump = section;
            velocity = value;
        }
    }
}
