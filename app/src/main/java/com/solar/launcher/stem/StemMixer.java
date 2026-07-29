package com.solar.launcher.stem;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.SystemClock;

import com.solar.launcher.audio.SolarTransport;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Synced MediaPlayers for Stem Player + NP Stems master (origin or 4-pad mix).
 * Layman: Stem Player pads, or Now Playing original file vs reconstituted stems.
 * Technical: ORIGIN = one MediaPlayer; PADS = loopCtrl + beat roll + optional bass_body;
 * magical swap = matched seek + SoloLayerGains-timed crossfade. Was: pads-only.
 * Reversal: drop origin/swap APIs; NP back on SolarTransport layers / ensureSolo.
 * 2026-07-19 / 2026-07-20 / 2026-07-21
 */
public final class StemMixer {
    public static final int STEM_COUNT = 4;
    private static final int DEFAULT_MS_PER_BAR = 2000;
    /** Default beat-roll slice when hold-stem fires (~1/8 bar @ 120). */
    public static final int DEFAULT_STUTTER_MS = 250;
    /** Floor for roll seeks — sub-80ms seek storms crash OMX on Y1 (API 17). 2026-07-19 */
    public static final int MIN_STUTTER_MS = 80;

    /** NP dual-source: original file vs four stem pads. 2026-07-21 */
    public enum SourceMode {
        ORIGIN,
        PADS
    }

    public interface Listener {
        void onReady();
        void onError(String message);
        void onComplete();
    }

    /** Fired when origin↔pads crossfade finishes. 2026-07-21 */
    public interface SwapListener {
        void onSwapComplete(SourceMode mode);
    }

    private final Context app;
    // Was: new Handler(Looper.getMainLooper()). Reversal: restore main-looper Handler. 2026-07-20
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private MediaPlayer[] players = new MediaPlayer[0];
    private int[] playerZones = new int[0];
    private int playerCount;
    private MediaPlayer bassBodyPlayer;
    /** Single-file origin feed for NP Stems-off (or mid-swap). 2026-07-21 */
    private MediaPlayer originPlayer;
    private tv.danmaku.ijk.media.player.IjkMediaPlayer originIjkPlayer;
    private String originPath;
    private float originGain = 1f;
    private float masterGain = 1f;
    private SourceMode sourceMode = SourceMode.PADS;
    private boolean swapBusy;
    private SwapListener swapListener;
    private final float[] gains = new float[] { 0f, 0f, 0f, 0f };
    private final boolean[] loopCtrl = new boolean[STEM_COUNT];
    private Listener listener;
    private int preparedCount;
    private int expectedPrepare;
    private boolean started;
    private boolean autoStartPending;
    private boolean released;
    private boolean looping;
    private int loopStartMs;
    private int loopEndMs;
    private float loopBars = StemControls.DEFAULT_LOOP_BARS;
    private int msPerBar = DEFAULT_MS_PER_BAR;
    private float bpm = StemBpm.DEFAULT_BPM;
    /** Cross-song tempo rate (Song1=1). Applied via IJK SoundTouch when ≠1. 2026-07-19 */
    private float targetRate = 1f;
    /** Paths for IJK reload when rate needs SoundTouch. */
    private String[] playerPaths = new String[0];
    private String bassBodyPath;
    private tv.danmaku.ijk.media.player.IjkMediaPlayer[] ijkPlayers;
    private tv.danmaku.ijk.media.player.IjkMediaPlayer ijkBassBody;
    private boolean usingIjk;

    /**
     * Fire song-end once per playthrough (any audible stem may signal).
     * Layman: when this track finishes, tell the jam once — even if Vocals is muted.
     * Was: only zone 0 completion. Reversal: zone==0 gate only.
     * 2026-07-21
     */
    private boolean songCompleteFired;

    /** Hold-stem beat roll — one zone at a time (fields keep stutter* names). 2026-07-20 */
    private int stutterZone = -1;
    private int stutterSliceMs;
    private int stutterAnchorMs;
    /** Wall clock when roll started — catch-up uses elapsedRealtime delta. 2026-07-20 */
    private long rollOriginElapsedRealtime;
    /** Snapped playhead at roll start — virtual timeline origin. 2026-07-20 */
    private int rollOriginPosMs;

    private final Runnable driftFix = new Runnable() {
        @Override
        public void run() {
            if (released || !started) return;
            try {
                MediaPlayer lead = leadPlayer();
                if (lead == null || !lead.isPlaying()) {
                    main.postDelayed(this, 3000);
                    return;
                }
                int pos = lead.getCurrentPosition();
                for (int i = 0; i < playerCount; i++) {
                    MediaPlayer p = players[i];
                    if (p == null || p == lead) continue;
                    int z = playerZones[i];
                    // Don't yank free-running or stuttering pads into lead while looping.
                    if (looping && z >= 0 && z < STEM_COUNT && !loopCtrl[z]) continue;
                    if (z == stutterZone) continue;
                    try {
                        // Muted pads stay paused — setVolume(0) leaks; don't revive them. 2026-07-19
                        if (z >= 0 && z < STEM_COUNT && StemControls.isGainSilent(gains[z])) {
                            continue;
                        }
                        if (!p.isPlaying()) {
                            int pd = p.getDuration();
                            if (pd > 0 && pos >= pd - 80) continue;
                        }
                        int d = Math.abs(p.getCurrentPosition() - pos);
                        if (d > 350) {
                            p.seekTo(pos);
                            if (!p.isPlaying() && started && !released) p.start();
                        }
                    } catch (Exception ignored) {}
                }
                syncBassBodyToLead(pos);
            } catch (Exception ignored) {}
            main.postDelayed(this, 3000);
        }
    };

