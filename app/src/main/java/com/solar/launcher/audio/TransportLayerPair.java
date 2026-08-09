package com.solar.launcher.audio;

import android.os.Handler;

import com.solar.launcher.stem.SoloLayerGains;
import com.solar.launcher.stem.StemControls;

import java.io.File;
import java.io.IOException;

/**
 * Vocals + instrumental pads for NP Instrumental/Acapella (inside SolarTransport).
 * Layman: two faders for voice and band; scrub/pause move both together.
 * Technical: lead-locked seek + drift; mute-via-pause gains; solar-audio Handler.
 * Was: SoloLayerMixer beside transport (dual engines). Reversal: restore SoloLayerMixer handoff.
 * 2026-07-20
 */
public final class TransportLayerPair {
    public static final int LAYER_VOCALS = 0;
    public static final int LAYER_INSTR = 1;
    private static final int LAYER_COUNT = 2;

    /**
     * Minimal pad API so host tests inject fakes without MediaPlayer.
     * Layman: one stem strip that can play, pause, seek, and fade.
     * 2026-07-20
     */
    public interface Pad {
        void setListener(PadListener listener);
        void loadFile(File track) throws IOException;
        void start();
        void pause();
        void seekTo(int ms);
        void setGainImmediate(float gain);
        void fadeTo(float target, Runnable onDone);
        float getGain();
        int getPositionMs();
        int getDurationMs();
        boolean isPlaying();
        void release();
    }

    /** Pad ready / complete / error callbacks. 2026-07-20 */
    public interface PadListener {
        void onReady(Pad pad);
        void onComplete(Pad pad);
        void onError(Pad pad, String message);
    }

    public interface Listener {
        void onReady();
        void onComplete();
        void onError(String message);
    }

    /** Null = run inline (host unit tests without Looper). 2026-07-20 */
    private final Handler audio;
    private final Pad vocals;
    private final Pad instr;
    private final float[] gains = new float[] {1f, 1f};
    private final float[] fadeTargets = new float[] {1f, 1f};
    private final boolean[] fading = new boolean[LAYER_COUNT];
    private final Runnable fadeTick = new Runnable() {
        @Override
        public void run() {
            tickFades();
        }
    };
    private final Runnable driftFix = new Runnable() {
        @Override
        public void run() {
            if (released || !started) return;
            try {
                Pad lead = audibleLead();
                if (lead == null || !lead.isPlaying()) {
                    postDelayed(this, 800);
                    return;
                }
                int pos = lead.getPositionMs();
                Pad other = lead == vocals ? instr : vocals;
                int otherLayer = lead == vocals ? LAYER_INSTR : LAYER_VOCALS;
                if (other != null && !StemControls.isGainSilent(gains[otherLayer])) {
                    int d = Math.abs(other.getPositionMs() - pos);
                    if (d > 50) {
                        other.seekTo(pos);
                        if (!other.isPlaying()) other.start();
                    }
                }
            } catch (Exception ignored) {}
            postDelayed(this, 800);
        }
    };
    private Listener listener;
    private boolean released;
    private boolean started;
    private int preparedCount;
    private int seekOnReadyMs = -1;
    private boolean playWhenReady;
    private boolean completeNotified;

    /**
     * Production pair: two real TransportDecks on the shared audio looper.
     * 2026-07-20
     */
    public TransportLayerPair(Handler audioLooperHandler) {
        this(audioLooperHandler,
                new DeckPad(new TransportDeck(audioLooperHandler)),
                new DeckPad(new TransportDeck(audioLooperHandler)));
    }

    /**
     * Inject pads (host unit tests use fakes; null Handler = sync).
     * 2026-07-20
     */
    public TransportLayerPair(Handler audioLooperHandler, Pad vocalsPad, Pad instrPad) {
        this.audio = audioLooperHandler;
        this.vocals = vocalsPad;
        this.instr = instrPad;
        wirePad(vocals, LAYER_VOCALS);
        wirePad(instr, LAYER_INSTR);
    }

    /** Post to audio looper, or run now when Handler is null (tests). 2026-07-20 */
    private void post(Runnable r) {
        if (audio != null) audio.post(r);
        else r.run();
    }

    /** Delayed post; no-op delay when Handler is null (tests skip drift). 2026-07-20 */
    private void postDelayed(Runnable r, long ms) {
        if (audio != null) audio.postDelayed(r, ms);
        // Sync tests: skip drift polling (no Looper). 2026-07-20
    }

    private void removeCallbacks(Runnable r) {
        if (audio != null) audio.removeCallbacks(r);
    }

