package com.solar.launcher.audio;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Host checks for TransportLayerPair (fake pads — no MediaPlayer / Looper).
 * Layman: prove load/pause/seek/mute/complete/scrub behave as one song.
 * 2026-07-20
 */
public class TransportLayerPairTest {

    private FakePad vocals;
    private FakePad instr;
    private TransportLayerPair pair;

    @Before
    public void setUp() {
        vocals = new FakePad();
        instr = new FakePad();
        // null Handler → sync posts for JVM unit tests. 2026-07-20
        pair = new TransportLayerPair(null, vocals, instr);
    }

    @After
    public void tearDown() {
        if (pair != null) pair.release();
    }

    /** Both pads ready after load. 2026-07-20 */
    @Test
    public void load_marksReadyWhenBothPadsPrepared() throws Exception {
        final AtomicBoolean ready = new AtomicBoolean(false);
        pair.setListener(new TransportLayerPair.Listener() {
            @Override
            public void onReady() {
                ready.set(true);
            }

            @Override
            public void onComplete() {}

            @Override
            public void onError(String message) {}
        });
        File v = File.createTempFile("vocals", ".mp3");
        File i = File.createTempFile("instr", ".mp3");
        try {
            pair.load(v, i);
            vocals.fireReady();
            instr.fireReady();
            assertTrue(ready.get());
            assertTrue(pair.isReady());
        } finally {
            v.delete();
            i.delete();
        }
    }

    /** Pause stops both pads. 2026-07-20 */
    @Test
    public void pause_stopsBothPads() throws Exception {
        prepareAndPlay();
        assertTrue(vocals.playing);
        assertTrue(instr.playing);
        pair.pause();
        assertFalse(vocals.playing);
        assertFalse(instr.playing);
        assertFalse(pair.isPlaying());
    }

    /** Seek syncs both pads to the same ms. 2026-07-20 */
    @Test
    public void seek_syncsBothPads() throws Exception {
        prepareAndPlay();
        pair.seekTo(12_345);
        assertEquals(12_345, vocals.positionMs);
        assertEquals(12_345, instr.positionMs);
        assertEquals(12_345, pair.getPositionMs());
    }

    /** Gain 0 mutes via pause; other layer stays audible lead. 2026-07-20 */
    @Test
    public void muteViaGain0_pausesSilentPad() throws Exception {
        prepareAndPlay();
        pair.setGain(TransportLayerPair.LAYER_VOCALS, 0f);
        assertFalse(vocals.playing);
        assertTrue(instr.playing);
        assertTrue(pair.isPlaying());
        assertEquals(180_000, pair.getDurationMs());
    }

    /** Complete fires once from audible lead only. 2026-07-20 */
    @Test
    public void complete_onceFromLead() throws Exception {
        final AtomicInteger completes = new AtomicInteger(0);
        prepareAndPlay();
        pair.setListener(new TransportLayerPair.Listener() {
            @Override
            public void onReady() {}

            @Override
            public void onComplete() {
                completes.incrementAndGet();
            }

            @Override
            public void onError(String message) {}
        });
        vocals.fireComplete();
        instr.fireComplete();
        assertEquals(1, completes.get());
    }

    /** A user seek must re-arm the completion latch so a later genuine completion still fires. 2026-08-01 */
    @Test
    public void seekTo_rearmsCompleteLatch() throws Exception {
        final AtomicInteger completes = new AtomicInteger(0);
        prepareAndPlay();
        pair.setListener(new TransportLayerPair.Listener() {
            @Override
            public void onReady() {}

            @Override
            public void onComplete() {
                completes.incrementAndGet();
            }

            @Override
            public void onError(String message) {}
        });
        vocals.fireComplete();
        instr.fireComplete();
        assertEquals(1, completes.get());
        // Scrub (seek) after the first completion — latch must reset.
        pair.seekTo(12_345);
        vocals.fireComplete();
        assertEquals(2, completes.get());
    }

    /** Scrub position/duration stay stable from lead. 2026-07-20 */
    @Test
    public void scrubParity_positionAndDurationFromLead() throws Exception {
        prepareAndPlay();
        vocals.positionMs = 5_000;
        vocals.durationMs = 180_000;
        instr.positionMs = 5_050;
        instr.durationMs = 179_000;
        assertEquals(5_000, pair.getPositionMs());
        assertEquals(180_000, pair.getDurationMs());
        pair.seekTo(9_000);
        assertEquals(9_000, pair.getPositionMs());
    }

    private void prepareAndPlay() throws Exception {
        final AtomicBoolean ready = new AtomicBoolean(false);
        pair.setListener(new TransportLayerPair.Listener() {
            @Override
            public void onReady() {
                ready.set(true);
            }

            @Override
            public void onComplete() {}

            @Override
            public void onError(String message) {}
        });
        File v = File.createTempFile("vocals", ".mp3");
        File i = File.createTempFile("instr", ".mp3");
        v.deleteOnExit();
        i.deleteOnExit();
        pair.load(v, i);
        vocals.durationMs = 180_000;
        instr.durationMs = 180_000;
        vocals.fireReady();
        instr.fireReady();
        assertTrue(ready.get());
        pair.play();
    }

    /**
     * In-memory pad for host tests (no Android MediaPlayer).
     * 2026-07-20
     */
    static final class FakePad implements TransportLayerPair.Pad {
        TransportLayerPair.PadListener listener;
        boolean playing;
        int positionMs;
        int durationMs = 180_000;
        float gain = 1f;
        boolean released;

        @Override
        public void setListener(TransportLayerPair.PadListener listener) {
            this.listener = listener;
        }

        @Override
        public void loadFile(File track) throws IOException {
            if (track == null || !track.isFile()) throw new IOException("missing");
            playing = false;
            positionMs = 0;
        }

        @Override
        public void start() {
            if (!released) playing = true;
        }

        @Override
        public void pause() {
            playing = false;
        }

        @Override
        public void seekTo(int ms) {
            positionMs = Math.max(0, ms);
        }

        @Override
        public void setGainImmediate(float g) {
            gain = g;
        }

        @Override
        public void fadeTo(float target, Runnable onDone) {
            gain = target;
            if (onDone != null) onDone.run();
        }

        @Override
        public float getGain() {
            return gain;
        }

        @Override
        public int getPositionMs() {
            return positionMs;
        }

        @Override
        public int getDurationMs() {
            return durationMs;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public void release() {
            released = true;
            playing = false;
        }

        void fireReady() {
            if (listener != null) listener.onReady(this);
        }

        void fireComplete() {
            if (listener != null) listener.onComplete(this);
        }
    }
}
