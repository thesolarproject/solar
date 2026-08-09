package com.solar.launcher.stem.analysis;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM tests for the stem analysis engine using synthetic click tracks —
 * no Android, no audio files. 2026-08-01
 */
public class StemAnalysisCoreTest {
    private static final int HZ = StemAnalysisCore.HZ;

    /** Build a click track: narrow impulse bursts every beatMs, plus a quiet noise floor. */
    private static short[] clickTrack(int beatMs, int durationMs, int phaseMs, int seed) {
        int n = HZ * durationMs / 1000;
        short[] pcm = new short[n];
        java.util.Random rng = new java.util.Random(seed);
        for (int t = phaseMs; t < durationMs; t += beatMs) {
            int start = t * HZ / 1000;
            for (int i = 0; i < 64 && start + i < n; i++) {
                pcm[start + i] = (short) (24000 * Math.sin(i * 0.4));
            }
        }
        for (int i = 0; i < n; i++) {
            pcm[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, pcm[i] + rng.nextInt(300)));
        }
        return pcm;
    }

    @Test
    public void estimates120BpmFromClickTrack() {
        short[] pcm = clickTrack(500, 30_000, 0, 7);
        StemAnalysisCore.Result r = StemAnalysisCore.analyze(pcm, 0, pcm.length);
        assertNotNull(r);
        assertTrue("bpm=" + r.bpm, r.bpm >= 114f && r.bpm <= 126f);
        assertTrue("confidence=" + r.confidence, r.confidence > 0.2f);
    }

    @Test
    public void estimates100BpmFromClickTrack() {
        short[] pcm = clickTrack(600, 30_000, 0, 11);
        StemAnalysisCore.Result r = StemAnalysisCore.analyze(pcm, 0, pcm.length);
        assertNotNull(r);
        assertTrue("bpm=" + r.bpm, r.bpm >= 95f && r.bpm <= 105f);
    }

    @Test
    public void estimates150BpmFromClickTrack() {
        short[] pcm = clickTrack(400, 24_000, 0, 13);
        StemAnalysisCore.Result r = StemAnalysisCore.analyze(pcm, 0, pcm.length);
        assertNotNull(r);
        assertTrue("bpm=" + r.bpm, r.bpm >= 143f && r.bpm <= 157f);
    }

    @Test
    public void phaseMatchesClickOffset() {
        // Clicks starting 200 ms into the window at 120 BPM.
        short[] pcm = clickTrack(500, 24_000, 200, 3);
        StemAnalysisCore.Result r = StemAnalysisCore.analyze(pcm, 0, pcm.length);
        assertNotNull(r);
        assertTrue("phaseMs=" + r.phaseMs, r.phaseMs >= 100 && r.phaseMs <= 300);
    }

    @Test
    public void zeroPhaseWhenClicksAtStart() {
        short[] pcm = clickTrack(500, 24_000, 0, 5);
        StemAnalysisCore.Result r = StemAnalysisCore.analyze(pcm, 0, pcm.length);
        assertNotNull(r);
        assertTrue("phaseMs=" + r.phaseMs, r.phaseMs < 200);
    }

    @Test
    public void detectsCMajorTriad() {
        // C4 + E4 + G4 sines → C major (root 0, major).
        int n = HZ * 10;
        short[] pcm = new short[n];
        double[] freqs = { 261.63, 329.63, 392.00 };
        for (int i = 0; i < n; i++) {
            double t = i / (double) HZ;
            double v = 0;
            for (int f = 0; f < freqs.length; f++) v += Math.sin(2 * Math.PI * freqs[f] * t);
            pcm[i] = (short) (8000 * v / freqs.length);
        }
        int[] key = StemAnalysisCore.detectKey(pcm, 0, pcm.length);
        assertEquals("root should be C(0)", 0, key[0]);
        assertEquals("should be major", 1, key[1]);
    }

    @Test
    public void camelotMap() {
        assertEquals("8B", StemAnalysisCore.camelotFor(0, true));    // C major
        assertEquals("5A", StemAnalysisCore.camelotFor(0, false));   // C minor
        assertEquals("10A", StemAnalysisCore.camelotFor(11, false)); // B minor
        assertEquals("12B", StemAnalysisCore.camelotFor(4, true));   // E major
        assertEquals("1B", StemAnalysisCore.camelotFor(11, true));   // B major
        assertEquals("G major", StemAnalysisCore.keyLabel(7, true));
        assertEquals("E minor", StemAnalysisCore.keyLabel(4, false));
    }

    @Test
    public void fftIsCorrect() {
        int n = 8;
        float[] re = new float[n];
        float[] im = new float[n];
        re[0] = 1f;
        StemAnalysisCore.fft(re, im);
        for (int i = 0; i < n; i++) {
            assertEquals(1f, re[i], 1e-4f);
            assertEquals(0f, im[i], 1e-4f);
        }
    }
}
