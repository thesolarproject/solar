package com.solar.launcher.stem.analysis;

import java.util.Arrays;

/**
 * Pure-JVM stem analysis engine — no Android imports, so every step is unit-testable.
 * Layman: listen to the drums for a few seconds and work out the real tempo, where
 * the first beat lands, and which key the song is in — instead of guessing from length.
 * Technical: Hann-window FFT spectral flux onset envelope → autocorrelation BPM (with
 * half/double harmonic voting) → comb-scan downbeat phase → chroma vector vs
 * Krumhansl-Schmuckler key profiles → Camelot notation. 2026-08-01
 */
public final class StemAnalysisCore {
    /** Analysis sample rate — matches the 22.05 kHz mono pipeline. */
    public static final int HZ = 22050;
    /** Onset-envelope hop — must stay in lockstep with {@code onsetEnvelope}. 2026-08-01 */
    public static final int HOP = 128;
    /** ~60–180 BPM search window (period 1000–333 ms). */
    public static final float BPM_MIN = 60f;
    public static final float BPM_MAX = 180f;

    private StemAnalysisCore() {}

    /** Result of a full track analysis. */
    public static final class Result {
        public float bpm = 120f;
        public float confidence = 0f;
        /** Time of the first detected downbeat from the analysed window start, ms. */
        public int phaseMs = 0;
        /** 12-EDO root pitch class 0=C … 11=B (Camelot 8A is C minor). */
        public int keyRoot = -1;
        public boolean keyMajor = true;
        public String keyLabel = "";
        public String camelot = "";
        /** Estimated first-downbeat time of the FULL track (0 when unknown). */
        public int firstBeatMs = 0;

        public boolean hasKey() {
            return keyRoot >= 0 && keyRoot < 12;
        }

        public boolean hasBpm() {
            return bpm > 30f && confidence > 0.2f;
        }
    }

