package com.solar.launcher.stem;

/**
 * BPM + beatgrid helpers for Stem mashup / beat roll.
 * Layman: guess the song’s pulse so rolls land on the beat and songs can match tempo.
 * Technical: duration heuristic → BPM; rate clamp for SoundTouch; roll slice ms from bar fraction.
 * Was: fixed 2000 ms/bar only. Reversal: ignore StemBpm, keep DEFAULT_MS_PER_BAR.
 * 2026-07-19
 * 2026-07-20 — beat-roll catch-up math (was frozen chop release).
 */
public final class StemBpm {
    public static final float DEFAULT_BPM = 120f;
    public static final float MIN_RATE = 0.85f;
    public static final float MAX_RATE = 1.15f;
    /** Beat-roll ladder as fraction of one bar (0 = roll off / whole). */
    public static final float[] CHOP_FRAC = { 0f, 1f / 16f, 1f / 8f, 1f / 4f, 1f / 2f };
    /** Classic screw rates (pitch follows). */
    public static final float[] SCREW_RATES = { 1f, 0.85f, 0.7f, 0.55f };

    private StemBpm() {}

    /** ms per bar at BPM (4/4). */
    public static int msPerBar(float bpm) {
        float b = bpm > 30f && bpm < 300f ? bpm : DEFAULT_BPM;
        return Math.max(200, Math.round(240000f / b));
    }

    /** ms per beat (quarter note in 4/4). 2026-07-19 */
    public static int msPerBeat(float bpm) {
        return Math.max(50, Math.round(msPerBar(bpm) / 4f));
    }

    /**
     * Snap a playhead to the nearest beat for beat-roll anchors.
     * Layman: land the roll on the pulse, not between the notes.
     * Technical: round positionMs to beat grid from BPM estimate.
     * Was: free playhead anchor. Reversal: return positionMs unchanged.
     * 2026-07-19
     */
    public static int snapToBeatMs(int positionMs, float bpm, int firstBeatMs) {
        int beat = msPerBeat(bpm);
        if (beat < 1) return Math.max(0, positionMs);
        int pos = Math.max(0, positionMs);
        
        // Phrase Alignment: snap to the grid relative to the first downbeat
        int relativePos = pos - firstBeatMs;
        int nearestRelative = Math.round(relativePos / (float) beat) * beat;
        
        int nearest = firstBeatMs + nearestRelative;
        if (nearest < 0) nearest = 0;
        return nearest;
    }
    
    /** Legacy wrapper for non-phrase aligned calls. */
    public static int snapToBeatMs(int positionMs, float bpm) {
        return snapToBeatMs(positionMs, bpm, 0);
    }

    /**
     * Rough BPM from lead duration — assume ~4-min pop ≈ 120, scale by length.
     * Better than nothing without onset detection on Y1.
     * 2026-07-19
     */
    public static float estimateFromDurationMs(int durationMs) {
        if (durationMs < 15_000) return DEFAULT_BPM;
        // Assume ~96 bars in a typical track; bpm = bars*60 / durationSec * 4 beats? simplify:
        // Prefer mid-tempo for 3–5 min songs.
        float sec = durationMs / 1000f;
        if (sec < 90f) return 128f;
        if (sec < 150f) return 120f;
        if (sec < 240f) return 112f;
        return 100f;
    }

    /** Playback rate so otherBpm matches masterBpm (clamped). */
    public static float rateToMatch(float masterBpm, float otherBpm) {
        float m = masterBpm > 30f ? masterBpm : DEFAULT_BPM;
        float o = otherBpm > 30f ? otherBpm : DEFAULT_BPM;
        float r = m / o;
        if (r < MIN_RATE) return MIN_RATE;
        if (r > MAX_RATE) return MAX_RATE;
        return r;
    }

