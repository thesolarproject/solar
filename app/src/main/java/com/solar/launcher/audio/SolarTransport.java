package com.solar.launcher.audio;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.File;
import java.io.IOException;

/**
 * 2026-07-20 — Background dual-slot audio transport (prepare-ahead / gapless) off the UI thread.
 * Layman: next song warms up while this one plays so the join does not click; fades do not wait on menus.
 * Technical: HandlerThread solar-audio; slot A/B TransportDecks; setNextMediaPlayer when both stock MP.
 * Instrumental/Acapella dual pads live in {@link TransportLayerPair} here — ownsPlayback stays true.
 * Was: SoloLayerMixer beside transport + releaseOwnership. Reversal: restore SoloLayerMixer handoff.
 */
public final class SolarTransport {
    public interface Listener {
        void onPrepared(SolarTransport tx);
        void onCompletion(SolarTransport tx);
        void onError(SolarTransport tx, String message);
        /**
         * Play/pause intent settled on the audio looper — refresh NP/status chrome.
         * Layman: tell the menus the song really started or stopped.
         * Was: no callback; MainActivity polled before audio.post ran → stale Pause stamp.
         * Reversal: drop this method; restore playing&&active.isPlaying() only.
         * 2026-07-20
         */
        void onPlaybackStateChanged(SolarTransport tx);
    }

    private static SolarTransport instance;

    private final HandlerThread thread;
    private final Handler audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private TransportDeck slotA;
    private TransportDeck slotB;
    private TransportDeck active;
    private TransportDeck preparedNext;
    private volatile boolean nextSlotReady;
    private boolean released;
    /** Intent flag — main thread may read before audio.post runs. 2026-07-20 */
    private volatile boolean playing;
    private int pendingSeekMs = -1;
    /** True when SolarTransport owns NP decode (vs legacy MainActivity mediaPlayer). 2026-07-20 */
    private volatile boolean ownsPlayback;
    /** Dual Instrumental/Acapella pads (null = single-file mode). 2026-07-20 */
    private TransportLayerPair layerPair;
    private volatile boolean layerMode;

    /** Process-wide transport — one audio looper. 2026-07-20 */
    public static synchronized SolarTransport get() {
        if (instance == null || instance.released) {
            instance = new SolarTransport();
        }
        return instance;
    }

    private SolarTransport() {
        thread = new HandlerThread("solar-audio");
        thread.start();
        audio = new Handler(thread.getLooper());
        slotA = new TransportDeck(audio);
        slotB = new TransportDeck(audio);
        wireSlot(slotA);
        wireSlot(slotB);
        active = slotA;
    }

    /** Shared looper for Stem / Mix / layer fade ticks. 2026-07-20 */
    public Handler audioHandler() {
        return audio;
    }

