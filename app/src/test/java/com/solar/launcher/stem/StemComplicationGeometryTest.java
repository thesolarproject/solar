package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Four-corner Stem dial geometry + 33 RPM + upright glyphs.
 * 2026-07-21
 */
public class StemComplicationGeometryTest {

    @Test
    public void dialRadiusAndProtrude() {
        float r = StemComplicationGeometry.dialRadius(360f);
        assertTrue(r >= 42f);
        assertTrue(r <= 78f);
        assertTrue(StemComplicationGeometry.artRadius(r) < r);
        assertTrue(StemComplicationGeometry.protrudeFrac() > 0f);
    }

    @Test
    public void cornerCentresProtrudeOffCanvas() {
        float w = 480f;
        float h = 360f;
        float r = StemComplicationGeometry.dialRadius(Math.min(w, h));
        float[] tl = StemComplicationGeometry.dialCenter(
                StemComplicationGeometry.Corner.TL, w, h, r);
        float[] tr = StemComplicationGeometry.dialCenter(
                StemComplicationGeometry.Corner.TR, w, h, r);
        float[] br = StemComplicationGeometry.dialCenter(
                StemComplicationGeometry.Corner.BR, w, h, r);
        float[] bl = StemComplicationGeometry.dialCenter(
                StemComplicationGeometry.Corner.BL, w, h, r);
        assertTrue(tl[0] < 0f && tl[1] < 0f);
        assertTrue(tr[0] > w && tr[1] < 0f);
        assertTrue(br[0] > w && br[1] > h);
        assertTrue(bl[0] < 0f && bl[1] > h);
    }

    @Test
    public void pathSpeedMatchesSlowRpm() {
        float r = 50f;
        float expect = (float) (2.0 * Math.PI * r * (StemComplicationGeometry.RPM / 60.0));
        assertEquals(expect, StemComplicationGeometry.pathPxPerSec(r), 0.01f);
        assertEquals(0f, StemComplicationGeometry.pathPxPerSec(0f), 0.001f);
        assertTrue(StemComplicationGeometry.RPM <= 16f);
    }

    @Test
    public void uprightGlyphNeverUpsideDown() {
        // Lower-arc tangents that used to invert must stay readable. 2026-07-21
        float rot = StemComplicationGeometry.uprightGlyphRotationDeg(270f);
        assertTrue(Math.abs(rot) <= 90f + 0.01f);
        float rot2 = StemComplicationGeometry.uprightGlyphRotationDeg(90f);
        assertTrue(Math.abs(rot2) <= 90f + 0.01f);
    }

    @Test
    public void topArchSweepInverts() {
        assertTrue(StemComplicationGeometry.rimSweepSign(
                StemComplicationGeometry.Corner.TL) < 0f);
        assertTrue(StemComplicationGeometry.rimSweepSign(
                StemComplicationGeometry.Corner.TR) < 0f);
        assertTrue(StemComplicationGeometry.rimSweepSign(
                StemComplicationGeometry.Corner.BR) > 0f);
    }

    @Test
    public void titleTextLargerForReadability() {
        float s = StemComplicationGeometry.titleTextSize(360f);
        assertTrue(s >= 13f);
        assertTrue(s <= 20f);
    }

    @Test
    public void optionsHoldKeepsWhenClockMissing() {
        // MTK KeyEvent times → 0: a real hold still measures via the uptime fallback
        // (600ms → Options kept). A 0ms measurement can only be a tap racing the hold
        // timer under UI lag — short tap wins, Options closes. 2026-08-01
        assertFalse(StemControls.isIntentionalPadOptionsHold(true, 0L));
        assertTrue(StemControls.isIntentionalPadOptionsHold(true, 600L));
        assertTrue(!StemControls.isIntentionalPadOptionsHold(true, 100L));
        assertTrue(!StemControls.isIntentionalPadOptionsHold(false, 0L));
        long local = StemControls.bestPhysicalHoldMs(1000L, 1600L, 0L, 0L);
        assertEquals(600L, local);
    }

    @Test
    public void prepLabelHonest() {
        assertEquals("Ready", StemComplicationGeometry.prepDialLabel(null, false));
        assertEquals("Waiting", StemComplicationGeometry.prepDialLabel(
                QueuePrepStatus.KEY_IDLE, true));
        assertEquals("Loading stems…", StemComplicationGeometry.prepDialLabel(
                QueuePrepStatus.KEY_STEMS, true));
        assertEquals(0f, StemComplicationGeometry.prepDialFraction(false), 0.001f);
        assertTrue(StemComplicationGeometry.prepDialFraction(true) > 0f);
    }
}
