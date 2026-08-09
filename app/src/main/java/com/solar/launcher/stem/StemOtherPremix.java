package com.solar.launcher.stem;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Premix Melody/Other stem MP3s into one mono WAV for Y1-class CPUs.
 * Layman: blend piano/guitars/residual into one “other instruments” file so playback
 * only needs four players.
 * Technical: decode one MP3 at a time → 22.05 kHz mono PCM → additive mix → WAV;
 * reports MixProgress so UI is not stuck at a single 96%.
 * Was: N MediaPlayers on zone 3; silent mix with per-sample I/O. Reversal: skip premix;
 * unbuffered mixPcmFiles.
 * 2026-07-19
 */
public final class StemOtherPremix {
    public static final int OUT_HZ = 22050;
    public static final String MELODY_WAV = "melody.wav";
    /** ~6 minutes mono @ 22k — hard cap so mix cannot blow RAM/disk on a 4-minute pop song. */
    private static final int MAX_SAMPLES = OUT_HZ * 60 * 6;
    private static final int MIX_BUF_SAMPLES = 4096;

    /** Optional progress: within0to100 is local to this premix job. */
    public interface MixProgress {
        void onMixProgress(int within0to100, String detail);
    }

    private StemOtherPremix() {}

    /**
     * Mix sources into {@code outWav}. One source still goes through decode→WAV for a uniform format.
     * 2026-07-19
     */
    public static File mixToMonoWav(List<File> sources, File outWav, AtomicBoolean cancelled)
            throws Exception {
        return mixToMonoWav(sources, outWav, cancelled, null);
    }

    /**
     * Mix with progress — decode/blend/write each report a slice of 0–100.
     * 2026-07-19
     */
    public static File mixToMonoWav(List<File> sources, File outWav, AtomicBoolean cancelled,
            MixProgress progress) throws Exception {
        if (outWav == null) throw new IOException("melody out missing");
        List<File> ok = new ArrayList<File>();
        if (sources != null) {
            for (int i = 0; i < sources.size(); i++) {
                File f = sources.get(i);
                if (f != null && f.isFile() && f.length() >= 100) ok.add(f);
            }
        }
        if (ok.isEmpty()) throw new IOException("No Melody/Other stems to premix");

        File parent = outWav.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // Weights: decode ~80%, blend ~15%, wav write ~5%.
        final int n = ok.size();
        final int decodeSpan = 80;
        final int blendSpan = n > 1 ? 15 : 0;
        final int writeSpan = 100 - decodeSpan - blendSpan;

        File pcmA = new File(outWav.getAbsolutePath() + ".a.pcm");
        File pcmB = new File(outWav.getAbsolutePath() + ".b.pcm");
        try {
            report(progress, 0, "Decode 1/" + n + " · " + shortName(ok.get(0)));
            int samples = decodeMp3ToMonoPcm(ok.get(0), pcmA, cancelled, progress, 0, decodeSpan / n,
                    "Decode 1/" + n);
            for (int i = 1; i < ok.size(); i++) {
                throwIfCancelled(cancelled);
                int base = (decodeSpan * i) / n;
                int span = decodeSpan / n;
                if (i == ok.size() - 1) span = decodeSpan - base;
                report(progress, base, "Decode " + (i + 1) + "/" + n + " · " + shortName(ok.get(i)));
                int next = decodeMp3ToMonoPcm(ok.get(i), pcmB, cancelled, progress, base, span,
                        "Decode " + (i + 1) + "/" + n);
                int blendBase = decodeSpan + (blendSpan * (i - 1)) / Math.max(1, n - 1);
                int blendNext = decodeSpan + (blendSpan * i) / Math.max(1, n - 1);
                report(progress, blendBase, "Blend " + (i + 1) + "/" + n);
                samples = mixPcmFiles(pcmA, samples, pcmB, next, cancelled, progress,
                        blendBase, Math.max(1, blendNext - blendBase));
            }
            report(progress, decodeSpan + blendSpan, "Writing Melody WAV…");
            pcmToWav(pcmA, samples, outWav);
            report(progress, 100, "Melody ready");
            return outWav;
        } finally {
            if (pcmA.exists()) pcmA.delete();
            if (pcmB.exists()) pcmB.delete();
        }
    }

    private static void report(MixProgress progress, int pct, String detail) {
        if (progress == null) return;
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        try {
            progress.onMixProgress(pct, detail);
        } catch (Exception ignored) {}
    }

