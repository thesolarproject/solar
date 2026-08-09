package com.solar.launcher.stem;

/**
 * Song-to-song tempo match helpers — the seed (first-in-queue / selected track) is master.
 * Layman: speed the other song so it sits on the seed’s pulse without chipmunking.
 * Technical: StemBpm.rateToMatch → IJK SoundTouch when |rate-1| > epsilon; else 1.0 MediaPlayer.
 * Was: no cross-song rate. Reversal: always leave rate=1 and rely on seek drift only.
 * 2026-07-19 / 2026-08-01
 */
public final class StemTempoSync {
    /** Ignore tiny BPM estimate noise. */
    public static final float RATE_EPSILON = 0.02f;

    private StemTempoSync() {}

    /**
     * Seed tempo master for a pair — the first-in-queue / selected (seed) track
     * keeps its native BPM and the other track is speed-adjusted onto it, which
     * is how StemFM keeps the dominant song leading the beat. Ties are irrelevant
     * (seed wins by definition); an unknown seed BPM (<= 30) defers to the partner,
     * and both unknown → DEFAULT_BPM.
     * Layman: whichever song you picked/are-seeding leads; the other stretches to it.
     * Technical: returns bpmA (the seed) whenever valid; else bpmB; else DEFAULT_BPM.
     * Was: faster BPM wins. Reversal: faster-BPM-wins (user picked seed-leads).
     * 2026-08-01
     */
    public static float seedMasterBpm(float seedBpm, float partnerBpm) {
        if (seedBpm > 30f) return seedBpm;
        if (partnerBpm > 30f) return partnerBpm;
        return StemBpm.DEFAULT_BPM;
    }

    /**
     * Rate so songBpm sits on masterBpm's pulse. The song carrying the master BPM
     * itself (or a harmonic half/double-time groove) → unity; otherwise the DJ
     * speed match, epsilon-collapsed so tiny BPM noise stays native-speed.
     * Technical: harmonicRateToMatch, collapsed to 1.0 within RATE_EPSILON.
     * Was: rateForSong's index-based master. Reversal: rateForSong only.
     * 2026-08-01
     */
    public static float rateToMatchMaster(float masterBpm, float songBpm) {
        if (songBpm <= 30f) return 1f;
        float r = StemBpm.harmonicRateToMatch(masterBpm, songBpm);
        if (Math.abs(r - 1f) < RATE_EPSILON) return 1f;
        return r;
    }

    /**
     * Rate for songIndex to match master. Kept for API/tests; production uses
     * {@link #seedMasterBpm} + {@link #rateToMatchMaster} so the seed (first-in-
     * queue / selected) track leads regardless of which seat it lands on.
     * Legacy song-0-master path always 1.0.
     * 2026-07-19 / 2026-08-01
     */
    public static float rateForSong(float masterBpm, float songBpm, int songIndex) {
        if (songIndex <= 0) return 1f;
        return rateToMatchMaster(masterBpm, songBpm);
    }

    /** True when this song needs pitch-preserving stretch (IJK SoundTouch). */
    public static boolean needsSoundTouch(float rate) {
        return Math.abs(rate - 1f) >= RATE_EPSILON;
    }

    /**
     * Combine song tempo bus with pad screw (classic Houston feel on top of match).
     * Layman: slow the pad while the song still tries to sit on Song 1’s pulse.
     * Technical: tempoRate * screwRate; clamp to SCREW floor / MAX_RATE.
     * Was: hold screw replaced tempoRate. Reversal: return screw only.
     * 2026-07-20
     */
    public static float composePadRate(float tempoRate, float screwRate) {
        float t = tempoRate > 0.1f ? tempoRate : 1f;
        float s = screwRate > 0.1f ? screwRate : 1f;
        float r = t * s;
        if (r < 0.5f) return 0.5f;
        if (r > StemBpm.MAX_RATE) return StemBpm.MAX_RATE;
        return r;
    }

    /**
     * Expected media position for a slave song given Song 1 lead position + tempo rate.
     * Layman: where song 2/3 should be if they started together at matched speed.
     * Technical: leadPosMs * tempoRate (IJK setSpeed advances media clock by rate).
     * Was: compare raw getPositionMs across songs. Reversal: return leadPosMs.
     * 2026-07-20
     */
    public static int expectedSlavePosMs(int leadPosMs, float leadRate, int leadFirstBeatMs, float slaveRate, int slaveFirstBeatMs) {
        int l = Math.max(0, leadPosMs);
        float lr = leadRate > 0.1f ? leadRate : 1f;
        float sr = slaveRate > 0.1f ? slaveRate : 1f;
        
        // Media time elapsed from the first downbeat of the lead song
        int leadElapsedMs = l - leadFirstBeatMs;
        
        // Convert to slave's timeline
        int slaveElapsedMs = Math.round(leadElapsedMs * (sr / lr));
        
        return Math.max(0, slaveFirstBeatMs + slaveElapsedMs);
    }

    /**
     * Lockstep target snapped to the slave's effective beat grid, so drift
     * corrections land on the pulse instead of micro-seeking off-beat.
     * Layman: nudge a drifting track straight back onto the nearest beat.
     * Technical: expectedSlavePosMs then StemBpm.snapToBeatMs with slaveBpm*rate.
     * Was: raw expectedSlavePosMs only. Reversal: expectedSlavePosMs only.
     * 2026-08-01
     */
    public static int expectedSlavePosMsQuantized(
            int leadPosMs, float leadRate, int leadFirstBeatMs, 
            float slaveRate, int slaveFirstBeatMs, float slaveBpm) {
        int expect = expectedSlavePosMs(leadPosMs, leadRate, leadFirstBeatMs, slaveRate, slaveFirstBeatMs);
        float eff = slaveBpm > 30f ? slaveBpm * (slaveRate > 0.1f ? slaveRate : 1f) : slaveBpm;
        return StemBpm.snapToBeatMs(expect, eff, slaveFirstBeatMs);
    }

    /**
     * Beat-aligned start for a soft-replaced song so it lands on the surviving
     * master's pulse at the crossfade.
     * Layman: the new track drops in already on the beat the old one was on.
     * Technical: media pos = wallClock × rate, so myPos = survivorPos × myRate/
     * survivorRate (same convention as {@link #expectedSlavePosMs}); then snap to
     * the new song's beat grid.
     * Was: survivorPos × sr/mr (inverted — landed on the wrong side of the master).
     * 2026-08-01
     */
    public static int phaseAlignedStartMs(int survivorPosMs, float survivorRate, int survivorFirstBeatMs,
            float myRate, int myFirstBeatMs, float myBpm) {
        float sr = survivorRate > 0.1f ? survivorRate : 1f;
        float mr = myRate > 0.1f ? myRate : 1f;
        
        // Musical phase from first beat
        int survivorElapsedMs = Math.max(0, survivorPosMs) - survivorFirstBeatMs;
        
        // Convert to new track's timeline
        int myElapsedMs = Math.round(survivorElapsedMs * (mr / sr));
        int pos = Math.max(0, myFirstBeatMs + myElapsedMs);
        
        return StemBpm.snapToBeatMs(pos, myBpm, myFirstBeatMs);
    }

}