    public TransportDeck getActiveDeck() {
        return active;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean ownsPlayback() {
        return ownsPlayback;
    }

    /** True when Instrumental/Acapella dual pads are the audible path. 2026-07-20 */
    public boolean isLayerMode() {
        return layerMode && layerPair != null && layerPair.isReady();
    }

    /** True when idle slot is prepared for gapless promote. 2026-07-20 */
    public boolean hasPreparedNext() {
        return nextSlotReady && preparedNext != null;
    }

    /**
     * UI audible intent while this transport owns decode (deck hardware can lag async start).
     * Layman: menus follow “we meant to play,” not a still-warming pad.
     * Was: playing && active.isPlaying() — false between resume() post and deck.start().
     * Reversal: return playing && active != null && active.isPlaying();
     * 2026-07-20
     */
    public boolean isPlaying() {
        return ownsPlayback && playing;
    }

    public int getPositionMs() {
        if (layerMode && layerPair != null && layerPair.isReady()) {
            return layerPair.getPositionMs();
        }
        return active != null ? active.getPositionMs() : 0;
    }

    public int getDurationMs() {
        if (layerMode && layerPair != null && layerPair.isReady()) {
            return layerPair.getDurationMs();
        }
        return active != null ? active.getDurationMs() : 0;
    }

    /**
     * Load and play a local file on the audio looper.
     * Sync owns/playing before post so NP ladder does not fall through to idle MediaPlayer. 2026-07-20
     */
    public void playFile(final File file, final int seekMs, final boolean preferIjk,
            final boolean autoStart) {
        // Intent first — UI can poll before the audio thread runs. 2026-07-20
        ownsPlayback = true;
        playing = autoStart;
        layerMode = false;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("file", file != null ? file.getName() : "null");
            d.put("autoStart", autoStart);
            d.put("seekMs", seekMs);
            d.put("activePlaying", active != null && active.isPlaying());
            d.put("idlePlaying", idleSlot() != null && idleSlot().isPlaying());
            d.put("hasPreparedNext", preparedNext != null);
            com.solar.launcher.Debug290fecLog.log("SolarTransport.playFile", "playFile intent", "H2,H4", d);
        } catch (Exception ignored) {}
        // #endregion
        audio.post(new Runnable() {
            @Override
            public void run() {
                ownsPlayback = true;
                playing = autoStart;
                try {
                    clearLayersLocked();
                    clearPreparedNextLocked();
                    active.loadFile(file, preferIjk);
                    pendingSeekMs = Math.max(0, seekMs);
                } catch (IOException e) {
                    playing = false;
                    notifyError(e.getMessage());
                }
            }
        });
    }

    /**
     * Load and play a stream URL via IJK.
     * Sync owns/playing before post (same race as playFile). 2026-07-20
     */
    public void playUrl(final String url, final int seekMs, final boolean autoStart) {
        ownsPlayback = true;
        playing = autoStart;
        layerMode = false;
        audio.post(new Runnable() {
            @Override
            public void run() {
                ownsPlayback = true;
                playing = autoStart;
                try {
                    clearLayersLocked();
                    clearPreparedNextLocked();
                    active.loadUrl(url);
                    pendingSeekMs = Math.max(0, seekMs);
                } catch (IOException e) {
                    playing = false;
                    notifyError(e.getMessage());
                }
            }
        });
    }

    /**
     * Enter or reload Instrumental+Acapella pads; ownsPlayback stays true.
     * Layman: peel voice/band without handing speakers to a second mixer.
     * Mid-play: sibling stem → hard-swap; full original → mute then raise pads.
     * Was: SoloLayerMixer + releaseOwnership; then overlap crossfade (doubled sibling).
     * Reversal: that handoff / always-overlap path.
     * 2026-07-20 / 2026-07-21
     */
    public void playLayers(final File vocals, final File instrumental,
            final float vGain, final float iGain, final int seekMs, final boolean autoStart) {
        ownsPlayback = true;
        playing = autoStart;
        layerMode = true;
        audio.post(new Runnable() {
            @Override
            public void run() {
                ownsPlayback = true;
                playing = autoStart;
                try {
                    enterLayersLocked(vocals, instrumental, vGain, iGain, seekMs, autoStart);
                } catch (IOException e) {
                    layerMode = false;
                    playing = false;
                    notifyError(e.getMessage());
                }
            }
        });
        notifyPlaybackStateChanged();
    }

    /**
     * Fade layer gains while already in dual-pad mode (Vocals/Instrumentals toggles).
     * 2026-07-20
     */
    public void fadeLayerGains(final float vGain, final float iGain) {
        audio.post(new Runnable() {
            @Override
            public void run() {
                if (layerPair == null || !layerPair.isReady()) return;
                layerPair.fadeGain(TransportLayerPair.LAYER_VOCALS, vGain);
                layerPair.fadeGain(TransportLayerPair.LAYER_INSTR, iGain);
            }
        });
    }

    /** Set layer gains immediately (no fade). 2026-07-20 */
    public void setLayerGains(final float vGain, final float iGain) {
        audio.post(new Runnable() {
            @Override
            public void run() {
                if (layerPair == null || !layerPair.isReady()) return;
                layerPair.setGain(TransportLayerPair.LAYER_VOCALS, vGain);
                layerPair.setGain(TransportLayerPair.LAYER_INSTR, iGain);
            }
        });
    }