    private static String shortName(File f) {
        if (f == null) return "?";
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static void throwIfCancelled(AtomicBoolean cancelled) throws IOException {
        if (cancelled != null && cancelled.get()) throw new IOException("Cancelled");
    }

    /**
     * Decode MP3 → raw little-endian 16-bit mono @ OUT_HZ. Returns sample count.
     * Progress spans {@code basePct}..{@code basePct+spanPct} using duration when known.
     * 2026-07-19
     */
    static int decodeMp3ToMonoPcm(File mp3, File pcmOut, AtomicBoolean cancelled,
            MixProgress progress, int basePct, int spanPct, String label) throws Exception {
        return decodeMp3ToMonoPcm(mp3, pcmOut, cancelled, progress, basePct, spanPct,
                label, MAX_SAMPLES);
    }

    /**
     * Public windowed decode for stem analysis — stops after {@code maxSamples} mono
     * samples so a short tempo window costs seconds, not a full-track decode, on Y1-class
     * CPUs. Exposed across packages for {@code com.solar.launcher.stem.analysis}.
     * 2026-08-01
     */
    public static int decodeMp3ToMonoPcm(File mp3, File pcmOut, AtomicBoolean cancelled,
            int maxSamples) throws Exception {
        return decodeMp3ToMonoPcm(mp3, pcmOut, cancelled, null, 0, 0, "Decode", maxSamples);
    }

    static int decodeMp3ToMonoPcm(File mp3, File pcmOut, AtomicBoolean cancelled,
            MixProgress progress, int basePct, int spanPct, String label, int maxSamples)
            throws Exception {
        int cap = maxSamples > 0 ? maxSamples : MAX_SAMPLES;
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        FileOutputStream fos = null;
        try {
            ex.setDataSource(mp3.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0 || format == null) {
                throw new IOException("No audio in " + mp3.getName());
            }
            ex.selectTrack(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int inHz = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : OUT_HZ;
            int inCh = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            if (inHz <= 0) inHz = OUT_HZ;
            if (inCh <= 0) inCh = 1;
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0L;

            fos = new FileOutputStream(pcmOut);
            BufferedOutputStream bos = new BufferedOutputStream(fos, 16384);
            try {
            ByteBuffer[] inputs = codec.getInputBuffers();
            ByteBuffer[] outputs = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long inSample = 0;
            int written = 0;
            byte[] pair = new byte[2];
            long lastReportMs = 0L;
            int lastReportedPct = -1;

            while (!outputDone && written < cap) {
                throwIfCancelled(cancelled);
                if (!inputDone) {
                    int inIx = codec.dequeueInputBuffer(10_000);
                    if (inIx >= 0) {
                        ByteBuffer inBuf = inputs[inIx];
                        int sampleSize = ex.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIx, 0, sampleSize, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputs = codec.getOutputBuffers();
                } else if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        inHz = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        inCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else if (outIx >= 0) {
                    ByteBuffer outBuf = outputs[outIx];
                    if (info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        int frameBytes = 2 * inCh;
                        int frames = info.size / frameBytes;
                        for (int f = 0; f < frames && written < cap; f++) {
                            int sum = 0;
                            for (int c = 0; c < inCh; c++) sum += outBuf.getShort();
                            short mono = (short) (sum / inCh);
                            long outStart = (inSample * OUT_HZ) / inHz;
                            long outEnd = ((inSample + 1) * OUT_HZ) / inHz;
                            if (outEnd <= outStart) outEnd = outStart + 1;
                            while (written < outEnd && written < cap) {
                                pair[0] = (byte) (mono & 0xff);
                                pair[1] = (byte) ((mono >> 8) & 0xff);
                                bos.write(pair);
                                written++;
                            }
                            inSample++;
                        }
                        long now = System.currentTimeMillis();
                        int local = 0;
                        if (durationUs > 0 && info.presentationTimeUs > 0) {
                            local = (int) ((info.presentationTimeUs * 100L) / durationUs);
                            if (local > 99) local = 99;
                        } else if (cap > 0) {
                            local = (written * 100) / cap;
                        }
                        int mapped = basePct + (local * spanPct) / 100;
                        if (mapped != lastReportedPct && (now - lastReportMs >= 400
                                || mapped - lastReportedPct >= 2 || local >= 99)) {
                            lastReportMs = now;
                            lastReportedPct = mapped;
                            report(progress, mapped, label + " " + local + "%");
                        }
                    }
                    codec.releaseOutputBuffer(outIx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            bos.flush();
            report(progress, basePct + spanPct, label + " done");
            return written;
            } finally {
                try { bos.close(); } catch (Exception ignored) {}
                fos = null;
            }
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    /** Package-private overload used by older call sites / tests. */
    static int decodeMp3ToMonoPcm(File mp3, File pcmOut, AtomicBoolean cancelled)
            throws Exception {
        return decodeMp3ToMonoPcm(mp3, pcmOut, cancelled, null, 0, 0, "Decode");
    }

    /**
     * Additive mix of two PCM files into the first; buffered for MT6572.
     * Was: one short read/write per sample (very slow on eMMC). Reversal: unbuffered loop.
     * 2026-07-19
     */
    static int mixPcmFiles(File a, int aSamples, File b, int bSamples, AtomicBoolean cancelled,
            MixProgress progress, int basePct, int spanPct) throws Exception {
        int n = Math.max(aSamples, bSamples);
        if (n > MAX_SAMPLES) n = MAX_SAMPLES;
        File out = new File(a.getAbsolutePath() + ".mix");
        BufferedInputStream ina = new BufferedInputStream(new FileInputStream(a), 16384);
        BufferedInputStream inb = new BufferedInputStream(new FileInputStream(b), 16384);
        BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(out), 16384);
        try {
            byte[] ba = new byte[MIX_BUF_SAMPLES * 2];
            byte[] bb = new byte[MIX_BUF_SAMPLES * 2];
            byte[] bout = new byte[MIX_BUF_SAMPLES * 2];
            int done = 0;
            long lastReportMs = 0L;
            int lastPct = -1;
            while (done < n) {
                throwIfCancelled(cancelled);
                int chunk = Math.min(MIX_BUF_SAMPLES, n - done);
                int aWant = done < aSamples ? Math.min(chunk, aSamples - done) * 2 : 0;
                int bWant = done < bSamples ? Math.min(chunk, bSamples - done) * 2 : 0;
                int ra = readExact(ina, ba, aWant);
                int rb = readExact(inb, bb, bWant);
                for (int i = 0; i < chunk; i++) {
                    short sa = 0;
                    short sb = 0;
                    if (i * 2 + 1 < ra) {
                        sa = (short) ((ba[i * 2] & 0xff) | (ba[i * 2 + 1] << 8));
                    }
                    if (i * 2 + 1 < rb) {
                        sb = (short) ((bb[i * 2] & 0xff) | (bb[i * 2 + 1] << 8));
                    }
                    int sum = sa + sb;
                    if (sum > 32767) sum = 32767;
                    if (sum < -32768) sum = -32768;
                    bout[i * 2] = (byte) (sum & 0xff);
                    bout[i * 2 + 1] = (byte) ((sum >> 8) & 0xff);
                }
                fos.write(bout, 0, chunk * 2);
                done += chunk;
                long now = System.currentTimeMillis();
                int local = n > 0 ? (done * 100) / n : 100;
                int mapped = basePct + (local * spanPct) / 100;
                if (mapped != lastPct && (now - lastReportMs >= 400 || mapped - lastPct >= 2)) {
                    lastReportMs = now;
                    lastPct = mapped;
                    report(progress, mapped, "Blend " + local + "%");
                }
            }
            fos.flush();
        } finally {
            try { ina.close(); } catch (Exception ignored) {}
            try { inb.close(); } catch (Exception ignored) {}
            try { fos.close(); } catch (Exception ignored) {}
        }
        if (!a.delete()) { /* keep going */ }
        if (!out.renameTo(a)) {
            copyFile(out, a);
            out.delete();
        }
        return n;
    }

    static int mixPcmFiles(File a, int aSamples, File b, int bSamples, AtomicBoolean cancelled)
            throws Exception {
        return mixPcmFiles(a, aSamples, b, bSamples, cancelled, null, 0, 0);
    }

    /** Read exactly {@code want} bytes (or fewer at EOF). */
    private static int readExact(BufferedInputStream in, byte[] buf, int want) throws IOException {
        if (want <= 0) return 0;
        int got = 0;
        while (got < want) {
            int r = in.read(buf, got, want - got);
            if (r < 0) break;
            got += r;
        }
        return got;
    }

    private static void copyFile(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    static void pcmToWav(File pcm, int samples, File wav) throws IOException {
        int dataBytes = samples * 2;
        FileInputStream in = new FileInputStream(pcm);
        FileOutputStream out = new FileOutputStream(wav);
        try {
            ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            hdr.put("RIFF".getBytes("US-ASCII"));
            hdr.putInt(36 + dataBytes);
            hdr.put("WAVE".getBytes("US-ASCII"));
            hdr.put("fmt ".getBytes("US-ASCII"));
            hdr.putInt(16);
            hdr.putShort((short) 1);
            hdr.putShort((short) 1);
            hdr.putInt(OUT_HZ);
            hdr.putInt(OUT_HZ * 2);
            hdr.putShort((short) 2);
            hdr.putShort((short) 16);
            hdr.put("data".getBytes("US-ASCII"));
            hdr.putInt(dataBytes);
            out.write(hdr.array());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
    }
}
