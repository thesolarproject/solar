package com.solar.launcher.stem.analysis;

import android.content.Context;

import com.solar.launcher.stem.LalalClient;
import com.solar.launcher.stem.StemOtherPremix;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background stem analyser — decodes a short window of the drum stem (beats live there),
 * runs {@link StemAnalysisCore}, and hands the result back for caching / UI.
 * Layman: while stems are being prepared, quietly listen to the drums for a few seconds
 * and work out the real tempo, beat phase and key — no waiting on the first frame.
 * Technical: reuses {@link StemOtherPremix#decodeMp3ToMonoPcm} (MediaExtractor/MediaCodec
 * → 22.05 kHz mono) on a bounded window so analysis costs seconds, not minutes, on Y1-class
 * CPUs; falls back to the vocals stem when drums are missing. 2026-08-01
 */
public final class StemTrackAnalyzer {
    /** Analysis window — enough beats for a confident tempo estimate, cheap on MT6572. */
    public static final int WINDOW_MS = 60_000;
    private static final int WINDOW_SAMPLES = (StemAnalysisCore.HZ * WINDOW_MS) / 1000;

    private StemTrackAnalyzer() {}

    /**
     * Analyse a track from its stem files. Never throws for analysis failures — returns
     * null so callers fall back to the duration heuristic.
     */
    public static StemAnalysisCore.Result analyze(Context ctx, File track,
            List<LalalClient.StemFile> stems, AtomicBoolean cancelled) {
        File src = null;
        File vocals = null;
        if (stems != null && !stems.isEmpty()) {
            src = pickStem(stems, 1);
            vocals = pickStem(stems, 0);
            if (src == null) src = vocals;
        }
        if (src == null || !src.isFile()) src = track;
        if (src == null || !src.isFile()) return null;
        File pcm = null;
        try {
            pcm = File.createTempFile("stem_an", ".pcm", ctx.getCacheDir());
            int samples = StemOtherPremix.decodeMp3ToMonoPcm(src, pcm, cancelled, WINDOW_SAMPLES);
            if (samples < StemAnalysisCore.HZ * 4) return null;
            short[] buf = readPcm(pcm, samples);
            if (cancelled != null && cancelled.get()) return null;
            StemAnalysisCore.Result r = StemAnalysisCore.analyze(buf, 0, buf.length);
            // Prefer vocals for the key when drums are sparse (beat-only spectrum).
            if (vocals != null && vocals.isFile() && vocals != src) {
                try {
                    File vpcm = File.createTempFile("stem_key", ".pcm", ctx.getCacheDir());
                    int vsamples = StemOtherPremix.decodeMp3ToMonoPcm(
                            vocals, vpcm, cancelled, WINDOW_SAMPLES);
                    if (vsamples >= StemAnalysisCore.HZ * 4) {
                        short[] vbuf = readPcm(vpcm, vsamples);
                        int[] key = StemAnalysisCore.detectKey(vbuf, 0, vbuf.length);
                        if (key[0] >= 0) {
                            r.keyRoot = key[0];
                            r.keyMajor = key[1] != 0;
                            r.keyLabel = StemAnalysisCore.keyLabel(key[0], key[1] != 0);
                            r.camelot = StemAnalysisCore.camelotFor(key[0], key[1] != 0);
                        }
                    }
                    vpcm.delete();
                } catch (Exception ignored) {}
            }
            return r;
        } catch (Exception e) {
            return null;
        } finally {
            if (pcm != null) pcm.delete();
        }
    }

    /** First stem file on a zone, or null. */
    private static File pickStem(List<LalalClient.StemFile> stems, int zone) {
        if (stems == null) return null;
        for (int i = 0; i < stems.size(); i++) {
            LalalClient.StemFile s = stems.get(i);
            if (s != null && s.zone == zone && s.file != null && s.file.isFile()) {
                return s.file;
            }
        }
        return null;
    }

    /** Read the first {@code samples} little-endian shorts from a temp PCM file. */
    static short[] readPcm(File pcm, int samples) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(pcm);
        try {
            int want = Math.min(samples, WINDOW_SAMPLES);
            short[] buf = new short[want];
            byte[] bytes = new byte[want * 2];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) break;
                off += n;
            }
            int shorts = off / 2;
            for (int i = 0; i < shorts; i++) {
                buf[i] = (short) ((bytes[i * 2] & 0xff) | (bytes[i * 2 + 1] << 8));
            }
            if (shorts < buf.length) {
                short[] trimmed = new short[shorts];
                System.arraycopy(buf, 0, trimmed, 0, shorts);
                return trimmed;
            }
            return buf;
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }
}