    /**
     * Drop dual pads; keep A/B ownership and prepared-next warm.
     * Layman: leave Instrumental/Acapella mode without killing the next-song warmup.
     * 2026-07-20
     */
    public void clearLayers() {
        layerMode = false;
        audio.post(new Runnable() {
            @Override
            public void run() {
                clearLayersLocked();
                notifyPlaybackStateChanged();
            }
        });
    }

    /**
     * Warm the idle slot with the next queue item (gapless prepare-ahead).
     * Works while layered — next promote is single-file after clearLayers. 2026-07-20
     */
    public void prepareNextFile(final File file, final boolean preferIjk) {
        audio.post(new Runnable() {
            @Override
            public void run() {
                if (file == null || !file.isFile()) return;
                TransportDeck next = idleSlot();
                try {
                    preparedNext = next;
                    nextSlotReady = false;
                    next.loadFile(file, preferIjk);
                } catch (IOException e) {
                    preparedNext = null;
                    nextSlotReady = false;
                }
            }
        });
    }

    public void prepareNextUrl(final String url) {
        audio.post(new Runnable() {
            @Override
            public void run() {
                if (url == null || url.length() == 0) return;
                TransportDeck next = idleSlot();
                try {
                    preparedNext = next;
                    nextSlotReady = false;
                    next.loadUrl(url);
                } catch (IOException e) {
                    preparedNext = null;
                    nextSlotReady = false;
                }
            }
        });
    }

