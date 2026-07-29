package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** BPM + beat-roll slice math + catch-up + beat snap. 2026-07-19 / 2026-07-20 */
public class StemBpmTest {

    @Test
    public void chopSliceEighthAt120() {
        assertEquals(250, StemBpm.chopSliceMs(120f, 2));
    }

    @Test
    public void chopOffIsZero() {
        assertEquals(0, StemBpm.chopSliceMs(120f, 0));
    }

    @Test
    public void rateClamp() {
        assertEquals(1f, StemBpm.rateToMatch(120f, 120f), 0.001f);
        assertTrue(StemBpm.rateToMatch(120f, 200f) >= StemBpm.MIN_RATE);
    }

    @Test
    public void chopHoldMsNearRealtime() {
        assertEquals(350L, StemControls.STEM_STUTTER_HOLD_MS);
    }

    /** Beat-roll size ladder: off → 1/16 → 1/8 → 1/4 → 1/2. 2026-07-20 */
    @Test
    public void nudgeChopStepLadder() {
        assertEquals(0, StemBpm.nudgeChopStep(0, -1));
        assertEquals(1, StemBpm.nudgeChopStep(0, 1));
        assertEquals(2, StemBpm.nudgeChopStep(1, 1));
        assertEquals(4, StemBpm.nudgeChopStep(3, 5));
        assertEquals(3, StemBpm.nudgeChopStep(4, -1));
    }

    /** Classic screw ladder while Center peek during beat roll. 2026-07-20 */
    @Test
    public void nudgeScrewRateLadder() {
        assertEquals(1f, StemBpm.nudgeScrewRate(1f, -1), 0.001f);
        assertEquals(0.85f, StemBpm.nudgeScrewRate(1f, 1), 0.001f);
        assertEquals(0.7f, StemBpm.nudgeScrewRate(0.85f, 1), 0.001f);
        assertEquals(0.55f, StemBpm.nudgeScrewRate(0.7f, 1), 0.001f);
        assertEquals(0.55f, StemBpm.nudgeScrewRate(0.55f, 1), 0.001f);
        assertEquals(0.85f, StemBpm.nudgeScrewRate(0.7f, -1), 0.001f);
    }

    @Test
    public void snapToBeatAt120() {
        assertEquals(500, StemBpm.msPerBeat(120f));
        assertEquals(1000, StemBpm.snapToBeatMs(1100, 120f));
        assertEquals(1000, StemBpm.snapToBeatMs(900, 120f));
        assertEquals(0, StemBpm.snapToBeatMs(100, 120f));
    }

    /**
     * Beat-roll catch-up at 120 BPM feel: 1s wall @ rate 1.0 advances 1s.
     * 2026-07-20
     */
    @Test
    public void beatRollCatchUpRateOne() {
        assertEquals(2000, StemBpm.beatRollCatchUpMs(1000, 1000L, 1f, 10_000));
    }

    /** Screw-slowed roll: wall time advances less on the timeline. 2026-07-20 */
    @Test
    public void beatRollCatchUpRateScrew() {
        assertEquals(1700, StemBpm.beatRollCatchUpMs(1000, 1000L, 0.7f, 10_000));
    }

    /** Past end clamps to duration. 2026-07-20 */
    @Test
    public void beatRollCatchUpClampsPastEnd() {
        assertEquals(9_900, StemBpm.beatRollCatchUpMs(9000, 2000L, 1f, 10_000));
        assertEquals(0, StemBpm.beatRollCatchUpMs(-50, 0L, 1f, 10_000));
    }

    /** Status copy: Roll off / fraction labels. 2026-07-20 */
    @Test
    public void rollLabelCopy() {
        assertEquals("Roll off", StemBpm.rollLabel(0));
        assertEquals("1/8", StemBpm.rollLabel(2));
        assertEquals(StemBpm.rollLabel(1), StemBpm.chopLabel(1));
    }
}