    /** Attach ready/complete/error wiring for one pad. 2026-07-20 */
    private void wirePad(final Pad pad, final int layer) {
        pad.setListener(new PadListener() {
            @Override
            public void onReady(Pad p) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (released) return;
                        preparedCount++;
                        applyGain(layer);
                        if (preparedCount >= LAYER_COUNT) {
                            if (seekOnReadyMs >= 0) {
                                seekTo(seekOnReadyMs);
                                seekOnReadyMs = -1;
                            }
                            final Listener l = listener;
                            if (l != null) l.onReady();
                            if (playWhenReady) {
                                playWhenReady = false;
                                play();
                            }
                        }
                    }
                });
            }

            @Override
            public void onComplete(Pad p) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || !started || completeNotified) return;
                        if (p == audibleLead()) {
                            completeNotified = true;
                            final Listener l = listener;
                            if (l != null) l.onComplete();
                        }
                    }
                });
            }

            @Override
            public void onError(Pad p, String message) {
                final Listener l = listener;
                if (l != null) l.onError(message);
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Load acapella + instrumental (both required).
     * 2026-07-20
     */
    public void load(File vocalsFile, File instrumentalFile) throws IOException {
        releasePlayersOnly();
        released = false;
        started = false;
        completeNotified = false;
        preparedCount = 0;
        seekOnReadyMs = -1;
        playWhenReady = false;
        gains[LAYER_VOCALS] = 1f;
        gains[LAYER_INSTR] = 1f;
        fadeTargets[LAYER_VOCALS] = 1f;
        fadeTargets[LAYER_INSTR] = 1f;
        if (vocalsFile == null || !vocalsFile.isFile()
                || instrumentalFile == null || !instrumentalFile.isFile()) {
            throw new IOException("Need vocals + instrumental files");
        }
        vocals.loadFile(vocalsFile);
        instr.loadFile(instrumentalFile);
    }

    /** Start both layers (respecting silence). 2026-07-20 */
    public void play() {
        if (released || preparedCount < LAYER_COUNT) {
            playWhenReady = true;
            return;
        }
        started = true;
        completeNotified = false;
        startLayer(vocals, LAYER_VOCALS);
        startLayer(instr, LAYER_INSTR);
        removeCallbacks(driftFix);
        postDelayed(driftFix, 800);
    }

    private void startLayer(Pad pad, int layer) {
        if (pad == null) return;
        try {
            if (StemControls.isGainSilent(gains[layer])) {
                pad.pause();
                return;
            }
            pad.start();
        } catch (Exception ignored) {}
    }

    /** Pause both pads. 2026-07-20 */
    public void pause() {
        playWhenReady = false;
        try { vocals.pause(); } catch (Exception ignored) {}
        try { instr.pause(); } catch (Exception ignored) {}
    }

    /**
     * True when an audible layer is playing.
     * Layman: are the stems actually making sound?
     * 2026-07-20
     */
    public boolean isPlaying() {
        try {
            if (!StemControls.isGainSilent(gains[LAYER_VOCALS]) && vocals.isPlaying()) return true;
            if (!StemControls.isGainSilent(gains[LAYER_INSTR]) && instr.isPlaying()) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Lead pad position for scrub. 2026-07-20 */
    public int getPositionMs() {
        try {
            Pad lead = audibleLead();
            if (lead != null) return lead.getPositionMs();
        } catch (Exception ignored) {}
        return 0;
    }

    /** Lead (or first ready) duration for scrub. 2026-07-20 */
    public int getDurationMs() {
        try {
            Pad lead = audibleLead();
            if (lead != null) {
                int d = lead.getDurationMs();
                if (d > 0) return d;
            }
            int d = vocals.getDurationMs();
            if (d > 0) return d;
            return instr.getDurationMs();
        } catch (Exception ignored) {}
        return 0;
    }

    /** Seek both pads to the same ms (lead lock). 2026-07-20 */
    public void seekTo(int ms) {
        int safe = Math.max(0, ms);
        // A spurious completion may have latched the notify flag; a user seek restarts
        // the pair clock so a later genuine completion can still fire. 2026-08-01
        completeNotified = false;
        if (preparedCount < LAYER_COUNT) {
            seekOnReadyMs = safe;
            return;
        }
        try { vocals.seekTo(safe); } catch (Exception ignored) {}
        try { instr.seekTo(safe); } catch (Exception ignored) {}
    }

    public void setGain(int layer, float gain) {
        if (layer < 0 || layer >= LAYER_COUNT) return;
        fading[layer] = false;
        gains[layer] = StemControls.clampGain(gain);
        fadeTargets[layer] = gains[layer];
        applyGain(layer);
    }

    public float getGain(int layer) {
        if (layer < 0 || layer >= LAYER_COUNT) return 0f;
        return gains[layer];
    }

    /**
     * Smooth dial to target (Stem mute feel).
     * Layman: layer melts out / in instead of a hard cut.
     * 2026-07-20
     */
    public void fadeGain(int layer, float target) {
        if (layer < 0 || layer >= LAYER_COUNT || released) return;
        fadeTargets[layer] = StemControls.clampGain(target);
        if (SoloLayerGains.fadeDone(gains[layer], fadeTargets[layer])) {
            setGain(layer, fadeTargets[layer]);
            return;
        }
        fading[layer] = true;
        removeCallbacks(fadeTick);
        post(fadeTick);
    }

    private void tickFades() {
        if (released) return;
        // Host tests (null Handler): finish fades in one call. 2026-07-20
        if (audio == null) {
            for (int i = 0; i < LAYER_COUNT; i++) {
                if (!fading[i]) continue;
                while (!SoloLayerGains.fadeDone(gains[i], fadeTargets[i])) {
                    gains[i] = SoloLayerGains.stepToward(gains[i], fadeTargets[i]);
                    applyGain(i);
                }
                fading[i] = false;
                gains[i] = fadeTargets[i];
                applyGain(i);
            }
            return;
        }
        boolean any = false;
        for (int i = 0; i < LAYER_COUNT; i++) {
            if (!fading[i]) continue;
            float next = SoloLayerGains.stepToward(gains[i], fadeTargets[i]);
            gains[i] = next;
            applyGain(i);
            if (SoloLayerGains.fadeDone(next, fadeTargets[i])) {
                fading[i] = false;
                gains[i] = fadeTargets[i];
                applyGain(i);
            } else {
                any = true;
            }
        }
        if (any) {
            postDelayed(fadeTick, SoloLayerGains.FADE_TICK_MS);
        }
    }

    private void applyGain(int layer) {
        float g = gains[layer];
        Pad pad = layer == LAYER_VOCALS ? vocals : instr;
        if (pad == null) return;
        try {
            pad.setGainImmediate(g);
            if (StemControls.isGainSilent(g)) {
                pad.pause();
            } else if (started && !released && !pad.isPlaying()) {
                pad.seekTo(getPositionMs());
                pad.start();
            }
        } catch (Exception ignored) {}
    }

    public boolean isReady() {
        return !released && preparedCount >= LAYER_COUNT;
    }

    /** Tear down pads and timers. 2026-07-20 */
    public void release() {
        released = true;
        started = false;
        playWhenReady = false;
        removeCallbacks(fadeTick);
        removeCallbacks(driftFix);
        releasePlayersOnly();
        try { vocals.release(); } catch (Exception ignored) {}
        try { instr.release(); } catch (Exception ignored) {}
        listener = null;
    }

    private Pad audibleLead() {
        try {
            if (!StemControls.isGainSilent(gains[LAYER_VOCALS])) return vocals;
            if (!StemControls.isGainSilent(gains[LAYER_INSTR])) return instr;
            return vocals;
        } catch (Exception e) {
            return vocals;
        }
    }

    /**
     * Pause pads and clear ready count so {@link #load} can reload the same decks.
     * Layman: stop the strips without throwing them away.
     * Was: pad.release() here → TransportDeck terminal; second load stayed dead.
     * Reversal: call vocals.release()/instr.release() again.
     * 2026-07-20
     */
    private void releasePlayersOnly() {
        preparedCount = 0;
        try { vocals.pause(); } catch (Exception ignored) {}
        try { instr.pause(); } catch (Exception ignored) {}
    }

    /**
     * Wrap TransportDeck as a Pad (production).
     * Layman: real song strip behind the pair API.
     * 2026-07-20
     */
    static final class DeckPad implements Pad {
        private final TransportDeck deck;
        private PadListener listener;

        DeckPad(TransportDeck deck) {
            this.deck = deck;
            deck.setListener(new TransportDeck.Listener() {
                @Override
                public void onReady(TransportDeck d) {
                    if (listener != null) listener.onReady(DeckPad.this);
                }

                @Override
                public void onComplete(TransportDeck d) {
                    if (listener != null) listener.onComplete(DeckPad.this);
                }

                @Override
                public void onError(TransportDeck d, String message) {
                    if (listener != null) listener.onError(DeckPad.this, message);
                }
            });
        }

        @Override
        public void setListener(PadListener listener) {
            this.listener = listener;
        }

        @Override
        public void loadFile(File track) throws IOException {
            deck.loadFile(track, false);
        }

        @Override
        public void start() {
            deck.start();
        }

        @Override
        public void pause() {
            deck.pause();
        }

        @Override
        public void seekTo(int ms) {
            deck.seekTo(ms);
        }

        @Override
        public void setGainImmediate(float gain) {
            deck.setGainImmediate(gain);
        }

        @Override
        public void fadeTo(float target, Runnable onDone) {
            deck.fadeTo(target, onDone);
        }

        @Override
        public float getGain() {
            return deck.getGain();
        }

        @Override
        public int getPositionMs() {
            return deck.getPositionMs();
        }

        @Override
        public int getDurationMs() {
            return deck.getDurationMs();
        }

        @Override
        public boolean isPlaying() {
            return deck.isPlaying();
        }

        @Override
        public void release() {
            deck.release();
        }
    }
}
