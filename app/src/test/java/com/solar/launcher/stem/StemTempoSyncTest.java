package com.solar.launcher.stem;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tempo rate clamp for Mix/Stem beat sync. 2026-07-19
 */
public class StemTempoSyncTest {

    @Test
    public void masterAlwaysOne() {
        assertEquals(1f, StemTempoSync.rateForSong(120f, 100f, 0), 0.001f);
    }

    @Test
    public void slaveMatchesWithinClamp() {
        float r = StemTempoSync.rateForSong(120f, 100f, 1);
        assertTrue(r > 1f);
        assertTrue(r <= StemBpm.MAX_RATE);
        assertTrue(StemTempoSync.needsSoundTouch(r));
    }

    @Test
    public void tinyDeltaIsUnity() {
        float r = StemTempoSync.rateForSong(120f, 120.5f, 1);
        assertEquals(1f, r, 0.001f);
        assertFalse(StemTempoSync.needsSoundTouch(r));
    }

    /** Tempo bus × pad screw — Houston feel without dropping match. 2026-07-20 */
    @Test
    public void composePadRateMultiplies() {
        assertEquals(0.77f, StemTempoSync.composePadRate(1.1f, 0.7f), 0.01f);
        assertEquals(0.5f, StemTempoSync.composePadRate(1f, 0.4f), 0.001f);
    }

    /** Slave media clock scales with tempoRate under IJK setSpeed. 2026-07-20 */
    @Test
    public void expectedSlavePosScales() {
        // First-beat args added 2026-08-01; 0 keeps the old numeric expectations.
        assertEquals(1100, StemTempoSync.expectedSlavePosMs(1000, 1f, 0, 1.1f, 0));
        assertEquals(1000, StemTempoSync.expectedSlavePosMs(1000, 1f, 0, 1f, 0));
    }

    /** Half-time harmonic: 60 BPM slave pairs with 120 BPM master at unity. 2026-08-01 */
    @Test
    public void harmonicHalfTimeUnity() {
        assertEquals(1f, StemTempoSync.rateForSong(120f, 60f, 1), 0.001f);
        assertFalse(StemTempoSync.needsSoundTouch(StemTempoSync.rateForSong(120f, 60f, 1)));
    }

    /** Double-time harmonic: 240 BPM slave pairs with 120 BPM master at unity. 2026-08-01 */
    @Test
    public void harmonicDoubleTimeUnity() {
        assertEquals(1f, StemTempoSync.rateForSong(120f, 240f, 1), 0.001f);
    }

    /** Quantised lockstep target lands on the slave's beat grid. 2026-08-01 */
    @Test
    public void quantizedSlavePosSnapsToBeatGrid() {
        // 120 BPM slave → 500ms beat; 1120 → nearest beat 1000.
        assertEquals(1000, StemTempoSync.expectedSlavePosMsQuantized(1000, 1f, 0, 1f, 0, 120f));
        assertEquals(1000, StemTempoSync.expectedSlavePosMsQuantized(1120, 1f, 0, 1f, 0, 120f));
        assertEquals(1500, StemTempoSync.expectedSlavePosMsQuantized(1600, 1f, 0, 1f, 0, 120f));
    }

    /** Phase-aligned replacement start keeps the new song on the survivor's pulse. 2026-08-01 */
    @Test
    public void phaseAlignedStartKeepsPulse() {
        // Survivor master at 5000 (rate 1.0), replacement slave rate 1.1 → 5000*1.1.
        assertEquals(5500, StemTempoSync.phaseAlignedStartMs(5000, 1f, 0, 1.1f, 0, 120f));
        // Survivor slave (rate 1.1) at 5500 → new master (rate 1.0) at 5000.
        assertEquals(5000, StemTempoSync.phaseAlignedStartMs(5500, 1.1f, 0, 1f, 0, 120f));
    }

    /** Seed / first-in-queue is the tempo master — partner stretches to it. 2026-08-01 */
    @Test
    public void seedBpmIsMaster() {
        // Seed wins even when it's the slower beat — the partner speeds up to match.
        assertEquals(100f, StemTempoSync.seedMasterBpm(100f, 120f), 0.001f);
        assertEquals(120f, StemTempoSync.seedMasterBpm(120f, 100f), 0.001f);
        // Unknown seed BPM defers to the known partner.
        assertEquals(120f, StemTempoSync.seedMasterBpm(0f, 120f), 0.001f);
        assertEquals(120f, StemTempoSync.seedMasterBpm(120f, 0f), 0.001f);
        // Both unknown → default.
        assertEquals(StemBpm.DEFAULT_BPM, StemTempoSync.seedMasterBpm(0f, 0f), 0.001f);
    }

    /** rateToMatchMaster is index-free: the master song itself stays 1.0. 2026-08-01 */
    @Test
    public void rateToMatchMasterKeepsMasterUnity() {
        // 120 master, 100 slave → slave stretches up within clamp.
        float r = StemTempoSync.rateToMatchMaster(120f, 100f);
        assertTrue(r > 1f);
        assertTrue(r <= StemBpm.MAX_RATE);
        assertTrue(StemTempoSync.needsSoundTouch(r));
        // Master song itself → unity regardless of which seat it occupies.
        assertEquals(1f, StemTempoSync.rateToMatchMaster(120f, 120f), 0.001f);
        // Harmonic half-time still pairs at unity against the faster master.
        assertEquals(1f, StemTempoSync.rateToMatchMaster(120f, 60f), 0.001f);
    }

}
