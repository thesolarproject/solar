package com.solar.launcher.ui;

import android.view.animation.Interpolator;

/** Critically damped, LUT-backed interpolators with no per-frame transcendentals. */
final class MechanicalInterpolator implements Interpolator {
    private final float[] values;

    MechanicalInterpolator(float[] values) {
        this.values = values;
    }

    @Override
    public float getInterpolation(float input) {
        if (input <= 0f) return 0f;
        if (input >= 1f) return 1f;
        float p = input * (values.length - 1);
        int lo = (int) p;
        int hi = Math.min(values.length - 1, lo + 1);
        float f = p - lo;
        return values[lo] + (values[hi] - values[lo]) * f;
    }
}