    /** Half-Hann window for FFT frames. */
    private static float hann(int i, int n) {
        return 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (n - 1)));
    }

    /** In-place radix-2 FFT; n must be a power of two. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        if (n < 2) return;
        if ((n & (n - 1)) != 0) throw new IllegalArgumentException("FFT size not power of two");
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            float ang = (float) (-2.0 * Math.PI / len);
            float wRe = (float) Math.cos(ang);
            float wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cRe = 1f, cIm = 0f;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j;
                    int b = a + len / 2;
                    float uRe = re[a], uIm = im[a];
                    float vRe = re[b] * cRe - im[b] * cIm;
                    float vIm = re[b] * cIm + im[b] * cRe;
                    re[a] = uRe + vRe; im[a] = uIm + vIm;
                    re[b] = uRe - vRe; im[b] = uIm - vIm;
                    float nRe = cRe * wRe - cIm * wIm;
                    cIm = cRe * wIm + cIm * wRe;
                    cRe = nRe;
                }
            }
        }
    }

    /**
     * Build an onset envelope from mono PCM — short-time energy RISE per hop.
     * Layman: a loud spike where a beat or transient starts.
     * Technical: rectangular 256-sample frames at hop {@link #HOP}; onset = max(0,
     * energy[frame] − energy[prev]); normalised 0..1. Energy (not spectral flux) keeps
     * transients at frame edges (which Hann windows attenuate to zero) and is cheap on
     * Y1 — no FFT needed for the tempo path.
     */
    public static float[] onsetEnvelope(short[] pcm, int offset, int length) {
        int frame = 256;
        int hop = HOP;
        int nFrames = Math.max(0, (length - frame) / hop + 1);
        if (nFrames < 4) return new float[0];
        float[] en = new float[nFrames];
        float maxEn = 1e-9f;
        for (int f = 0; f < nFrames; f++) {
            int start = offset + f * hop;
            double sum = 0.0;
            int end = Math.min(start + frame, offset + length);
            for (int i = start; i < end; i++) {
                float s = pcm[i] / 32768f;
                sum += s * s;
            }
            en[f] = (float) sum;
            if (en[f] > maxEn) maxEn = en[f];
        }
        float[] onset = new float[nFrames];
        for (int f = 0; f < nFrames; f++) {
            float prev = f > 0 ? en[f - 1] : 0f;
            float rise = en[f] - prev;
            onset[f] = rise > 0f ? rise / maxEn : 0f;
        }
        return onset;
    }

    /**
     * BPM estimate from the onset envelope via RAW autocorrelation sums, then DJ-style
     * octave resolution: pick the fastest tempo (shortest lag) whose correlation is
     * within 85% of the peak.
     *
     * Why raw sums, not normalised correlation or per-pulse averages: a perfectly
     * periodic click train correlates at its true period AND every integer multiple,
     * and per-pulse averaging ties them all — the debug builds showed lag=155 (67 BPM)
     * "winning" every tempo. But in a fixed-length window the FUNDAMENTAL lag pairs up
     * more onsets than any multiple (60 clicks vs 30 pairs at double period), so the
     * raw sum is highest at the true tempo with no scoring games. The 85%-of-peak
     * shortest-lag rule then keeps 120 over 60, 150 over 75 for real music where a
     * half-time groove can look ambiguous. 2026-08-01
     */
    public static float estimateBpm(float[] flux, float[] outConfidence) {
        if (flux == null || flux.length < 16) return 120f;
        float hopSec = HOP / (float) HZ;
        int lagMin = Math.max(4, (int) Math.round((60f / BPM_MAX) / hopSec));
        int lagMax = Math.min(flux.length - 1, (int) Math.round((60f / BPM_MIN) / hopSec) + 1);
        if (lagMax <= lagMin) return 120f;
        float[] ac = new float[lagMax + 1];
        for (int lag = lagMin; lag <= lagMax; lag++) {
            float sum = 0f;
            for (int i = 0; i + lag < flux.length; i++) {
                sum += flux[i] * flux[i + lag];
            }
            ac[lag] = sum;
        }
        float bestScore = 0f;
        int best = lagMin;
        for (int lag = lagMin; lag <= lagMax; lag++) {
            if (ac[lag] > bestScore) {
                bestScore = ac[lag];
                best = lag;
            }
        }
        if (bestScore <= 0f) return 120f;
        int chosen = lagMin;
        for (int lag = lagMin; lag <= best; lag++) {
            if (ac[lag] >= bestScore * 0.85f) {
                chosen = lag;
                break;
            }
        }
        float bpm = 60f / (chosen * hopSec);
        if (bpm < BPM_MIN) bpm = BPM_MIN;
        if (bpm > BPM_MAX) bpm = BPM_MAX;
        if (outConfidence != null) {
            // Fraction of the theoretically-expected pairs actually found at the chosen
            // lag — a clean pulse train is ~1.0, a beatless track drifts far below 0.2.
            float expected = flux.length / (float) chosen;
            outConfidence[0] = Math.max(0f,
                    Math.min(1f, expected > 0f ? ac[chosen] / expected : 0f));
        }
        return bpm;
    }

    /**
     * Peak score at a lag for the old autocorrelation path (kept for reference tests).
     */
    static float peakScoreAt(float[] ac, int lag, int radius) {
        if (lag < 1 || lag >= ac.length - 1) return 0f;
        float peak = ac[lag];
        for (int r = 1; r <= radius && lag - r >= 0; r++) peak = Math.max(peak, ac[lag - r]);
        for (int r = 1; r <= radius && lag + r < ac.length; r++) peak = Math.max(peak, ac[lag + r]);
        return peak;
    }

    /**
     * Downbeat phase — the time offset of the first beat pulse in the window.
     * Scans comb offsets over one beat period against the onset envelope and picks the
     * offset whose pulses capture the most onset energy (average per pulse, so an offset
     * aligned with every click beats one that only catches a few).
     */
    public static int estimatePhaseMs(float[] flux, float bpm, int analysisMs) {
        if (flux == null || flux.length < 8 || bpm <= 30f) return 0;
        float hopSec = HOP / (float) HZ;
        float beatSec = 60f / bpm;
        int beatHops = Math.max(2, Math.round(beatSec / hopSec));
        if (beatHops >= flux.length - 1) return 0;
        int bestOffset = 0;
        float bestScore = -1f;
        for (int off = 0; off < beatHops; off++) {
            float sum = 0f;
            int pulses = 0;
            for (int p = off; p < flux.length; p += beatHops) {
                sum += flux[p];
                pulses++;
            }
            if (pulses > 0) {
                float avg = sum / pulses;
                if (avg > bestScore) {
                    bestScore = avg;
                    bestOffset = off;
                }
            }
        }
        float ms = bestOffset * hopSec * 1000f;
        if (ms > beatSec * 1000f) ms = 0f;
        if (analysisMs > 0 && ms > analysisMs) ms = ms % Math.max(1, (int) (beatSec * 1000f));
        return Math.max(0, Math.round(ms));
    }

    /** Chroma profile correlation constants (Krumhansl-Schmuckler). */
    private static final float[] KS_MAJOR = {
            6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f
    };
    private static final float[] KS_MINOR = {
            6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f
    };

    /** Camelot wheel label for (root, major) — 1A..12B. */
    public static String camelotFor(int root, boolean major) {
        int[] majorMap = { 8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1 };   // C→8B
        int[] minorMap = { 5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10 };  // Cm→5A
        int num = major ? majorMap[root] : minorMap[root];
        return num + (major ? "B" : "A");
    }

    /** Pitch-class label for a root (C..B). */
    public static String rootLabel(int root) {
        String[] names = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };
        if (root < 0 || root >= names.length) return "";
        return names[root];
    }

    /** Key label e.g. "G major" / "E minor". */
    public static String keyLabel(int root, boolean major) {
        String r = rootLabel(root);
        return r.isEmpty() ? "" : r + (major ? " major" : " minor");
    }

    /**
     * Key detection — 12-bin chroma from a window of PCM, correlated against the
     * Krumhansl-Schmuckler profiles; returns the best (root, major) pair.
     *
     * Technique: 4096-pt FFT (~5.4 Hz bins) then PEAK-PICK the local maxima and map
     * each peak's frequency to its nearest semitone. Soft chroma (splitting a bin's
     * energy across two pitch classes) leaks C4 (261.6 Hz, which lands between bins
     * 48/49) into pitch class B; peak-picking maps each tone to exactly one pitch
     * class, so a C–E–G triad reads C major, not A minor / C minor. 2026-08-01
     */
    public static int[] detectKey(short[] pcm, int offset, int length) {
        int fftN = 4096;
        int frame = fftN;
        int hop = 2048;
        float[] win = new float[frame];
        for (int i = 0; i < frame; i++) win[i] = hann(i, frame);
        int nFrames = Math.max(0, (length - frame) / hop + 1);
        if (nFrames < 2) return new int[] { -1, 0 };
        float[] re = new float[fftN];
        float[] im = new float[fftN];
        double[] chroma = new double[12];
        for (int f = 0; f < nFrames; f++) {
            int start = offset + f * hop;
            Arrays.fill(re, 0f);
            Arrays.fill(im, 0f);
            for (int i = 0; i < frame && start + i < offset + length; i++) {
                re[i] = (pcm[start + i] / 32768f) * win[i];
            }
            fft(re, im);
            // Frame max gives a noise floor: a genuine tone peaks well above 6% of it.
            float frameMax = 0f;
            for (int k = 1; k < fftN / 2; k++) {
                float mag = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                if (mag > frameMax) frameMax = mag;
            }
            float floor = frameMax * 0.06f;
            for (int k = 2; k < fftN / 2 - 1; k++) {
                double freq = k * HZ / (double) fftN;
                if (freq < 60.0 || freq > 4000.0) continue;
                float m = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                if (m < floor) continue;
                float mp = (float) Math.sqrt(re[k - 1] * re[k - 1] + im[k - 1] * im[k - 1]);
                float mn = (float) Math.sqrt(re[k + 1] * re[k + 1] + im[k + 1] * im[k + 1]);
                if (m <= mp || m < mn) continue; // not a local max
                // Nearest semitone: C4's peak lands on bin 49 (~263.7 Hz → 60.15)
                // which rounds to pc 0; E4 bin 61 → pc 4; G4 bin 73 → pc 7.
                double midi = 69.0 + 12.0 * Math.log(freq / 440.0) / Math.log(2.0);
                int pc = ((int) Math.round(midi)) % 12;
                if (pc < 0) pc += 12;
                chroma[pc] += m;
            }
        }
        double norm = 0;
        for (int i = 0; i < 12; i++) norm += chroma[i] * chroma[i];
        if (norm < 1e-9) return new int[] { -1, 0 };
        norm = Math.sqrt(norm);
        float[] v = new float[12];
        for (int i = 0; i < 12; i++) v[i] = (float) (chroma[i] / norm);
        int bestRoot = -1;
        int bestMajor = 1; // default major; flipped to 0 when a minor profile wins
        double bestCorr = -1.0;
        for (int root = 0; root < 12; root++) {
            for (int m = 0; m < 2; m++) {
                float[] profile = m == 0 ? KS_MAJOR : KS_MINOR;
                double corr = 0, p2 = 0;
                for (int i = 0; i < 12; i++) {
                    corr += v[i] * profile[(i + 12 - root) % 12];
                    p2 += profile[i] * profile[i];
                }
                corr /= Math.sqrt(p2);
                if (corr > bestCorr) {
                    bestCorr = corr;
                    bestRoot = root;
                    // key[1] contract is 1=major / 0=minor; m==0 indexes KS_MAJOR.
                    // Was: stored m directly — a major win (m=0) was reported as minor. 2026-08-01
                    bestMajor = m == 0 ? 1 : 0;
                }
            }
        }
        return new int[] { bestRoot, bestMajor };
    }

    /** Full analysis pipeline over mono PCM; returns a populated Result. */
    public static Result analyze(short[] pcm, int offset, int length) {
        Result r = new Result();
        if (pcm == null || length < HZ * 4) return r;
        int safeLen = Math.min(length, HZ * 60); // 60 s cap for tempo stability
        float[] flux = onsetEnvelope(pcm, offset, safeLen);
        float[] conf = new float[1];
        float bpm = estimateBpm(flux, conf);
        r.bpm = bpm;
        r.confidence = conf[0];
        int phaseMs = estimatePhaseMs(flux, bpm, safeLen * 1000 / HZ);
        r.phaseMs = phaseMs;
        r.firstBeatMs = phaseMs;
        int[] key = detectKey(pcm, offset, safeLen);
        r.keyRoot = key[0];
        r.keyMajor = key[1] != 0;
        if (r.hasKey()) {
            r.keyLabel = keyLabel(r.keyRoot, r.keyMajor);
            r.camelot = camelotFor(r.keyRoot, r.keyMajor);
        }
        return r;
    }
}