    /**
     * DJ-style harmonic tempo lock — try half / same / double-time of the slave
     * against the master and pick the ratio closest to 1.0 inside the clamp.
     * Layman: a 60 BPM track grooves with a 120 BPM master at unity speed (its kicks
     * land on every other beat) instead of demanding an impossible 2× stretch.
     * Technical: for h in {0.5, 1, 2} compute master/(other*h); keep the candidate
     * nearest 1.0 that stays within [MIN_RATE, MAX_RATE]. Falls back to rateToMatch.
     * Was: raw master/other ratio only. Reversal: rateToMatch only.
     * 2026-08-01
     */
    public static float harmonicRateToMatch(float masterBpm, float otherBpm) {
        float m = masterBpm > 30f ? masterBpm : DEFAULT_BPM;
        float o = otherBpm > 30f ? otherBpm : DEFAULT_BPM;
        float best = rateToMatch(m, o);
        float bestErr = Math.abs(best - 1f);
        float[] harmonics = { 0.5f, 1f, 2f };
        for (int i = 0; i < harmonics.length; i++) {
            float r = m / (o * harmonics[i]);
            if (r < MIN_RATE || r > MAX_RATE) continue;
            float err = Math.abs(r - 1f);
            if (err < bestErr) {
                bestErr = err;
                best = r;
            }
        }
        return best;
    }

    public static int clampChopStep(int step) {
        if (step < 0) return 0;
        if (step >= CHOP_FRAC.length) return CHOP_FRAC.length - 1;
        return step;
    }

    public static int nudgeChopStep(int current, int steps) {
        return clampChopStep(current + steps);
    }

    /** Slice length in ms; 0 = beat roll off. */
    public static int chopSliceMs(float bpm, int chopStep) {
        int step = clampChopStep(chopStep);
        float frac = CHOP_FRAC[step];
        if (frac <= 0.001f) return 0;
        return Math.max(40, Math.round(msPerBar(bpm) * frac));
    }

    /**
     * Where the song should be after a beat-roll hold ends.
     * Layman: while you mash the pad the slice chatters; let go and jump ahead to “now”.
     * Technical: originPosMs + elapsedWallMs * rate, clamped to [0, durationMs].
     * Was: release left playhead on frozen chop anchor. Reversal: return originPosMs.
     * 2026-07-20
     */
    public static int beatRollCatchUpMs(int originPosMs, long elapsedWallMs, float rate,
            int durationMs) {
        float r = rate > 0.01f ? rate : 1f;
        long elapsed = elapsedWallMs > 0L ? elapsedWallMs : 0L;
        int catchUp = originPosMs + Math.round(elapsed * r);
        if (catchUp < 0) catchUp = 0;
        if (durationMs > 0) {
            int maxSeek = durationMs - 100;
            if (maxSeek < 0) maxSeek = 0;
            if (catchUp > maxSeek) catchUp = maxSeek;
        }
        return catchUp;
    }

    public static int screwIndexForRate(float rate) {
        int best = 0;
        float bestD = Math.abs(SCREW_RATES[0] - rate);
        for (int i = 1; i < SCREW_RATES.length; i++) {
            float d = Math.abs(SCREW_RATES[i] - rate);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    public static float nudgeScrewRate(float current, int steps) {
        int idx = screwIndexForRate(current) + steps;
        if (idx < 0) idx = 0;
        if (idx >= SCREW_RATES.length) idx = SCREW_RATES.length - 1;
        return SCREW_RATES[idx];
    }

    /**
     * Status label for beat-roll slice size.
     * Was: "Chop off" / fractions via chopLabel. Reversal: call chopLabel alias.
     * 2026-07-20
     */
    public static String rollLabel(int chopStep) {
        int s = clampChopStep(chopStep);
        if (s == 0) return "Roll off";
        if (s == 1) return "1/16";
        if (s == 2) return "1/8";
        if (s == 3) return "1/4";
        return "1/2";
    }

    /** Thin alias — prefer {@link #rollLabel}. 2026-07-20 */
    public static String chopLabel(int chopStep) {
        return rollLabel(chopStep);
    }
}