    /**
     * Pause active deck or layer pair — set intent synchronously so status/NP update immediately.
     * Was: playing=false only inside audio.post → updatePlayerUI saw stale “playing”.
     * Reversal: move playing=false into the posted runnable only.
     * 2026-07-20
     */
    public void pause() {
        playing = false;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("layerMode", layerMode);
            d.put("activePlaying", active != null && active.isPlaying());
            d.put("idlePlaying", idleSlot() != null && idleSlot().isPlaying());
            d.put("hasPreparedNext", preparedNext != null);
            com.solar.launcher.Debug290fecLog.log("SolarTransport.pause", "pause intent", "H1,H2,H3", d);
        } catch (Exception ignored) {}
        // #endregion
        audio.post(new Runnable() {
            @Override
            public void run() {
                playing = false;
                if (layerMode && layerPair != null) {
                    layerPair.pause();
                } else if (active != null) {
                    active.pause();
                }
                // #region agent log
                try {
                    org.json.JSONObject d2 = new org.json.JSONObject();
                    d2.put("activePlayingAfter", active != null && active.isPlaying());
                    d2.put("idlePlayingAfter", idleSlot() != null && idleSlot().isPlaying());
                    com.solar.launcher.Debug290fecLog.log("SolarTransport.pause", "pause after", "H2", d2);
                } catch (Exception ignored) {}
                // #endregion
                notifyPlaybackStateChanged();
            }
        });
    }

    /**
     * Resume active deck or layer pair — intent first, then start on audio looper.
     * Was: playing=true only inside post → Pause overlay stuck on after Center play.
     * Reversal: move playing=true into the posted runnable only.
     * 2026-07-20
     */
    public void resume() {
        playing = true;
        audio.post(new Runnable() {
            @Override
            public void run() {
                playing = true;
                if (layerMode && layerPair != null) {
                    layerPair.play();
                } else if (active != null) {
                    active.start();
                }
                notifyPlaybackStateChanged();
            }
        });
    }

    public void seekTo(final int ms) {
        audio.post(new Runnable() {
            @Override
            public void run() {
                if (layerMode && layerPair != null) {
                    layerPair.seekTo(ms);
                } else if (active != null) {
                    active.seekTo(ms);
                }
            }
        });
    }

    /** Fade active single-deck gain (mid-play crossfade helper). 2026-07-20 */
    public void fadeActiveGain(final float target, final Runnable onDone) {
        audio.post(new Runnable() {
            @Override
            public void run() {
                if (active == null) {
                    if (onDone != null) audio.post(onDone);
                    return;
                }
                active.fadeTo(target, onDone);
            }
        });
    }

    public void setGain(final float gain) {
        audio.post(new Runnable() {
            @Override
            public void run() {
                if (active != null) active.setGainImmediate(gain);
            }
        });
    }

    public void setSpeed(final float speed) {
        audio.post(new Runnable() {
            @Override
            public void run() {
                if (active != null) active.setSpeed(speed);
                if (preparedNext != null) preparedNext.setSpeed(speed);
            }
        });
    }

    /** Promote prepared next slot to active (gapless handoff). 2026-07-20 */
    public void promotePreparedNext() {
        audio.post(new Runnable() {
            @Override
            public void run() {
                clearLayersLocked();
                promoteNextLocked();
            }
        });
    }

    /** Stop and drop ownership — sync flags so UI leaves transport ladder immediately. 2026-07-20 */
    public void stop() {
        playing = false;
        ownsPlayback = false;
        layerMode = false;
        audio.post(new Runnable() {
            @Override
            public void run() {
                playing = false;
                ownsPlayback = false;
                clearLayersLocked();
                clearPreparedNextLocked();
                if (active != null) active.pause();
                notifyPlaybackStateChanged();
            }
        });
    }

    /**
     * @deprecated Layers stay under transport — do not yield ownership for Solo.
     * Kept for legacy IJK/EQ callers; no-op while layered.
     * Was: drop ownsPlayback for SoloLayerMixer. Reversal: restore ownsPlayback=false always.
     * 2026-07-20
     */
    public void releaseOwnership() {
        // Layman: do not give speakers away when stems are the song.
        if (layerMode) return;
        ownsPlayback = false;
        playing = false;
        audio.post(new Runnable() {
            @Override
            public void run() {
                if (layerMode) return;
                ownsPlayback = false;
                playing = false;
                if (active != null) {
                    try { active.pause(); } catch (Exception ignored) {}
                    active.setGainImmediate(1f);
                }
                notifyPlaybackStateChanged();
            }
        });
    }

    public synchronized void shutdown() {
        released = true;
        layerMode = false;
        audio.post(new Runnable() {
            @Override
            public void run() {
                ownsPlayback = false;
                clearLayersLocked();
                clearPreparedNextLocked();
                if (slotA != null) slotA.release();
                if (slotB != null) slotB.release();
                slotA = null;
                slotB = null;
                active = null;
            }
        });
        thread.quit();
        synchronized (SolarTransport.class) {
            if (instance == this) instance = null;
        }
    }

    /**
     * Load dual pads; hard-swap from stem sibling, or mute original then raise pads.
     * Layman: leave acapella/band or full song without hearing two copies.
     * Technical: sibling path → pause active + pads at target; original → fade active out then pads up.
     * Was: always overlap fade (pads up + active down) → doubled stem when active was sibling.
     * Reversal: restore always-overlap crossfadeFromSingle branch.
     * 2026-07-20 / 2026-07-21
     */
    private void enterLayersLocked(File vocalsFile, File instrumentalFile,
            final float vGain, final float iGain, int seekMs, final boolean autoStart)
            throws IOException {
        // Already layered — just retarget gains. 2026-07-20
        if (layerPair != null && layerPair.isReady()) {
            layerMode = true;
            if (autoStart) {
                layerPair.fadeGain(TransportLayerPair.LAYER_VOCALS, vGain);
                layerPair.fadeGain(TransportLayerPair.LAYER_INSTR, iGain);
                if (!layerPair.isPlaying() && playing) layerPair.play();
            } else {
                layerPair.setGain(TransportLayerPair.LAYER_VOCALS, vGain);
                layerPair.setGain(TransportLayerPair.LAYER_INSTR, iGain);
            }
            notifyPlaybackStateChanged();
            return;
        }
        final boolean crossfadeFromSingle = playing && active != null && !layerMode;
        // Active already playing vocals/instr file → hard-swap (no second copy). 2026-07-21
        final boolean activeIsStemSibling = crossfadeFromSingle && active != null
                && com.solar.launcher.stem.SoloLayerGains.isActiveStemSibling(
                        active.getPath(), vocalsFile, instrumentalFile);
        if (layerPair != null) {
            try { layerPair.release(); } catch (Exception ignored) {}
            layerPair = null;
        }
        layerPair = new TransportLayerPair(audio);
        layerMode = true;
        final int seek = Math.max(0, seekMs);
        layerPair.setListener(new TransportLayerPair.Listener() {
            @Override
            public void onReady() {
                if (released || layerPair == null) return;
                layerPair.seekTo(seek);
                if (crossfadeFromSingle && autoStart) {
                    if (activeIsStemSibling) {
                        // Sibling hard-swap: kill single stem now; pads own audio. 2026-07-21
                        pauseActiveDeckResetGain();
                        layerPair.setGain(TransportLayerPair.LAYER_VOCALS, vGain);
                        layerPair.setGain(TransportLayerPair.LAYER_INSTR, iGain);
                        playing = true;
                        layerPair.play();
                        notifyPlaybackStateChanged();
                    } else {
                        // Full original: silence single deck first, then raise pads. 2026-07-21
                        // Was: pads+active overlap fade → original under stems briefly.
                        layerPair.setGain(TransportLayerPair.LAYER_VOCALS, 0f);
                        layerPair.setGain(TransportLayerPair.LAYER_INSTR, 0f);
                        playing = true;
                        if (active != null) {
                            final TransportLayerPair pads = layerPair;
                            active.fadeTo(0f, new Runnable() {
                                @Override
                                public void run() {
                                    pauseActiveDeckResetGain();
                                    if (released || pads == null || pads != layerPair) return;
                                    pads.play();
                                    pads.fadeGain(TransportLayerPair.LAYER_VOCALS, vGain);
                                    pads.fadeGain(TransportLayerPair.LAYER_INSTR, iGain);
                                    notifyPlaybackStateChanged();
                                }
                            });
                        } else {
                            layerPair.play();
                            layerPair.fadeGain(TransportLayerPair.LAYER_VOCALS, vGain);
                            layerPair.fadeGain(TransportLayerPair.LAYER_INSTR, iGain);
                            notifyPlaybackStateChanged();
                        }
                    }
                } else {
                    layerPair.setGain(TransportLayerPair.LAYER_VOCALS, vGain);
                    layerPair.setGain(TransportLayerPair.LAYER_INSTR, iGain);
                    pauseActiveDeckResetGain();
                    if (autoStart) {
                        playing = true;
                        layerPair.play();
                    }
                    notifyPlaybackStateChanged();
                }
                notifyPrepared();
            }

            @Override
            public void onComplete() {
                if (released) return;
                // Layer song ended — MainActivity clears layers + promotes next. 2026-07-20
                notifyComplete();
            }

            @Override
            public void onError(String message) {
                notifyError(message);
            }
        });
        layerPair.load(vocalsFile, instrumentalFile);
    }

    /**
     * Stop the single A/B deck and restore unity gain for later reuse.
     * Layman: shut the one-file player so dual pads can take over cleanly.
     * 2026-07-21
     */
    private void pauseActiveDeckResetGain() {
        if (active == null) return;
        try { active.pause(); } catch (Exception ignored) {}
        try { active.setGainImmediate(1f); } catch (Exception ignored) {}
    }

    private void clearLayersLocked() {
        layerMode = false;
        if (layerPair != null) {
            try { layerPair.release(); } catch (Exception ignored) {}
            layerPair = null;
        }
    }

    private void wireSlot(final TransportDeck deck) {
        deck.setListener(new TransportDeck.Listener() {
            @Override
            public void onReady(TransportDeck d) {
                if (released) return;
                if (d == preparedNext) {
                    nextSlotReady = true;
                    boolean attached = false;
                    if (active != null) attached = active.attachNextMediaPlayer(d);
                    // #region agent log
                    try {
                        org.json.JSONObject d0 = new org.json.JSONObject();
                        d0.put("role", "preparedNext");
                        d0.put("attached", attached);
                        d0.put("path", d.getPath() != null ? d.getPath().getName() : "null");
                        d0.put("activePlaying", active != null && active.isPlaying());
                        d0.put("nextPlaying", d.isPlaying());
                        com.solar.launcher.Debug290fecLog.log("SolarTransport.onReady",
                                "next slot ready", "H2,H5", d0);
                    } catch (Exception ignored) {}
                    // #endregion
                    return;
                }
                if (d != active) return;
                if (layerMode) return;
                int seek = pendingSeekMs;
                pendingSeekMs = -1;
                if (seek > 0) d.seekTo(seek);
                // #region agent log
                try {
                    org.json.JSONObject d1 = new org.json.JSONObject();
                    d1.put("role", "active");
                    d1.put("willStart", playing);
                    d1.put("path", d.getPath() != null ? d.getPath().getName() : "null");
                    d1.put("idlePlaying", idleSlot() != null && idleSlot().isPlaying());
                    com.solar.launcher.Debug290fecLog.log("SolarTransport.onReady",
                            "active ready", "H2,H4,H5", d1);
                } catch (Exception ignored) {}
                // #endregion
                if (playing) d.start();
                notifyPrepared();
            }

            @Override
            public void onComplete(TransportDeck d) {
                if (released) return;
                if (layerMode) return;
                if (d != active) return;
                // 2026-07-20 — Always hand completion to MainActivity (queue index + promote).
                // Layman: song ended — let the playlist brain move the “now” pointer, then swap pads.
                // Technical: silent promoteNextLocked desynced audio from queue/NP metadata.
                // Was: auto-promote here + notifyPrepared only → NP showed previous track.
                // Reversal: if (preparedNext != null && preparedNext.isPrepared()) { promoteNextLocked(); return; }
                notifyComplete();
            }

            @Override
            public void onError(TransportDeck d, String message) {
                if (d == active && !layerMode) {
                    // Do not leave the process-wide transport ladder claiming a failed
                    // origin. Prepared-next failures must not disturb the active song.
                    playing = false;
                    ownsPlayback = false;
                    notifyPlaybackStateChanged();
                    notifyError(message);
                }
            }
        });
    }

    private TransportDeck idleSlot() {
        return active == slotA ? slotB : slotA;
    }

    private void promoteNextLocked() {
        if (preparedNext == null || !nextSlotReady) return;
        TransportDeck next = preparedNext;
        preparedNext = null;
        nextSlotReady = false;
        TransportDeck prev = active;
        active = next;
        if (prev != null) {
            try { prev.pause(); } catch (Exception ignored) {}
            try { prev.detachNext(); } catch (Exception ignored) {}
        }
        // Claim speakers after promote so NP ladder follows this deck. 2026-07-20
        ownsPlayback = true;
        playing = true;
        layerMode = false;
        if (!next.isPlaying()) next.start();
        notifyPrepared();
    }

    private void clearPreparedNextLocked() {
        // #region agent log
        try {
            TransportDeck idle = idleSlot();
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("hadPreparedNext", preparedNext != null);
            d.put("idlePlaying", idle != null && idle.isPlaying());
            d.put("idlePath", idle != null && idle.getPath() != null ? idle.getPath().getName() : "null");
            d.put("idleDeckId", idle != null ? System.identityHashCode(idle) : 0);
            com.solar.launcher.Debug290fecLog.log("SolarTransport.clearPreparedNext",
                    "clear next ptr only", "H2", d);
        } catch (Exception ignored) {}
        // #endregion
        preparedNext = null;
        nextSlotReady = false;
    }

    private void notifyPrepared() {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                l.onPrepared(SolarTransport.this);
            }
        });
    }

    private void notifyComplete() {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                l.onCompletion(SolarTransport.this);
            }
        });
    }

    private void notifyError(final String message) {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                l.onError(SolarTransport.this, message);
            }
        });
    }

    /** Main-thread play/pause settle notify for NP/status/library glyphs. 2026-07-20 */
    private void notifyPlaybackStateChanged() {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                l.onPlaybackStateChanged(SolarTransport.this);
            }
        });
    }
}
