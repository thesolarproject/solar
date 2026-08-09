package com.solar.launcher.stem;

/**
 * Equal-power + stem-stagger blend curves for song-swap and soft scrub.
 * Layman: blends keep loudness steady; drums/bass move first, vocals hang on a beat longer.
 * Technical: √sin/√cos equal-power; zone lag fractions; optional bass snap at 50%.
 * Was: {@link StemControls#fadeGainStep} linear only. Reversal: call fadeGainStep everywhere.
 * Zones: 0=vocals · 1=drums · 2=bass · 3=melody (StemMashupFaceView).
 * 2026-07-21
 */
public final class StemBlendGains {
    /** Vocals lag ~35% of the transition window (StemFM-style). 2026-07-21 */
    public static final float VOCALS_LAG_FRAC = 0.35f;
    /** Melody slight lag after drums. 2026-07-21 */
    public static final float MELODY_LAG_FRAC = 0.15f;
    /** Drums/bass lead (no lag). 2026-07-21 */
    public static final float DRUMS_LAG_FRAC = 0f;
    public static final float BASS_LAG_FRAC = 0f;
    /** Bass snap midpoint (A→B jump). 2026-07-21 */
    public static final float BASS_SWAP_AT = 0.5f;

    private StemBlendGains() {}

    /** Clamp 0..1. 2026-07-21 */
    public static float clamp01(float t) {
        if (t < 0f) return 0f;
        if (t > 1f) return 1f;
        return t;
    }

    /**
     * Equal-power fade-out curve (old timeline): cos(π/2 · t).
     * Layman: old song gets quieter without a volume dip in the middle.
     * Technical: classic equal-power — out²+in² ≈ 1. Was: √cos (wrong power sum).
     * 2026-07-21
     */
    public static float equalPowerOut(float t) {
        float x = clamp01(t);
        return (float) Math.cos(x * Math.PI * 0.5);
    }

    /**
     * Equal-power fade-in curve (new timeline): sin(π/2 · t).
     * Layman: new song rises so total loudness stays roughly even.
     * 2026-07-21
     */
    public static float equalPowerIn(float t) {
        float x = clamp01(t);
        return (float) Math.sin(x * Math.PI * 0.5);
    }

    /**
     * Linear mix kept for wave preset / tests — prefer equal-power for LONG/∞.
     * 2026-07-21
     */
    public static float linear(float from, float to, float t) {
        float x = clamp01(t);
        return from + (to - from) * x;
    }

    /**
     * Zone lag as fraction of transition (0 = earliest).
     * Layman: which stems move first when blending songs.
     * 2026-07-21
     */
    public static float zoneLagFrac(int zone) {
        if (zone == 0) return VOCALS_LAG_FRAC; // vocals
        if (zone == 1) return DRUMS_LAG_FRAC;  // drums
        if (zone == 2) return BASS_LAG_FRAC;   // bass
        if (zone == 3) return MELODY_LAG_FRAC; // melody
        return 0f;
    }

    /**
     * Remap global progress t into a zone-local 0..1 after lag.
     * Layman: vocals wait a beat before they start fading.
     * Technical: t' = clamp((t - lag) / (1 - lag)).
     * 2026-07-21
     */
    public static float zoneLocalT(float globalT, int zone) {
        float lag = zoneLagFrac(zone);
        if (lag <= 0.001f) return clamp01(globalT);
        float span = 1f - lag;
        if (span < 0.05f) span = 0.05f;
        return clamp01((globalT - lag) / span);
    }

    /**
     * Outgoing zone gain during staggered equal-power blend.
     * Bass optional snap: after midpoint, out snaps to 0 (muddy mid-cross avoided).
     * 2026-07-21
     */
    public static float staggeredOutGain(float fromGain, float globalT, int zone,
            boolean bassSnap) {
        if (bassSnap && zone == 2 && globalT >= BASS_SWAP_AT) {
            return 0f;
        }
        float local = zoneLocalT(globalT, zone);
        return fromGain * equalPowerOut(local);
    }

    /**
     * Incoming zone gain during staggered equal-power blend.
     * Bass snap: before midpoint stay 0; at/after jump toward target.
     * 2026-07-21
     */
    public static float staggeredInGain(float toGain, float globalT, int zone,
            boolean bassSnap) {
        if (bassSnap && zone == 2) {
            if (globalT < BASS_SWAP_AT) return 0f;
            // After snap, rise with remaining window. 2026-07-21
            float local = clamp01((globalT - BASS_SWAP_AT) / (1f - BASS_SWAP_AT));
            return toGain * equalPowerIn(local);
        }
        float local = zoneLocalT(globalT, zone);
        return toGain * equalPowerIn(local);
    }

    /**
     * Soft-scrub dual-timeline gains for one zone at progress t (0..1).
     * Returns [outGain, inGain] scaled by from/to levels.
     * 2026-07-21
     */
    public static float[] softScrubZoneGains(float fromGain, float toGain, float globalT,
            int zone, boolean bassSnap) {
        return new float[] {
                staggeredOutGain(fromGain, globalT, zone, bassSnap),
                staggeredInGain(toGain, globalT, zone, bassSnap)
        };
    }

    /**
     * Wave preset: nearly linear snap (Spotify-like sharp cut-in).
     * 2026-07-21
     */
    public static boolean useEqualPowerForPreset(int transitionPreset) {
        return transitionPreset != StemControls.TRANSITION_PRESET_SHORT;
    }

    /**
     * Step-based equal-power fade (replaces linear fadeGainStep for LONG/∞).
     * Layman: each tick of the blend uses the smooth loudness curve.
     * 2026-07-21
     */
    public static float equalPowerFadeStep(float from, float to, int stepIndex, int totalSteps) {
        if (totalSteps < 1) return to;
        if (stepIndex >= totalSteps) return to;
        float t = stepIndex / (float) totalSteps;
        float w = equalPowerIn(t);
        return from + (to - from) * w;
    }
}
