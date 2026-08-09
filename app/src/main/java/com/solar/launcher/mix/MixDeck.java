package com.solar.launcher.mix;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.audio.SolarTransport;
import com.solar.launcher.stem.StemSoundTouch;
import com.solar.launcher.video.SolarIjkPlayerFactory;

import java.io.File;
import java.io.IOException;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * One Mix deck — full track via IJK + optional SoundTouch rate.
 * Layman: one song playing under one pad, speed-matched without chipmunking.
 * Technical: IjkMediaPlayer audio-only; setSpeed for beat sync; fade on solar-audio looper.
 * Was: fade ticks on main Looper. Reversal: audio = new Handler(Looper.getMainLooper()).
 * 2026-07-19 / 2026-07-20
 */
public final class MixDeck {
    public interface Listener {
        void onReady(MixDeck deck);
        void onError(MixDeck deck, String message);
        void onComplete(MixDeck deck);
    }

    private final Handler audio;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private IjkMediaPlayer player;
    private Listener listener;
    private float gain;
    private float rate = 1f;
    private boolean started;
    private boolean released;
    private boolean prepared;
    private File path;
    private int fadeStepsLeft;
    private float fadeFrom;
    private float fadeTo;
    private final Runnable fadeTick = new Runnable() {
        @Override
        public void run() {
            if (released || fadeStepsLeft <= 0) {
                gain = fadeTo;
                applyVolume();
                return;
            }
            fadeStepsLeft--;
            float t = 1f - (fadeStepsLeft / 10f);
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;
            gain = fadeFrom + (fadeTo - fadeFrom) * t;
            applyVolume();
            if (fadeStepsLeft > 0) audio.postDelayed(this, 40);
        }
    };

    public MixDeck(Context ignored) {
        this.audio = SolarTransport.get().audioHandler();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public File getPath() {
        return path;
    }

    public float getGain() {
        return gain;
    }

    public float getRate() {
        return rate;
    }

    public boolean isPrepared() {
        return prepared;
    }

    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    /** Load file at rate (1.0 = native). 2026-07-19 */
    public void load(File track, float playbackRate) throws IOException {
        releasePlayerOnly();
        prepared = false;
        started = false;
        if (track == null || !track.isFile()) throw new IOException("Mix deck missing file");
        path = track;
        rate = playbackRate > 0.1f ? playbackRate : 1f;
        gain = 0f;
        player = SolarIjkPlayerFactory.create();
        StemSoundTouch.applyStemPlayerOptions(player);
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        player.setDataSource(track.getAbsolutePath());
        final float speed = rate;
        player.setOnPreparedListener(new tv.danmaku.ijk.media.player.IMediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(tv.danmaku.ijk.media.player.IMediaPlayer mp) {
                prepared = true;
                try {
                    player.setSpeed(speed);
                } catch (Exception ignored) {}
                applyVolume();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onReady(MixDeck.this);
                    }
                });
            }
        });
        player.setOnCompletionListener(new tv.danmaku.ijk.media.player.IMediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(tv.danmaku.ijk.media.player.IMediaPlayer mp) {
                if (released) return;
                // Host decides: queue advance soft-replace vs restart. Was: always loop here.
                // Reversal: seekTo(0); start(); then onComplete.
                // 2026-07-21
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onComplete(MixDeck.this);
                    }
                });
            }
        });
        player.setOnErrorListener(new tv.danmaku.ijk.media.player.IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(tv.danmaku.ijk.media.player.IMediaPlayer mp, int what, int extra) {
                if (listener != null) {
                    final String msg = "Mix error " + what + "/" + extra;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) listener.onError(MixDeck.this, msg);
                        }
                    });
                }
                return true;
            }
        });
        player.prepareAsync();
    }

    public void play() {
        if (released || player == null) return;
        try {
            player.seekTo(0);
            player.start();
            started = true;
        } catch (Exception e) {
            if (listener != null) listener.onError(this, e.getMessage());
        }
    }

    public void pause() {
        try {
            if (player != null && player.isPlaying()) player.pause();
        } catch (Exception ignored) {}
    }

    public void resume() {
        try {
            if (player != null) {
                player.start();
                started = true;
            }
        } catch (Exception ignored) {}
    }

    public void setGain(float g) {
        audio.removeCallbacks(fadeTick);
        fadeStepsLeft = 0;
        gain = g < 0f ? 0f : (g > 1f ? 1f : g);
        applyVolume();
    }

    public float nudgeGain(float delta) {
        setGain(gain + delta);
        return gain;
    }

    /** Smooth fade to target (~400ms) then optional callback on UI thread. 2026-07-19 */
    public void fadeTo(float target, final Runnable onDone) {
        audio.removeCallbacks(fadeTick);
        fadeFrom = gain;
        fadeTo = target < 0f ? 0f : (target > 1f ? 1f : target);
        fadeStepsLeft = 10;
        audio.post(new Runnable() {
            @Override
            public void run() {
                if (released) {
                    if (onDone != null) ui.post(onDone);
                    return;
                }
                if (fadeStepsLeft <= 0) {
                    gain = fadeTo;
                    applyVolume();
                    if (onDone != null) ui.post(onDone);
                    return;
                }
                fadeStepsLeft--;
                float t = 1f - (fadeStepsLeft / 10f);
                if (t < 0f) t = 0f;
                if (t > 1f) t = 1f;
                gain = fadeFrom + (fadeTo - fadeFrom) * t;
                applyVolume();
                audio.postDelayed(this, 40);
            }
        });
    }

    public int getPositionMs() {
        try {
            return player != null ? (int) player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDurationMs() {
        try {
            return player != null ? (int) player.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public void seekTo(int ms) {
        if (player == null) return;
        try {
            int dur = getDurationMs();
            int p = ms;
            if (dur > 0) {
                if (p < 0) p = 0;
                if (p >= dur) p = 0;
            } else if (p < 0) {
                p = 0;
            }
            player.seekTo(p);
            if (started && !player.isPlaying()) player.start();
        } catch (Exception ignored) {}
    }

    public void setRate(float playbackRate) {
        rate = playbackRate > 0.1f ? playbackRate : 1f;
        if (player == null) return;
        try {
            player.setSpeed(rate);
        } catch (Exception ignored) {}
    }

    public void setPitch(float pitchFactor) {
        if (player == null) return;
        try {
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch-pitch", String.valueOf(pitchFactor));
        } catch (Exception ignored) {}
    }

    public void release() {
        released = true;
        audio.removeCallbacks(fadeTick);
        releasePlayerOnly();
        listener = null;
    }

    private void applyVolume() {
        if (player == null) return;
        try {
            // Volume-only mute — keep playhead (StemPadMutePolicy lockstep). 2026-07-21
            // Was: pause when silent + start on unmute. Reversal: restore pause/start.
            boolean silent = gain <= 0.001f;
            player.setVolume(silent ? 0f : gain, silent ? 0f : gain);
            if (com.solar.launcher.stem.StemPadMutePolicy.shouldPauseWhenSilent()) {
                if (silent) {
                    if (player.isPlaying()) player.pause();
                } else if (started && !released && !player.isPlaying()) {
                    player.start();
                }
            }
        } catch (Exception ignored) {}
    }

    private void releasePlayerOnly() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        prepared = false;
        started = false;
    }
}
