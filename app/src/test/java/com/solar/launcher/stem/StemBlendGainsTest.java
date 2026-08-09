package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Equal-power + stem-stagger blend curves. 2026-07-21 */
public class StemBlendGainsTest {

    @Test
    public void equalPowerEndpoints() {
        assertEquals(1f, StemBlendGains.equalPowerOut(0f), 0.01f);
        assertEquals(0f, StemBlendGains.equalPowerOut(1f), 0.01f);
        assertEquals(0f, StemBlendGains.equalPowerIn(0f), 0.01f);
        assertEquals(1f, StemBlendGains.equalPowerIn(1f), 0.01f);
    }

    @Test
    public void midBlendKeepsLoudnessRoughlyConstant() {
        float t = 0.5f;
        float out = StemBlendGains.equalPowerOut(t);
        float in = StemBlendGains.equalPowerIn(t);
        // Equal-power: out² + in² ≈ 1
        assertEquals(1f, out * out + in * in, 0.05f);
    }

    @Test
    public void vocalsLagBehindDrums() {
        float t = 0.2f;
        float drumsLocal = StemBlendGains.zoneLocalT(t, 1);
        float vocalsLocal = StemBlendGains.zoneLocalT(t, 0);
        assertTrue(drumsLocal > vocalsLocal);
        assertEquals(0f, vocalsLocal, 0.001f); // still in lag window
    }

    @Test
    public void bassSnapAtMidpoint() {
        float early = StemBlendGains.staggeredOutGain(1f, 0.4f, 2, true);
        float late = StemBlendGains.staggeredOutGain(1f, 0.6f, 2, true);
        assertTrue(early > 0.1f);
        assertEquals(0f, late, 0.001f);
        float inEarly = StemBlendGains.staggeredInGain(1f, 0.4f, 2, true);
        float inLate = StemBlendGains.staggeredInGain(1f, 0.6f, 2, true);
        assertEquals(0f, inEarly, 0.001f);
        assertTrue(inLate > 0f);
    }

    @Test
    public void waveUsesLinearPathFlag() {
        assertFalse(StemBlendGains.useEqualPowerForPreset(StemControls.TRANSITION_PRESET_SHORT));
        assertTrue(StemBlendGains.useEqualPowerForPreset(StemControls.TRANSITION_PRESET_FULL));
    }

    @Test
    public void softScrubReturnsPair() {
        float[] g = StemBlendGains.softScrubZoneGains(1f, 1f, 0.5f, 1, false);
        assertEquals(2, g.length);
        assertTrue(g[0] > 0f && g[0] < 1f);
        assertTrue(g[1] > 0f && g[1] < 1f);
    }
}
