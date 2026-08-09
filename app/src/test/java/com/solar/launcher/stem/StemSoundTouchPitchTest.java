package com.solar.launcher.stem;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Camelot key-match pitch math shared by the DJ Mix (MixPlayerHost) and stem
 * mashup (StemPlayerHost) paths. 2026-08-02
 */
public class StemSoundTouchPitchTest {

    @Test
    public void sameKeyIsUnity() {
        assertEquals(1f, StemSoundTouch.pitchFactorForKeys(0, true, 0, true), 0.0001f);
        assertEquals(1f, StemSoundTouch.pitchFactorForKeys(7, false, 7, false), 0.0001f);
    }

    @Test
    public void knownMajorFifthShiftsPlusSeven() {
        // Master C major (root 0) vs slave G major (root 7): diff = (0-7)%12 = -7 -> +12 = 5
        float f = StemSoundTouch.pitchFactorForKeys(0, true, 7, true);
        float expect = (float) Math.pow(2.0, 5.0 / 12.0);
        assertEquals(expect, f, 0.0001f);
    }

    @Test
    public void relativeMinorAdjustsThreeSemitones() {
        // Master C major (0, major) vs slave C minor (0, minor): relative-minor moves
        // target root -3 -> diff = -3 -> 2^(-3/12).
        float f = StemSoundTouch.pitchFactorForKeys(0, true, 0, false);
        float expect = (float) Math.pow(2.0, -3.0 / 12.0);
        assertEquals(expect, f, 0.0001f);
        // Master C minor (0, minor) vs slave C major (0, major): +3.
        float g = StemSoundTouch.pitchFactorForKeys(0, false, 0, true);
        assertEquals((float) Math.pow(2.0, 3.0 / 12.0), g, 0.0001f);
    }

    @Test
    public void clampWrapsWithinSixSemitones() {
        // diff of +8 wraps to -4; diff of -8 wraps to +4.
        float f = StemSoundTouch.pitchFactorForKeys(4, true, 8, true); // (4-8)%12 = -4
        assertEquals((float) Math.pow(2.0, -4.0 / 12.0), f, 0.0001f);
        float g = StemSoundTouch.pitchFactorForKeys(8, true, 4, true); // (8-4)%12 = 4
        assertEquals((float) Math.pow(2.0, 4.0 / 12.0), g, 0.0001f);
        // Boundary ±6 stays.
        float h = StemSoundTouch.pitchFactorForKeys(0, true, 6, true); // -6
        assertEquals((float) Math.pow(2.0, -6.0 / 12.0), h, 0.0001f);
    }

    @Test
    public void unknownKeyIsUnityNoShift() {
        assertEquals(1f, StemSoundTouch.pitchFactorForKeys(-1, true, 0, true), 0.0001f);
        assertEquals(1f, StemSoundTouch.pitchFactorForKeys(0, true, -1, true), 0.0001f);
        assertEquals(1f, StemSoundTouch.pitchFactorForKeys(-1, false, -1, false), 0.0001f);
    }

    @Test
    public void pitchIsReasonableRange() {
        // Any known-key pair stays within ±6 semitones -> 0.707..1.414.
        for (int a = 0; a < 12; a++) {
            for (int b = 0; b < 12; b++) {
                for (int m = 0; m < 2; m++) {
                    for (int s = 0; s < 2; s++) {
                        float f = StemSoundTouch.pitchFactorForKeys(a, m == 1, b, s == 1);
                        assertTrue("range a=" + a + " b=" + b + " f=" + f,
                                f >= 0.7f && f <= 1.42f);
                    }
                }
            }
        }
    }
}
