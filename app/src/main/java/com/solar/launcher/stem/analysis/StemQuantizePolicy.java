package com.solar.launcher.stem.analysis;

/**
 * StemFM-style Quantize Performance — stem actions (pad crossfade, mute, swap) are
 * deferred to the next beat / bar / half-bar instead of executing instantly, so every
 * switch lands on the pulse. Pure math: no Android imports. 2026-08-01
 */
public final class StemQuantizePolicy {
    /** Off — actions execute immediately (StemFM default; user-toggleable). */
    public static final int OFF = 0;
    /** Snap to the next beat (quarter note). */
    public static final int BEAT = 1;
    /** Snap to the next half-bar (2 beats). */
    public static final int HALF_BAR = 2;
    /** Snap to the next bar (4 beats). */
    public static final int BAR = 3;

    private StemQuantizePolicy() {}

    /** ms per beat at the given BPM (same convention as StemBpm). */
    public static int msPerBeat(float bpm) {
        float b = bpm > 30f && bpm < 300f ? bpm : 120f;
        return Math.max(50, Math.round(60000f / b));
    }

    /**
     * Delay in ms before an action taken at {@code positionMs} should execute so it
     * lands on the quantised boundary. Returns 0 for OFF or when BPM is unusable.
     * A position exactly on the boundary executes immediately (delay 0).
     */
    public static int delayMs(int mode, float bpm, int positionMs) {
        if (mode <= OFF || mode > BAR) return 0;
        int beat = msPerBeat(bpm);
        int span = mode == BEAT ? beat : (mode == HALF_BAR ? beat * 2 : beat * 4);
        if (span < 50) return 0;
        int pos = Math.max(0, positionMs);
        int next = ((pos / span) + 1) * span;
        int delay = next - pos;
        if (delay <= 0) delay = span;
        // Safety cap — never wait more than 4 bars for a quantised action.
        int cap = beat * 16;
        if (delay > cap) delay = 0;
        return delay;
    }

    /** Human label for a quantise mode. */
    public static String label(int mode) {
        switch (mode) {
            case BEAT: return "Quantize · Beat";
            case HALF_BAR: return "Quantize · Half Bar";
            case BAR: return "Quantize · Bar";
            default: return "Quantize · Off";
        }
    }
}