    private int loopTickSample;
    private final Runnable loopTick = new Runnable() {
        @Override
        public void run() {
            if (released || !looping || !started) return;
            // #region agent log
            long tLoop0 = android.os.SystemClock.uptimeMillis();
            // #endregion
            try {
                for (int i = 0; i < playerCount; i++) {
                    int z = playerZones[i];
                    if (!shouldSeekZoneOnLoopWrap(true, z >= 0 && z < STEM_COUNT && loopCtrl[z],
                            z == stutterZone)) {
                        continue;
                    }
                    try {
                        if (usingIjk && ijkPlayers != null && ijkPlayers[i] != null) {
                            tv.danmaku.ijk.media.player.IjkMediaPlayer ip = ijkPlayers[i];
                            if (!ip.isPlaying()) continue;
                            if ((int) ip.getCurrentPosition() >= loopEndMs - 20) {
                                ip.seekTo(loopStartMs);
                            }
                        } else {
                            MediaPlayer p = players[i];
                            if (p == null || !p.isPlaying()) continue;
                            if (p.getCurrentPosition() >= loopEndMs - 20) {
                                p.seekTo(loopStartMs);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (loopCtrl[2] && stutterZone != 2) {
                    try {
                        if (usingIjk && ijkBassBody != null && ijkBassBody.isPlaying()
                                && (int) ijkBassBody.getCurrentPosition() >= loopEndMs - 20) {
                            ijkBassBody.seekTo(loopStartMs);
                        } else if (bassBodyPlayer != null && bassBodyPlayer.isPlaying()
                                && bassBodyPlayer.getCurrentPosition() >= loopEndMs - 20) {
                            bassBodyPlayer.seekTo(loopStartMs);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            // #region agent log
            loopTickSample++;
            if (loopTickSample == 1 || loopTickSample % 50 == 0) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("players", playerCount);
                    d.put("costMs", android.os.SystemClock.uptimeMillis() - tLoop0);
                    d.put("n", loopTickSample);
                    com.solar.launcher.Debug8b0481Log.log(
                            "StemMixer.loopTick", "loopTick sample", "H2", d);
                } catch (Exception ignored) {}
            }
            // #endregion
            main.postDelayed(this, 40);
        }
    };

    private final Runnable stutterTick = new Runnable() {
        @Override
        public void run() {
            if (released || stutterZone < 0 || !started) return;
            try {
                seekZone(stutterZone, stutterAnchorMs);
            } catch (Exception e) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("zone", stutterZone);
                    d.put("err", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                    com.solar.launcher.Debug543e15Log.log(
                            "StemMixer.stutterTick", "seek failed — stop roll", "F1", d);
                } catch (Exception ignored) {}
                // #endregion
                clearStutterInternal();
                return;
            }
            stutterTickCount++;
            // #region agent log
            if (stutterTickCount == 1 || stutterTickCount % 25 == 0) {
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("zone", stutterZone);
                    d.put("sliceMs", stutterSliceMs);
                    d.put("ticks", stutterTickCount);
                    com.solar.launcher.Debug543e15Log.log(
                            "StemMixer.stutterTick", "beat-roll tick", "F1", d);
                } catch (Exception ignored) {}
            }
            // #endregion
            main.postDelayed(this, Math.max(MIN_STUTTER_MS, stutterSliceMs));
        }
    };

    private int stutterTickCount;

    public StemMixer(Context context) {
        this.app = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Optional callback when magical origin↔pads swap lands. 2026-07-21 */
    public void setSwapListener(SwapListener listener) {
        this.swapListener = listener;
    }

    /** ORIGIN = full song file; PADS = four stem zones. 2026-07-21 */
    public SourceMode getSourceMode() {
        return sourceMode;
    }

    public boolean isOriginMode() {
        return sourceMode == SourceMode.ORIGIN;
    }

    public boolean isPadMode() {
        return sourceMode == SourceMode.PADS;
    }

    public boolean isSwapBusy() {
        return swapBusy;
    }

    /**
     * Load the original track as a single feed (NP Stems off).
     * Layman: play the song file itself, not the stem pads.
     * Technical: one MediaPlayer; clears pad players. Was: pads-only load.
     * Reversal: remove; keep load(List) only.
     * 2026-07-21
     */
    public void loadOrigin(String pathOrUrl) throws IOException {
        loadOrigin(pathOrUrl, false);
    }

    /**
     * Load an origin through IJK when the caller knows the platform decoder is unsuitable.
     * Network origins always use IJK, matching Solar's existing stream path.
     */
    public void loadOrigin(String pathOrUrl, boolean preferIjk) throws IOException {
        if (pathOrUrl == null || pathOrUrl.trim().isEmpty()) {
            throw new IOException("Need origin file or url");
        }
        releasePlayersOnly();
        usingIjk = false;
        preparedCount = 0;
        started = false;
        looping = false;
        targetRate = 1f;
        clearStutterInternal();
        sourceMode = SourceMode.ORIGIN;
        originGain = 1f;
        for (int i = 0; i < STEM_COUNT; i++) {
            gains[i] = 0f;
            loopCtrl[i] = false;
        }
        originPath = pathOrUrl;
        expectedPrepare = 1;
        
        if (shouldUseIjkOrigin(pathOrUrl, preferIjk)) {
            originIjkPlayer = com.solar.launcher.video.SolarIjkPlayerFactory.create();
            StemSoundTouch.applyStemPlayerOptions(originIjkPlayer);
            originIjkPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            originIjkPlayer.setDataSource(originPath);
            originIjkPlayer.setOnPreparedListener(new tv.danmaku.ijk.media.player.IMediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(tv.danmaku.ijk.media.player.IMediaPlayer p) {
                    preparedCount++;
                    applyOriginGain();
                    if (preparedCount >= expectedPrepare && listener != null) {
                        listener.onReady();
                    }
                }
            });
            originIjkPlayer.setOnCompletionListener(new tv.danmaku.ijk.media.player.IMediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(tv.danmaku.ijk.media.player.IMediaPlayer p) {
                    if (released || sourceMode != SourceMode.ORIGIN) return;
                    pause();
                    if (listener != null) listener.onComplete();
                }
            });
            originIjkPlayer.setOnErrorListener(
                    new tv.danmaku.ijk.media.player.IMediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(tv.danmaku.ijk.media.player.IMediaPlayer p,
                        int what, int extra) {
                    if (listener != null) {
                        listener.onError("Origin play error " + what + "/" + extra);
                    }
                    return true;
                }
            });
            originIjkPlayer.prepareAsync();
        } else {
            originPlayer = new MediaPlayer();
            originPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            originPlayer.setDataSource(originPath);
            originPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mediaPlayer) {
                    preparedCount++;
                    applyOriginGain();
                    if (preparedCount >= expectedPrepare && listener != null) {
                        listener.onReady();
                    }
                }
            });
            originPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {
                    if (released || sourceMode != SourceMode.ORIGIN) return;
                    pause();
                    if (listener != null) listener.onComplete();
                }
            });
            wireError(originPlayer);
            originPlayer.prepareAsync();
        }
    }

    static boolean shouldUseIjkOrigin(String pathOrUrl, boolean preferIjk) {
        if (preferIjk) return true;
        if (pathOrUrl == null) return false;
        return pathOrUrl.regionMatches(true, 0, "http://", 0, 7)
                || pathOrUrl.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * Load four stem pads (NP Stems on / Stem Player). Alias keeps call sites clear.
     * Melody catch-all: pass premixed zone-3 when available (see NpStemPersistGate).
     * 2026-07-21
     */
    public void loadPads(List<LalalClient.StemFile> stems) throws IOException {
        loadPads(stems, null);
    }

    /**
     * Pad load with optional bass body WAV.
     * 2026-07-21
     */
    public void loadPads(List<LalalClient.StemFile> stems, File bassBodyWav) throws IOException {
        sourceMode = SourceMode.PADS;
        releaseOriginOnly();
        load(stems, bassBodyWav);
    }

    /**
     * Apply Instrumentals/Vocals group gains on pad mode (timed fade).
     * Layman: mute voice or band like Stem Player dials.
     * Technical: {@link NpStemPadGains#targets} → setGain per zone.
     * 2026-07-21
     */
    public void applyIsolationGains(boolean wantVocals, boolean wantInstr) {
        if (sourceMode != SourceMode.PADS) return;
        float[] t = NpStemPadGains.targets(wantVocals, wantInstr);
        for (int z = 0; z < STEM_COUNT; z++) {
            setGain(z, t[z]);
        }
    }

    /**
     * Public seek for NP scrub — all audible feeds at matched playhead.
     * 2026-07-21
     */
    public void seekTo(int ms) {
        if (sourceMode == SourceMode.ORIGIN && (originPlayer != null || originIjkPlayer != null)) {
            try {
                if (originPlayer != null) originPlayer.seekTo(Math.max(0, ms));
                if (originIjkPlayer != null) originIjkPlayer.seekTo(Math.max(0, ms));
            } catch (Exception ignored) {}
            return;
        }
        seekAllPlaying(ms);
    }

    /**
     * Magical swap to pad mix at matched playhead (gapless short crossfade).
     * Layman: Stems turns on — song keeps its place, pads fade in.
     * Technical: loadPads → seek → fade origin out / pads in. Host may pause SolarTransport.
     * Was: hardCutToSoloFile. Reversal: drop; cut to transport layers.
     * 2026-07-21
     */
    public void crossfadeToPads(final List<LalalClient.StemFile> stems, final File bassBodyWav,
            final int positionMs, final boolean wantVocals, final boolean wantInstr,
            final boolean autoStart) {
        if (released || stems == null) return;
        swapBusy = true;
        final int pos = StemMixerSwapPolicy.matchedPositionMs(positionMs, getDurationMs());
        final boolean wasPlaying = isPlaying() || autoStart;
        autoStartPending = wasPlaying;
        try {
            final MediaPlayer keepOrigin = originPlayer;
            final tv.danmaku.ijk.media.player.IjkMediaPlayer keepIjk = originIjkPlayer;
            final String keepPath = originPath;
            originPlayer = null;
            originIjkPlayer = null;
            originPath = null;
            // Wire swap listener before prepare so onReady cannot race. 2026-07-21
            setListener(wrapReadyForPadSwap(pos, wantVocals, wantInstr, wasPlaying));
            sourceMode = SourceMode.PADS;
            load(stems, bassBodyWav);
            originPlayer = keepOrigin;
            originIjkPlayer = keepIjk;
            originPath = keepPath;
        } catch (Exception e) {
            swapBusy = false;
            if (listener != null) {
                listener.onError(e.getMessage() != null ? e.getMessage() : "pad swap failed");
            }
        }
    }

    /**
     * Magical swap back to origin file at matched playhead.
     * Layman: Stems off — back to the real track without a jump.
     * 2026-07-21
     */
    public void crossfadeToOrigin(final File track, final int positionMs, final boolean autoStart) {
        if (released || track == null || !track.isFile()) return;
        swapBusy = true;
        final int pos = StemMixerSwapPolicy.matchedPositionMs(positionMs, getDurationMs());
        final boolean wasPlaying = isPlaying() || autoStart;
        autoStartPending = wasPlaying;
        final float[] startPad = new float[STEM_COUNT];
        for (int i = 0; i < STEM_COUNT; i++) startPad[i] = gains[i];
        try {
            MediaPlayer[] keepPlayers = players;
            int[] keepZones = playerZones;
            int keepCount = playerCount;
            String[] keepPaths = playerPaths;
            MediaPlayer keepBass = bassBodyPlayer;
            players = new MediaPlayer[0];
            playerZones = new int[0];
            playerCount = 0;
            playerPaths = new String[0];
            bassBodyPlayer = null;
            setListener(wrapReadyForOriginSwap(pos, startPad, wasPlaying));
            loadOrigin(track.getAbsolutePath());
            players = keepPlayers;
            playerZones = keepZones;
            playerCount = keepCount;
            playerPaths = keepPaths;
            bassBodyPlayer = keepBass;
            sourceMode = SourceMode.ORIGIN;
            originGain = 0f;
            applyOriginGain();
        } catch (Exception e) {
            swapBusy = false;
            if (listener != null) {
                listener.onError(e.getMessage() != null ? e.getMessage() : "origin swap failed");
            }
        }
    }

    /**
     * After pads ready: seek both, start silent pads, fade to isolation targets.
     * 2026-07-21
     */
    private Listener wrapReadyForPadSwap(final int posMs, final boolean wantVocals,
            final boolean wantInstr, final boolean wasPlaying) {
        final Listener outer = listener;
        return new Listener() {
            @Override
            public void onReady() {
                try {
                    seekAllPlaying(posMs);
                    if (originPlayer != null) {
                        try { originPlayer.seekTo(posMs); } catch (Exception ignored) {}
                    }
                    if (originIjkPlayer != null) {
                        try { originIjkPlayer.seekTo(posMs); } catch (Exception ignored) {}
                    }
                    float[] end = StemMixerSwapPolicy.padGainsAtSwapEnd(true, wantVocals, wantInstr);
                    for (int z = 0; z < STEM_COUNT; z++) gains[z] = 0f;
                    applyAllGains();
                    if (wasPlaying) {
                        started = true;
                        for (int i = 0; i < playerCount; i++) {
                            try {
                                if (players[i] != null) players[i].start();
                            } catch (Exception ignored) {}
                        }
                        if (bassBodyPlayer != null) {
                            try { bassBodyPlayer.start(); } catch (Exception ignored) {}
                        }
                        if (originPlayer != null) {
                            try {
                                if (!originPlayer.isPlaying()) originPlayer.start();
                            } catch (Exception ignored) {}
                        }
                        if (originIjkPlayer != null) {
                            try {
                                if (!originIjkPlayer.isPlaying()) originIjkPlayer.start();
                            } catch (Exception ignored) {}
                        }
                    }
                    runCrossfadeTicks(/*fadeInPads*/ true, end, wasPlaying);
                } catch (Exception e) {
                    swapBusy = false;
                    if (outer != null) outer.onError(e.getMessage());
                }
                if (outer != null) outer.onReady();
            }

            @Override
            public void onError(String message) {
                swapBusy = false;
                if (outer != null) outer.onError(message);
            }

            @Override
            public void onComplete() {
                if (outer != null) outer.onComplete();
            }
        };
    }

    /**
     * After origin ready: seek, fade pads out / origin in, then drop pads.
     * 2026-07-21
     */
    private Listener wrapReadyForOriginSwap(final int posMs, final float[] startPad,
            final boolean wasPlaying) {
        final Listener outer = listener;
        return new Listener() {
            @Override
            public void onReady() {
                try {
                    if (originPlayer != null) {
                        try { originPlayer.seekTo(posMs); } catch (Exception ignored) {}
                    }
                    if (originIjkPlayer != null) {
                        try { originIjkPlayer.seekTo(posMs); } catch (Exception ignored) {}
                    }
                    seekAllPlaying(posMs);
                    for (int z = 0; z < STEM_COUNT; z++) {
                        gains[z] = startPad != null && z < startPad.length ? startPad[z] : 1f;
                    }
                    applyAllGains();
                    originGain = 0f;
                    applyOriginGain();
                    if (wasPlaying) {
                        started = true;
                        try {
                            if (originPlayer != null) originPlayer.start();
                        } catch (Exception ignored) {}
                        try {
                            if (originIjkPlayer != null) originIjkPlayer.start();
                        } catch (Exception ignored) {}
                        for (int i = 0; i < playerCount; i++) {
                            try {
                                if (players[i] != null && !players[i].isPlaying()) {
                                    players[i].start();
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    runCrossfadeTicks(/*fadeInPads*/ false, new float[] {0f, 0f, 0f, 0f},
                            wasPlaying);
                } catch (Exception e) {
                    swapBusy = false;
                    if (outer != null) outer.onError(e.getMessage());
                }
                if (outer != null) outer.onReady();
            }

            @Override
            public void onError(String message) {
                swapBusy = false;
                if (outer != null) outer.onError(message);
            }

            @Override
            public void onComplete() {
                if (outer != null) outer.onComplete();
            }
        };
    }

    /**
     * Tick origin + pad gains toward swap end; then release the silent side.
     * 2026-07-21
     */
    private void runCrossfadeTicks(final boolean fadeInPads, final float[] padEnd,
            final boolean wasPlaying) {
        final float originStart = fadeInPads ? 1f : 0f;
        final float originEnd = fadeInPads ? 0f : 1f;
        originGain = originStart;
        applyOriginGain();
        final float[] padCur = new float[STEM_COUNT];
        for (int z = 0; z < STEM_COUNT; z++) padCur[z] = gains[z];
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (released) {
                    swapBusy = false;
                    return;
                }
                boolean done = SoloLayerGains.fadeDone(originGain, originEnd);
                originGain = SoloLayerGains.stepToward(originGain, originEnd);
                applyOriginGain();
                for (int z = 0; z < STEM_COUNT; z++) {
                    float t = padEnd != null && z < padEnd.length ? padEnd[z] : 0f;
                    if (!SoloLayerGains.fadeDone(padCur[z], t)) done = false;
                    padCur[z] = SoloLayerGains.stepToward(padCur[z], t);
                    gains[z] = padCur[z];
                    applyGain(z);
                }
                if (!done) {
                    main.postDelayed(this, SoloLayerGains.FADE_TICK_MS);
                    return;
                }
                // Landed — drop the silent side so only one feed remains. 2026-07-21
                if (fadeInPads) {
                    releaseOriginOnly();
                    sourceMode = SourceMode.PADS;
                } else {
                    releasePadsOnly();
                    sourceMode = SourceMode.ORIGIN;
                    originGain = 1f;
                    applyOriginGain();
                }
                swapBusy = false;
                if (swapListener != null) swapListener.onSwapComplete(sourceMode);
            }
        };
        main.post(tick);
    }

    /** Volume for the origin MediaPlayer. 2026-07-21 */
    private void applyOriginGain() {
        if ((originPlayer == null && originIjkPlayer == null) || sourceMode != SourceMode.ORIGIN) return;
        float g = StemControls.clampGain(originGain);
        try {
            if (originPlayer != null) originPlayer.setVolume(g * masterGain, g * masterGain);
            if (originIjkPlayer != null) originIjkPlayer.setVolume(g * masterGain, g * masterGain);
        } catch (Exception ignored) {}
    }

    /** Tear down origin feed only (pads may keep playing). 2026-07-21 */
    private void releaseOriginOnly() {
        if (originPlayer != null) {
            try { originPlayer.stop(); } catch (Exception ignored) {}
            try { originPlayer.release(); } catch (Exception ignored) {}
            originPlayer = null;
        }
        if (originIjkPlayer != null) {
            try { originIjkPlayer.stop(); } catch (Exception ignored) {}
            try { originIjkPlayer.release(); } catch (Exception ignored) {}
            originIjkPlayer = null;
        }
        originPath = null;
        originGain = 1f;
    }

    /** Tear down pad players only (origin may keep playing). 2026-07-21 */
    private void releasePadsOnly() {
        for (int i = 0; i < players.length; i++) {
            MediaPlayer p = players[i];
            players[i] = null;
            if (p == null) continue;
            try { p.stop(); } catch (Exception ignored) {}
            try { p.release(); } catch (Exception ignored) {}
        }
        if (bassBodyPlayer != null) {
            try { bassBodyPlayer.stop(); } catch (Exception ignored) {}
            try { bassBodyPlayer.release(); } catch (Exception ignored) {}
            bassBodyPlayer = null;
        }
        players = new MediaPlayer[0];
        playerZones = new int[0];
        playerPaths = new String[0];
        playerCount = 0;
        expectedPrepare = 0;
        preparedCount = 0;
        for (int i = 0; i < STEM_COUNT; i++) gains[i] = 0f;
    }

    public void setBpm(float bpmValue) {
        bpm = bpmValue > 30f ? bpmValue : StemBpm.DEFAULT_BPM;
        msPerBar = StemBpm.msPerBar(bpm);
    }

    public float getBpm() {
        return bpm;
    }

    public int getMsPerBar() {
        return msPerBar;
    }

    /** How many MediaPlayers/IJK pads this mixer owns (incl. multi Melody). 2026-07-19 */
    public int getPlayerCount() {
        return playerCount;
    }

    /** Pads on one zone — Melody often >1 when live multi-other. 2026-07-19 */
    public int countPlayersForZone(int zone) {
        int n = 0;
        for (int i = 0; i < playerCount; i++) {
            if (playerZones[i] == zone) n++;
        }
        return n;
    }

    public float getTargetRate() {
        return targetRate;
    }

    /**
     * Match this song to Song 1’s tempo (pitch-preserving when IJK pads are active).
     * Layman: remember how fast this song should run vs Song 1.
     * Technical: stores rate; applies IjkMediaPlayer.setSpeed when usingIjk.
     * Full MP→IJK migrate deferred (MT6572 memory) — drift sync covers playhead.
     * Was: always 1.0. Reversal: ignore setTargetRate.
     * 2026-07-19
     */
    public void setTargetRate(float rate) {
        float r = rate > 0.1f ? rate : 1f;
        if (r < StemBpm.MIN_RATE) r = StemBpm.MIN_RATE;
        if (r > StemBpm.MAX_RATE) r = StemBpm.MAX_RATE;
        targetRate = r;
        if (usingIjk) applyIjkSpeed(targetRate);
    }

    /**
     * Apply hold beat-roll/screw playback rate (allows SCREW_RATES below tempo-match floor).
     * Layman: slow the pad while you mash it for rolled-and-screwed feel.
     * Technical: IJK setSpeed; MediaPlayer path stores only (no API 17 rate).
     * Was: setTargetRate clamped to 0.85–1.15. Reversal: call setTargetRate only.
     * 2026-07-19
     */
    public void setHoldScrewRate(float rate) {
        float r = rate > 0.1f ? rate : 1f;
        if (r < 0.5f) r = 0.5f;
        if (r > StemBpm.MAX_RATE) r = StemBpm.MAX_RATE;
        targetRate = r;
        if (usingIjk) applyIjkSpeed(targetRate);
    }

    /** Apply SoundTouch speed on live IJK pads. 2026-07-19 */
    private void applyIjkSpeed(float speed) {
        if (ijkPlayers != null) {
            for (int i = 0; i < ijkPlayers.length; i++) {
                if (ijkPlayers[i] == null) continue;
                try {
                    ijkPlayers[i].setSpeed(speed);
                } catch (Exception ignored) {}
            }
        }
        if (ijkBassBody != null) {
            try {
                ijkBassBody.setSpeed(speed);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Build IJK stem pads with soundtouch=1 at targetRate (songs 2–3 when BPM differs).
     * Call instead of MediaPlayer load when StemTempoSync.needsSoundTouch(rate).
     * 2026-07-19
     */
    public void loadWithSoundTouch(List<LalalClient.StemFile> stems, File bassBodyWav, float rate)
            throws IOException {
        float r = rate > 0.1f ? rate : 1f;
        if (r < StemBpm.MIN_RATE) r = StemBpm.MIN_RATE;
        if (r > StemBpm.MAX_RATE) r = StemBpm.MAX_RATE;
        targetRate = r;
        releasePlayersOnly();
        preparedCount = 0;
        started = false;
        looping = false;
        usingIjk = true;
        clearStutterInternal();
        for (int i = 0; i < STEM_COUNT; i++) {
            gains[i] = 0f;
            loopCtrl[i] = false;
        }
        if (stems == null || stems.isEmpty()) {
            throw new IOException("Need stem files");
        }
        // Cap IJK path too — same one-pad-per-zone budget. 2026-07-19
        List<LalalClient.StemFile> collapsed = LalalClient.collapseToOnePadPerZone(stems);
        List<LalalClient.StemFile> ok = new ArrayList<LalalClient.StemFile>();
        boolean[] zoneHit = new boolean[STEM_COUNT];
        for (int i = 0; i < collapsed.size(); i++) {
            LalalClient.StemFile s = collapsed.get(i);
            if (s == null || s.file == null || !s.file.isFile()) continue;
            int z = s.zone;
            if (z < 0 || z >= STEM_COUNT) z = 3;
            zoneHit[z] = true;
            ok.add(s);
        }
        if (ok.size() < 2 && (!zoneHit[0] || (!zoneHit[1] && !zoneHit[2] && !zoneHit[3]))) {
            throw new IOException("Need vocals and instrumental/melody stems");
        }
        boolean wantBody = bassBodyWav != null && bassBodyWav.isFile() && bassBodyWav.length() > 1000;
        playerCount = ok.size();
        expectedPrepare = playerCount + (wantBody ? 1 : 0);
        players = new MediaPlayer[0];
        playerZones = new int[playerCount];
        playerPaths = new String[playerCount];
        ijkPlayers = new tv.danmaku.ijk.media.player.IjkMediaPlayer[playerCount];
        for (int i = 0; i < playerCount; i++) {
            final int index = i;
            final LalalClient.StemFile stem = ok.get(i);
            final int zone = stem.zone >= 0 && stem.zone < STEM_COUNT ? stem.zone : 3;
            playerZones[i] = zone;
            playerPaths[i] = stem.file.getAbsolutePath();
            tv.danmaku.ijk.media.player.IjkMediaPlayer ijk =
                    com.solar.launcher.video.SolarIjkPlayerFactory.create();
            StemSoundTouch.applyStemPlayerOptions(ijk);
            ijk.setAudioStreamType(AudioManager.STREAM_MUSIC);
            ijk.setDataSource(playerPaths[i]);
            final float speed = targetRate;
            ijk.setOnPreparedListener(new tv.danmaku.ijk.media.player.IMediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(tv.danmaku.ijk.media.player.IMediaPlayer mp) {
                    preparedCount++;
                    try {
                        ijk.setSpeed(speed);
                    } catch (Exception ignored) {}
                    applyGain(zone);
                    if (started && !released && !ijk.isPlaying()) {
                        int pos = getPositionMs();
                        if (pos > 0) {
                            try { ijk.seekTo(pos); } catch (Exception ignored) {}
                        }
                        try { ijk.start(); } catch (Exception ignored) {}
                    } else if (preparedCount == 1 && autoStartPending && !released && !ijk.isPlaying()) {
                        started = true;
                        try { ijk.start(); } catch (Exception ignored) {}
                    }
                    if (preparedCount >= expectedPrepare) {
                        autoStartPending = false;
                        refineMsPerBar();
                        if (listener != null) listener.onReady();
                    }
                }
            });
            ijk.setOnCompletionListener(new tv.danmaku.ijk.media.player.IMediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(tv.danmaku.ijk.media.player.IMediaPlayer mp) {
                    if (released) return;
                    // Hard-muted — do not restart (volume-0 still audible on IJK). 2026-07-19
                    if (zone >= 0 && zone < STEM_COUNT && StemControls.isGainSilent(gains[zone])) {
                        return;
                    }
                    if (stutterZone == zone) {
                        try {
                            ijk.seekTo(stutterAnchorMs);
                            ijk.start();
                        } catch (Exception ignored) {}
                        return;
                    }
                    if (looping && zone >= 0 && zone < STEM_COUNT && loopCtrl[zone]) {
                        try {
                            ijk.seekTo(loopStartMs);
                            ijk.start();
                        } catch (Exception ignored) {}
                        return;
                    }
                    // Whole-song end: any audible pad may signal (Vocals mute must not kill seat). 2026-07-21
                    maybeFireSongComplete(zone);
                }
            });
            ijkPlayers[index] = ijk;
            ijk.prepareAsync();
        }
        if (wantBody) {
            bassBodyPath = bassBodyWav.getAbsolutePath();
            ijkBassBody = com.solar.launcher.video.SolarIjkPlayerFactory.create();
            StemSoundTouch.applyStemPlayerOptions(ijkBassBody);
            ijkBassBody.setAudioStreamType(AudioManager.STREAM_MUSIC);
            ijkBassBody.setDataSource(bassBodyPath);
            ijkBassBody.setOnPreparedListener(new tv.danmaku.ijk.media.player.IMediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(tv.danmaku.ijk.media.player.IMediaPlayer mp) {
                    preparedCount++;
                    try {
                        ijkBassBody.setSpeed(targetRate);
                    } catch (Exception ignored) {}
                    applyGain(2);
                    if (started && !released && !ijkBassBody.isPlaying()) {
                        int pos = getPositionMs();
                        if (pos > 0) {
                            try { ijkBassBody.seekTo(pos); } catch (Exception ignored) {}
                        }
                        try { ijkBassBody.start(); } catch (Exception ignored) {}
                    } else if (preparedCount == 1 && autoStartPending && !released && !ijkBassBody.isPlaying()) {
                        started = true;
                        try { ijkBassBody.start(); } catch (Exception ignored) {}
                    }
                    if (preparedCount >= expectedPrepare) {
                        autoStartPending = false;
                        refineMsPerBar();
                        if (listener != null) listener.onReady();
                    }
                }
            });
            ijkBassBody.prepareAsync();
        } else {
            bassBodyPath = null;
        }
    }

    /** Which zones join the A–B loop (pad-local). Others free-run. */
    public void setLoopCtrlMask(boolean[] mask) {
        for (int i = 0; i < STEM_COUNT; i++) {
            loopCtrl[i] = mask != null && i < mask.length && mask[i];
        }
    }

    public void setLoopCtrlZone(int zone, boolean on) {
        if (zone < 0 || zone >= STEM_COUNT) return;
        loopCtrl[zone] = on;
    }

    public boolean isLoopCtrlZone(int zone) {
        return zone >= 0 && zone < STEM_COUNT && loopCtrl[zone];
    }

    /**
     * Load stems; optional bassBodyWav plays with Bass gain (zone 2).
     * 2026-07-19
     */
    /**
     * Pad-local loop: seek this zone on A–B wrap?
     * Free-run and stutter pads are left alone. 2026-07-19
     */
    public static boolean shouldSeekZoneOnLoopWrap(boolean looping, boolean inLoopCtrl,
            boolean isStutterZone) {
        return looping && inLoopCtrl && !isStutterZone;
    }

    public void load(List<LalalClient.StemFile> stems) throws IOException {
        load(stems, null);
    }

    public void load(List<LalalClient.StemFile> stems, File bassBodyWav) throws IOException {
        releasePlayersOnly();
        // Pad mode unless a swap temporarily reattached origin. 2026-07-21
        if (originPlayer == null && originIjkPlayer == null) sourceMode = SourceMode.PADS;
        usingIjk = false;
        preparedCount = 0;
        started = false;
        looping = false;
        targetRate = 1f;
        clearStutterInternal();
        for (int i = 0; i < STEM_COUNT; i++) {
            gains[i] = 0f;
            loopCtrl[i] = false;
        }
        if (stems == null || stems.isEmpty()) {
            throw new IOException("Need stem files");
        }
        // Cap at one stream per pad — live multi-Melody was 7 players/song on Y1. 2026-07-19
        List<LalalClient.StemFile> collapsed = LalalClient.collapseToOnePadPerZone(stems);
        List<LalalClient.StemFile> ok = new ArrayList<LalalClient.StemFile>();
        boolean[] zoneHit = new boolean[STEM_COUNT];
        for (int i = 0; i < collapsed.size(); i++) {
            LalalClient.StemFile s = collapsed.get(i);
            if (s == null || s.file == null || !s.file.isFile()) continue;
            int z = s.zone;
            if (z < 0 || z >= STEM_COUNT) z = 3;
            zoneHit[z] = true;
            ok.add(s);
        }
        if (ok.size() < 2 && (!zoneHit[0] || (!zoneHit[1] && !zoneHit[2] && !zoneHit[3]))) {
            throw new IOException("Need vocals and instrumental/melody stems");
        }
        boolean wantBody = bassBodyWav != null && bassBodyWav.isFile() && bassBodyWav.length() > 1000;
        playerCount = ok.size();
        expectedPrepare = playerCount + (wantBody ? 1 : 0);
        players = new MediaPlayer[playerCount];
        playerZones = new int[playerCount];
        playerPaths = new String[playerCount];
        for (int i = 0; i < playerCount; i++) {
            final int index = i;
            final LalalClient.StemFile stem = ok.get(i);
            final int zone = stem.zone >= 0 && stem.zone < STEM_COUNT ? stem.zone : 3;
            playerZones[i] = zone;
            playerPaths[i] = stem.file.getAbsolutePath();
            MediaPlayer mp = new MediaPlayer();
            players[i] = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(stem.file.getAbsolutePath());
            wirePrepared(mp, zone);
            wireCompletion(mp, zone, index, stem.id);
            wireError(mp);
            mp.prepareAsync();
        }
        if (wantBody) {
            bassBodyPath = bassBodyWav.getAbsolutePath();
            bassBodyPlayer = new MediaPlayer();
            bassBodyPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            bassBodyPlayer.setDataSource(bassBodyWav.getAbsolutePath());
            wirePrepared(bassBodyPlayer, 2);
            wireCompletion(bassBodyPlayer, 2, -1, "bass_body");
            wireError(bassBodyPlayer);
            bassBodyPlayer.prepareAsync();
        } else {
            bassBodyPath = null;
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("players", playerCount);
            d.put("wantBody", wantBody);
            d.put("z0", countPlayersForZone(0));
            d.put("z1", countPlayersForZone(1));
            d.put("z2", countPlayersForZone(2));
            d.put("z3", countPlayersForZone(3));
            d.put("stemList", stems != null ? stems.size() : 0);
            com.solar.launcher.Debug8b0481Log.log(
                    "StemMixer.load", "players after load", "H1", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    private void wirePrepared(MediaPlayer mp, final int zone) {
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mediaPlayer) {
                preparedCount++;
                applyGain(zone);
                if (started && !released && !mediaPlayer.isPlaying()) {
                    int pos = getPositionMs();
                    if (pos > 0) {
                        try { mediaPlayer.seekTo(pos); } catch (Exception ignored) {}
                    }
                    try { mediaPlayer.start(); } catch (Exception ignored) {}
                } else if (preparedCount == 1 && autoStartPending && !released && !mediaPlayer.isPlaying()) {
                    started = true;
                    try { mediaPlayer.start(); } catch (Exception ignored) {}
                }
                if (preparedCount >= expectedPrepare) {
                    autoStartPending = false;
                    refineMsPerBar();
                    if (listener != null) listener.onReady();
                }
            }
        });
    }

    private void wireCompletion(MediaPlayer mp, final int zone, final int index, final String id) {
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                if (released) return;
                // Hard-muted pad — do not restart (would leak until next gain apply). 2026-07-19
                if (zone >= 0 && zone < STEM_COUNT && StemControls.isGainSilent(gains[zone])) {
                    return;
                }
                if (stutterZone == zone) {
                    try {
                        mediaPlayer.seekTo(stutterAnchorMs);
                        mediaPlayer.start();
                    } catch (Exception ignored) {}
                    return;
                }
                if (looping && zone >= 0 && zone < STEM_COUNT && loopCtrl[zone]) {
                    try {
                        mediaPlayer.seekTo(loopStartMs);
                        mediaPlayer.start();
                    } catch (Exception ignored) {}
                    return;
                }
                // Whole-song end from any audible stem (pair-repeat needs both seats). 2026-07-21
                maybeFireSongComplete(zone);
            }
        });
    }

    /**
     * Notify host once when this mixer’s song ends (any audible stem).
     * Layman: the track finished — restart or advance; don’t wait only on Vocals.
     * Technical: debounce songCompleteFired; silent zones ignored; sibling mixer separate.
     * Was: zone==0 only (+ silent Vocals swallowed end). Reversal: if (zone!=0) return.
     * 2026-07-21
     */
    private void maybeFireSongComplete(int zone) {
        if (released || songCompleteFired) return;
        if (zone >= 0 && zone < STEM_COUNT && StemControls.isGainSilent(gains[zone])) {
            return;
        }
        songCompleteFired = true;
        try {
            pause();
        } catch (Exception ignored) {}
        if (listener != null) listener.onComplete();
    }

    /** Allow another onComplete after seek/restart. 2026-07-21 */
    private void clearSongCompleteLatch() {
        songCompleteFired = false;
    }

    private void wireError(MediaPlayer mp) {
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                if (listener != null) {
                    listener.onError("Stem play error " + what + "/" + extra);
                }
                return true;
            }
        });
    }

    private MediaPlayer leadPlayer() {
        for (int i = 0; i < playerCount; i++) {
            if (playerZones[i] == 0 && players[i] != null) return players[i];
        }
        return playerCount > 0 ? players[0] : null;
    }

    private void refineMsPerBar() {
        try {
            MediaPlayer lead = leadPlayer();
            if (lead != null) {
                int dur = lead.getDuration();
                bpm = StemBpm.estimateFromDurationMs(dur);
                msPerBar = StemBpm.msPerBar(bpm);
            }
        } catch (Exception ignored) {}
    }

    private void syncBassBodyToLead(int pos) {
        if (bassBodyPlayer == null || stutterZone == 2) return;
        if (looping && !loopCtrl[2]) return;
        // Bass muted → body stays paused (same hard-mute as pad). 2026-07-19
        if (StemControls.isGainSilent(gains[2])) return;
        try {
            int d = Math.abs(bassBodyPlayer.getCurrentPosition() - pos);
            if (d > 350) {
                bassBodyPlayer.seekTo(pos);
                if (!bassBodyPlayer.isPlaying() && started && !released) bassBodyPlayer.start();
            }
        } catch (Exception ignored) {}
    }

    public void play() {
        if (released) return;
        autoStartPending = true;
        clearSongCompleteLatch();
        try {
            // Origin-only feed (NP Stems off). 2026-07-21
            if (sourceMode == SourceMode.ORIGIN && (originPlayer != null || originIjkPlayer != null) && playerCount == 0) {
                if (originPlayer != null) {
                    originPlayer.seekTo(0);
                    originPlayer.start();
                }
                if (originIjkPlayer != null) {
                    originIjkPlayer.seekTo(0);
                    originIjkPlayer.start();
                }
                originGain = 1f;
                applyOriginGain();
                started = true;
                return;
            }
            if (usingIjk && ijkPlayers != null) {
                for (int i = 0; i < ijkPlayers.length; i++) {
                    if (ijkPlayers[i] != null) {
                        ijkPlayers[i].seekTo(0);
                        ijkPlayers[i].start();
                    }
                }
                if (ijkBassBody != null) {
                    ijkBassBody.seekTo(0);
                    ijkBassBody.start();
                }
            } else {
                for (int i = 0; i < playerCount; i++) {
                    MediaPlayer p = players[i];
                    if (p != null) {
                        p.seekTo(0);
                        p.start();
                    }
                }
                if (bassBodyPlayer != null) {
                    bassBodyPlayer.seekTo(0);
                    bassBodyPlayer.start();
                }
            }
            started = true;
            // Pause pads still at gain 0 — setVolume(0) alone still bleeds. 2026-07-19
            applyAllGains();
            main.removeCallbacks(driftFix);
            main.postDelayed(driftFix, 3000);
        } catch (Exception e) {
            if (listener != null) listener.onError(e.getMessage());
        }
    }

    public void pause() {
        stopBeatRoll();
        if (originPlayer != null || originIjkPlayer != null) {
            try {
                if (originPlayer != null && originPlayer.isPlaying()) originPlayer.pause();
            } catch (Exception ignored) {}
            try {
                if (originIjkPlayer != null && originIjkPlayer.isPlaying()) originIjkPlayer.pause();
            } catch (Exception ignored) {}
        }
        if (usingIjk && ijkPlayers != null) {
            for (int i = 0; i < ijkPlayers.length; i++) {
                try {
                    if (ijkPlayers[i] != null && ijkPlayers[i].isPlaying()) ijkPlayers[i].pause();
                } catch (Exception ignored) {}
            }
            try {
                if (ijkBassBody != null && ijkBassBody.isPlaying()) ijkBassBody.pause();
            } catch (Exception ignored) {}
            return;
        }
        for (int i = 0; i < playerCount; i++) {
            try {
                MediaPlayer p = players[i];
                if (p != null && p.isPlaying()) p.pause();
            } catch (Exception ignored) {}
        }
        try {
            if (bassBodyPlayer != null && bassBodyPlayer.isPlaying()) bassBodyPlayer.pause();
        } catch (Exception ignored) {}
    }

    public void resume() {
        if ((originPlayer != null || originIjkPlayer != null) && (sourceMode == SourceMode.ORIGIN || swapBusy)) {
            try {
                if (originPlayer != null) originPlayer.start();
                if (originIjkPlayer != null) originIjkPlayer.start();
            } catch (Exception ignored) {}
            started = true;
            applyOriginGain();
            if (sourceMode == SourceMode.ORIGIN && playerCount == 0) return;
        }
        if (usingIjk && ijkPlayers != null) {
            for (int i = 0; i < ijkPlayers.length; i++) {
                try {
                    if (ijkPlayers[i] != null) ijkPlayers[i].start();
                } catch (Exception ignored) {}
            }
            try {
                if (ijkBassBody != null) ijkBassBody.start();
            } catch (Exception ignored) {}
            started = true;
            applyAllGains();
            return;
        }
        for (int i = 0; i < playerCount; i++) {
            try {
                MediaPlayer p = players[i];
                if (p != null) p.start();
            } catch (Exception ignored) {}
        }
        try {
            if (bassBodyPlayer != null) bassBodyPlayer.start();
        } catch (Exception ignored) {}
        started = true;
        applyAllGains();
    }

    public boolean togglePlayPause() {
        if (isPlaying()) {
            pause();
            return false;
        }
        resume();
        return true;
    }

    public boolean isPlaying() {
        try {
            if ((originPlayer != null || originIjkPlayer != null) && sourceMode == SourceMode.ORIGIN && playerCount == 0) {
                if (originPlayer != null) return originPlayer.isPlaying();
                if (originIjkPlayer != null) return originIjkPlayer.isPlaying();
            }
            if (usingIjk && ijkPlayers != null && ijkPlayers.length > 0 && ijkPlayers[0] != null) {
                return ijkPlayers[0].isPlaying();
            }
            MediaPlayer lead = leadPlayer();
            return lead != null && lead.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public int getPositionMs() {
        try {
            if ((originPlayer != null || originIjkPlayer != null) && sourceMode == SourceMode.ORIGIN && playerCount == 0) {
                if (originPlayer != null) return originPlayer.getCurrentPosition();
                if (originIjkPlayer != null) return (int) originIjkPlayer.getCurrentPosition();
            }
            if (usingIjk && ijkPlayers != null && ijkPlayers.length > 0 && ijkPlayers[0] != null) {
                return (int) ijkPlayers[0].getCurrentPosition();
            }
            MediaPlayer lead = leadPlayer();
            return lead != null ? lead.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDurationMs() {
        try {
            if ((originPlayer != null || originIjkPlayer != null) && sourceMode == SourceMode.ORIGIN && playerCount == 0) {
                if (originPlayer != null) return originPlayer.getDuration();
                if (originIjkPlayer != null) return (int) originIjkPlayer.getDuration();
            }
            if (usingIjk && ijkPlayers != null && ijkPlayers.length > 0 && ijkPlayers[0] != null) {
                return (int) ijkPlayers[0].getDuration();
            }
            MediaPlayer lead = leadPlayer();
            return lead != null ? lead.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public void startLoop(float bars) {
        float b = bars > 0f ? bars : StemControls.DEFAULT_LOOP_BARS;
        loopBars = b;
        int pos = getPositionMs();
        int dur = getDurationMs();
        int len = Math.max(200, Math.round(b * msPerBar));
        loopStartMs = pos;
        loopEndMs = pos + len;
        if (dur > 0 && loopEndMs > dur) {
            loopEndMs = dur;
            loopStartMs = Math.max(0, dur - len);
        }
        looping = true;
        main.removeCallbacks(loopTick);
        main.post(loopTick);
    }

    public void setLoopBars(float bars) {
        loopBars = bars > 0f ? bars : StemControls.DEFAULT_LOOP_BARS;
        if (!looping) return;
        int len = Math.max(200, Math.round(loopBars * msPerBar));
        int dur = getDurationMs();
        loopEndMs = loopStartMs + len;
        if (dur > 0 && loopEndMs > dur) {
            loopEndMs = dur;
        }
    }

    public float getLoopBars() {
        return loopBars;
    }

    public boolean isLooping() {
        return looping;
    }

    public void clearLoop() {
        looping = false;
        main.removeCallbacks(loopTick);
        for (int i = 0; i < STEM_COUNT; i++) loopCtrl[i] = false;
    }

    /**
     * Hold-stem beat roll — quantized slice retrigger while key is held.
     * Layman: mash the pad, it chatters on the beat; let go and jump to where the song would be.
     * Was: startHoldStutter frozen chop (release left playhead on anchor).
     * Reversal: rename back + stop without catch-up seek.
     * 2026-07-19 / 2026-07-20
     */
    public void startBeatRoll(int zone, int sliceMs) {
        if (released || zone < 0 || zone >= STEM_COUNT) return;
        // Same zone already rolling — only resize slice (wheel), keep catch-up origin. 2026-07-20
        if (stutterZone == zone && stutterSliceMs > 0) {
            setStutterSliceMs(sliceMs);
            return;
        }
        stopBeatRoll();
        stutterZone = zone;
        stutterTickCount = 0;
        stutterSliceMs = sliceMs > MIN_STUTTER_MS ? sliceMs : DEFAULT_STUTTER_MS;
        if (stutterSliceMs < MIN_STUTTER_MS) stutterSliceMs = MIN_STUTTER_MS;
        // Snap roll to beat grid so slices land on the pulse. 2026-07-19
        stutterAnchorMs = StemBpm.snapToBeatMs(positionForZone(zone), getBpm());
        if (looping && loopCtrl[zone]) {
            // Keep roll inside A–B window.
            if (stutterAnchorMs < loopStartMs || stutterAnchorMs >= loopEndMs) {
                stutterAnchorMs = StemBpm.snapToBeatMs(loopStartMs, getBpm());
            }
            int maxSlice = Math.max(MIN_STUTTER_MS, loopEndMs - stutterAnchorMs);
            if (stutterSliceMs > maxSlice) stutterSliceMs = maxSlice;
        }
        // Virtual timeline origin — wheel resize must not reset these. 2026-07-20
        rollOriginElapsedRealtime = SystemClock.elapsedRealtime();
        rollOriginPosMs = stutterAnchorMs;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("zone", zone);
            d.put("sliceMs", stutterSliceMs);
            d.put("anchor", stutterAnchorMs);
            com.solar.launcher.Debug543e15Log.log(
                    "StemMixer.startBeatRoll", "beat-roll start", "F1", d);
        } catch (Exception ignored) {}
        // #endregion
        seekZone(zone, stutterAnchorMs);
        main.removeCallbacks(stutterTick);
        main.post(stutterTick);
    }

    /** @deprecated Prefer {@link #startBeatRoll}. 2026-07-20 */
    public void startHoldStutter(int zone, int sliceMs) {
        startBeatRoll(zone, sliceMs);
    }

    /**
     * Resize active beat roll without resetting catch-up origin (wheel while held).
     * 2026-07-19 / 2026-07-20
     */
    public void setStutterSliceMs(int sliceMs) {
        if (stutterZone < 0) return;
        int s = sliceMs > MIN_STUTTER_MS ? sliceMs : MIN_STUTTER_MS;
        if (looping && loopCtrl[stutterZone]) {
            int maxSlice = Math.max(MIN_STUTTER_MS, loopEndMs - stutterAnchorMs);
            if (s > maxSlice) s = maxSlice;
        }
        stutterSliceMs = s;
    }

    /**
     * End beat roll — seek catch-up then clear timer.
     * Layman: let go and the song jumps ahead to “now”, not stuck on the chatter.
     * Was: stopStutter cleared timer only (frozen chop). Reversal: clear without seekZone.
     * 2026-07-20
     */
    public void stopBeatRoll() {
        if (stutterZone >= 0) {
            int zone = stutterZone;
            long elapsed = SystemClock.elapsedRealtime() - rollOriginElapsedRealtime;
            int catchUp = StemBpm.beatRollCatchUpMs(
                    rollOriginPosMs, elapsed, getTargetRate(), getDurationMs());
            // Inside A–B: keep catch-up in the loop window for joined pads. 2026-07-20
            if (looping && zone >= 0 && zone < STEM_COUNT && loopCtrl[zone]
                    && loopEndMs > loopStartMs) {
                if (catchUp < loopStartMs) catchUp = loopStartMs;
                if (catchUp >= loopEndMs) catchUp = Math.max(loopStartMs, loopEndMs - 1);
            }
            try {
                seekZone(zone, catchUp);
            } catch (Exception ignored) {}
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("zone", zone);
                d.put("ticks", stutterTickCount);
                d.put("catchUp", catchUp);
                d.put("elapsed", elapsed);
                com.solar.launcher.Debug543e15Log.log(
                        "StemMixer.stopBeatRoll", "beat-roll stop catch-up", "F1", d);
            } catch (Exception ignored) {}
            // #endregion
        }
        clearStutterInternal();
    }

    /** @deprecated Prefer {@link #stopBeatRoll}. 2026-07-20 */
    public void stopStutter() {
        stopBeatRoll();
    }

    public boolean isStuttering() {
        return stutterZone >= 0;
    }

    public int getStutterZone() {
        return stutterZone;
    }

    private void clearStutterInternal() {
        main.removeCallbacks(stutterTick);
        stutterZone = -1;
        stutterSliceMs = 0;
        stutterAnchorMs = 0;
        stutterTickCount = 0;
        rollOriginElapsedRealtime = 0L;
        rollOriginPosMs = 0;
    }

    private int positionForZone(int zone) {
        for (int i = 0; i < playerCount; i++) {
            if (playerZones[i] != zone) continue;
            try {
                if (usingIjk && ijkPlayers != null && ijkPlayers[i] != null) {
                    return (int) ijkPlayers[i].getCurrentPosition();
                }
                if (players.length > i && players[i] != null) {
                    return players[i].getCurrentPosition();
                }
            } catch (Exception ignored) {}
        }
        if (zone == 2) {
            try {
                if (usingIjk && ijkBassBody != null) {
                    return (int) ijkBassBody.getCurrentPosition();
                }
                if (bassBodyPlayer != null) return bassBodyPlayer.getCurrentPosition();
            } catch (Exception ignored) {}
        }
        return getPositionMs();
    }

    private void seekZone(int zone, int ms) {
        // Silent pads stay paused unless this is an active chop (gain raised first). 2026-07-19
        boolean allowStart = started && !StemControls.isGainSilent(gains[zone]);
        for (int i = 0; i < playerCount; i++) {
            if (playerZones[i] != zone) continue;
            try {
                if (usingIjk && ijkPlayers != null && ijkPlayers[i] != null) {
                    ijkPlayers[i].seekTo(ms);
                    if (allowStart && !ijkPlayers[i].isPlaying()) ijkPlayers[i].start();
                } else if (players != null && players.length > i && players[i] != null) {
                    MediaPlayer p = players[i];
                    p.seekTo(ms);
                    if (allowStart && !p.isPlaying()) p.start();
                }
            } catch (IllegalStateException ise) {
                // MediaPlayer in bad state — abort chop rather than native death. 2026-07-19
                throw ise;
            } catch (Exception ignored) {}
        }
        if (zone == 2) {
            try {
                if (usingIjk && ijkBassBody != null) {
                    ijkBassBody.seekTo(ms);
                    if (allowStart && !ijkBassBody.isPlaying()) ijkBassBody.start();
                } else if (bassBodyPlayer != null) {
                    bassBodyPlayer.seekTo(ms);
                    if (allowStart && !bassBodyPlayer.isPlaying()) bassBodyPlayer.start();
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Seek every stem layer (+ bass body) on this mixer to the same ms.
     * Layman: jump the whole song together so pads stay in sync.
     * Hold-OK scrub must call this (or soft-blend that ends here) — never seek one zone alone.
     * Sibling song = different StemMixer instance — untouched.
     * 2026-07-19 / 2026-07-21
     */
    public void seekAllPlaying(int ms) {
        clearSongCompleteLatch();
        for (int i = 0; i < playerCount; i++) {
            int z = playerZones[i];
            if (z == stutterZone) continue;
            if (looping && z >= 0 && z < STEM_COUNT && !loopCtrl[z]) continue;
            try {
                if (usingIjk && ijkPlayers != null && ijkPlayers[i] != null) {
                    ijkPlayers[i].seekTo(ms);
                } else if (players.length > i && players[i] != null) {
                    players[i].seekTo(ms);
                }
            } catch (Exception ignored) {}
        }
        if (stutterZone != 2) {
            try {
                if (usingIjk && ijkBassBody != null) ijkBassBody.seekTo(ms);
                else if (bassBodyPlayer != null) bassBodyPlayer.seekTo(ms);
            } catch (Exception ignored) {}
        }
    }

    public void setMasterGain(float gain) {
        this.masterGain = gain;
        applyOriginGain();
        applyAllGains();
    }

    public void setGain(int zone, float gain) {
        if (zone < 0 || zone >= STEM_COUNT) return;
        gains[zone] = StemControls.clampGain(gain);
        applyGain(zone);
    }

    public float getGain(int zone) {
        if (zone < 0 || zone >= STEM_COUNT) return 0f;
        return gains[zone];
    }

    public float nudgeGainSteps(int zone, int steps) {
        float g = StemControls.nudgeGain(getGain(zone), steps);
        setGain(zone, g);
        return g;
    }

    public float nudgeGain(int zone, float delta) {
        setGain(zone, getGain(zone) + delta);
        return getGain(zone);
    }

    /**
     * Push pad gain to players — mute is volume 0 only (timeline keeps running).
     * Layman: dial to silence and back up — song is still where it was.
     * Was: pause when silent + seekTo(getPositionMs)/start on unmute (felt like restart).
     * Reversal: restore pause/seek unmute path; ignore StemPadMutePolicy.
     * 2026-07-19 / 2026-07-21
     */
    private void applyGain(int zone) {
        float g = gains[zone] * masterGain;
        boolean silent = StemControls.isGainSilent(g);
        // Volume-only mute — never seek/restart on zero↔audible. 2026-07-21
        boolean pauseSilent = StemPadMutePolicy.shouldPauseWhenSilent();
        boolean seekUnmute = StemPadMutePolicy.shouldSeekOnUnmute();
        if (usingIjk && ijkPlayers != null) {
            for (int i = 0; i < ijkPlayers.length; i++) {
                if (playerZones[i] != zone) continue;
                if (ijkPlayers[i] == null) continue;
                try {
                    ijkPlayers[i].setVolume(silent ? 0f : g, silent ? 0f : g);
                    if (pauseSilent && silent) {
                        if (ijkPlayers[i].isPlaying()) ijkPlayers[i].pause();
                    } else if (pauseSilent && !silent && started && !released
                            && !ijkPlayers[i].isPlaying()) {
                        if (seekUnmute) ijkPlayers[i].seekTo(getPositionMs());
                        ijkPlayers[i].start();
                    }
                } catch (Exception ignored) {}
            }
            if (zone == 2 && ijkBassBody != null) {
                float bg = silent ? 0f : g * StemBassBody.BODY_GAIN_K;
                try {
                    ijkBassBody.setVolume(bg, bg);
                    if (pauseSilent && silent) {
                        if (ijkBassBody.isPlaying()) ijkBassBody.pause();
                    } else if (pauseSilent && !silent && started && !released
                            && !ijkBassBody.isPlaying()) {
                        if (seekUnmute) ijkBassBody.seekTo(getPositionMs());
                        ijkBassBody.start();
                    }
                } catch (Exception ignored) {}
            }
            return;
        }
        for (int i = 0; i < playerCount; i++) {
            if (playerZones[i] != zone) continue;
            MediaPlayer p = players[i];
            if (p == null) continue;
            try {
                p.setVolume(silent ? 0f : g, silent ? 0f : g);
                if (pauseSilent && silent) {
                    if (p.isPlaying()) p.pause();
                } else if (pauseSilent && !silent && started && !released && !p.isPlaying()) {
                    if (seekUnmute) p.seekTo(getPositionMs());
                    p.start();
                }
            } catch (Exception ignored) {}
        }
        if (zone == 2 && bassBodyPlayer != null) {
            float bg = silent ? 0f : g * StemBassBody.BODY_GAIN_K;
            try {
                bassBodyPlayer.setVolume(bg, bg);
                if (pauseSilent && silent) {
                    if (bassBodyPlayer.isPlaying()) bassBodyPlayer.pause();
                } else if (pauseSilent && !silent && started && !released
                        && !bassBodyPlayer.isPlaying()) {
                    if (seekUnmute) bassBodyPlayer.seekTo(getPositionMs());
                    bassBodyPlayer.start();
                }
            } catch (Exception ignored) {}
        }
    }

    /** Re-apply every zone after play/resume so zeroed pads stay paused. 2026-07-19 */
    private void applyAllGains() {
        for (int z = 0; z < STEM_COUNT; z++) {
            applyGain(z);
        }
    }

    /**
     * Swap one pad’s stem file mid-session — DEPRECATED for mashup (causes restart).
     * Host now keeps one StemMixer per song always running; cycle = control routing only.
     * Kept for possible lab tooling. Was: mashup cycle path. Reversal: call from swapPadStem.
     * 2026-07-19
     */
    public void replaceZoneStem(int zone, File stemFile) throws IOException {
        if (released || zone < 0 || zone >= STEM_COUNT) {
            throw new IOException("replaceZoneStem bad zone");
        }
        if (stemFile == null || !stemFile.isFile() || stemFile.length() < 100) {
            throw new IOException("replaceZoneStem missing file");
        }
        if (usingIjk) {
            // Rate≠1 path deferred — mashup uses MediaPlayer @ 1.0 for now. 2026-07-19
            throw new IOException("replaceZoneStem needs MediaPlayer path");
        }
        // Drop every player currently on this zone (Melody may have several). 2026-07-19
        java.util.ArrayList<MediaPlayer> keepP = new java.util.ArrayList<MediaPlayer>();
        java.util.ArrayList<Integer> keepZ = new java.util.ArrayList<Integer>();
        java.util.ArrayList<String> keepPaths = new java.util.ArrayList<String>();
        for (int i = 0; i < playerCount; i++) {
            if (playerZones[i] == zone) {
                MediaPlayer old = players[i];
                players[i] = null;
                if (old != null) {
                    try { old.stop(); } catch (Exception ignored) {}
                    try { old.release(); } catch (Exception ignored) {}
                }
            } else {
                keepP.add(players[i]);
                keepZ.add(Integer.valueOf(playerZones[i]));
                keepPaths.add(playerPaths != null && i < playerPaths.length
                        ? playerPaths[i] : null);
            }
        }
        // Bass body rides zone 2 — drop it when swapping bass. 2026-07-19
        if (zone == 2 && bassBodyPlayer != null) {
            try { bassBodyPlayer.stop(); } catch (Exception ignored) {}
            try { bassBodyPlayer.release(); } catch (Exception ignored) {}
            bassBodyPlayer = null;
            bassBodyPath = null;
        }
        final int newIndex = keepP.size();
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setDataSource(stemFile.getAbsolutePath());
        keepP.add(mp);
        keepZ.add(Integer.valueOf(zone));
        keepPaths.add(stemFile.getAbsolutePath());
        players = keepP.toArray(new MediaPlayer[keepP.size()]);
        playerZones = new int[keepZ.size()];
        for (int i = 0; i < keepZ.size(); i++) playerZones[i] = keepZ.get(i).intValue();
        playerPaths = keepPaths.toArray(new String[keepPaths.size()]);
        playerCount = players.length;
        expectedPrepare = playerCount + (bassBodyPlayer != null ? 1 : 0);
        // Don't fire onReady again for a mid-session swap — just start when prepared. 2026-07-19
        final boolean wasStarted = started;
        final int leadPos = getPositionMs();
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mediaPlayer) {
                applyGain(zone);
                try {
                    if (wasStarted) {
                        mediaPlayer.seekTo(leadPos);
                        mediaPlayer.start();
                    }
                } catch (Exception ignored) {}
            }
        });
        wireCompletion(mp, zone, newIndex, stemFile.getName());
        wireError(mp);
        mp.prepareAsync();
    }

    /**
     * Stem file currently driving a zone (first player on that pad).
     * 2026-07-19
     */
    public File stemFileForZone(int zone) {
        if (zone < 0 || zone >= STEM_COUNT) return null;
        for (int i = 0; i < playerCount; i++) {
            if (playerZones[i] != zone) continue;
            if (playerPaths != null && i < playerPaths.length && playerPaths[i] != null) {
                return new File(playerPaths[i]);
            }
        }
        return null;
    }

    public void release() {
        released = true;
        looping = false;
        clearStutterInternal();
        main.removeCallbacks(driftFix);
        main.removeCallbacks(loopTick);
        releasePlayersOnly();
        listener = null;
    }

    private void releasePlayersOnly() {
        releaseOriginOnly();
        for (int i = 0; i < players.length; i++) {
            MediaPlayer p = players[i];
            players[i] = null;
            if (p == null) continue;
            try { p.stop(); } catch (Exception ignored) {}
            try { p.release(); } catch (Exception ignored) {}
        }
        if (bassBodyPlayer != null) {
            try { bassBodyPlayer.stop(); } catch (Exception ignored) {}
            try { bassBodyPlayer.release(); } catch (Exception ignored) {}
            bassBodyPlayer = null;
        }
        if (ijkPlayers != null) {
            for (int i = 0; i < ijkPlayers.length; i++) {
                tv.danmaku.ijk.media.player.IjkMediaPlayer p = ijkPlayers[i];
                ijkPlayers[i] = null;
                if (p == null) continue;
                try { p.stop(); } catch (Exception ignored) {}
                try { p.release(); } catch (Exception ignored) {}
            }
        }
        if (ijkBassBody != null) {
            try { ijkBassBody.stop(); } catch (Exception ignored) {}
            try { ijkBassBody.release(); } catch (Exception ignored) {}
            ijkBassBody = null;
        }
        players = new MediaPlayer[0];
        ijkPlayers = null;
        usingIjk = false;
        playerCount = 0;
        expectedPrepare = 0;
        preparedCount = 0;
    }
}
