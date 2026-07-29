package com.solar.launcher.audio;

import android.os.Handler;
import com.solar.launcher.stem.StemControls;
import java.io.File;
import java.io.IOException;

public final class TransportDeck {
    public interface Listener {
        void onReady(TransportDeck deck);
        void onComplete(TransportDeck deck);
        void onError(TransportDeck deck, String message);
    }

    private final Handler audio;
    private final com.solar.launcher.stem.StemMixer mixer;
    private Listener listener;
    private float gain = 1f;
    private boolean started;
    private boolean released;
    private boolean prepared;
    private File path;
    private String url;
    private float currentSpeed = 1.0f;
    private int fadeStepsLeft;
    private float fadeFrom;
    private float fadeTo;
    private Runnable fadeOnDone;
    private final Runnable fadeTick = new Runnable() {
        @Override
        public void run() {
            if (released) {
                if (fadeOnDone != null) {
                    Runnable d = fadeOnDone;
                    fadeOnDone = null;
                    d.run();
                }
                return;
            }
            if (fadeStepsLeft <= 0) {
                gain = fadeTo;
                applyVolume();
                Runnable d = fadeOnDone;
                fadeOnDone = null;
                if (d != null) d.run();
                return;
            }
            fadeStepsLeft--;
            int done = 10 - fadeStepsLeft;
            gain = StemControls.fadeGainStep(fadeFrom, fadeTo, done, 10);
            applyVolume();
            audio.postDelayed(this, 40L);
        }
    };

    public TransportDeck(Handler audioLooperHandler) {
        this.audio = audioLooperHandler;
        this.mixer = new com.solar.launcher.stem.StemMixer(com.solar.launcher.SolarApplication.getAppContext());
        this.mixer.setListener(new com.solar.launcher.stem.StemMixer.Listener() {
            @Override
            public void onReady() {
                prepared = true;
                applyVolume();
                postReady();
            }

            @Override
            public void onError(String message) {
                if (listener != null) listener.onError(TransportDeck.this, message);
            }

            @Override
            public void onComplete() {
                if (released) return;
                if (listener != null) listener.onComplete(TransportDeck.this);
            }
        });
    }

    private void postReady() {
        if (listener != null) listener.onReady(TransportDeck.this);
    }

    public com.solar.launcher.stem.StemMixer getMixer() {
        return mixer;
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

    public boolean isPrepared() {
        return prepared;
    }

    public boolean isPlaying() {
        try {
            return mixer != null && mixer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public void loadFile(File track, boolean preferIjk) throws IOException {
        prepared = false;
        started = false;
        url = null;
        if (track == null || !track.isFile()) throw new IOException("TransportDeck missing file");
        path = track;
        mixer.loadOrigin(track.getAbsolutePath(), preferIjk);
    }

    public void loadFile(File track) throws IOException {
        loadFile(track, false);
    }

    public void loadUrl(String sourceUrl) throws IOException {
        prepared = false;
        started = false;
        path = null;
        if (sourceUrl == null || sourceUrl.length() == 0) {
            throw new IOException("TransportDeck missing url");
        }
        url = sourceUrl;
        mixer.loadOrigin(sourceUrl);
    }

    public void start() {
        if (released || !prepared) return;
        started = true;
        try {
            mixer.resume();
        } catch (Exception ignored) {}
    }

    public void pause() {
        if (released) return;
        started = false;
        try {
            mixer.pause();
        } catch (Exception ignored) {}
    }

    public void setGainImmediate(float target) {
        audio.removeCallbacks(fadeTick);
        gain = StemControls.clampGain(target);
        applyVolume();
    }

    public void fadeTo(float target, Runnable onDone) {
        audio.removeCallbacks(fadeTick);
        fadeFrom = gain;
        fadeTo = StemControls.clampGain(target);
        fadeStepsLeft = 10;
        fadeOnDone = onDone;
        audio.post(fadeTick);
    }

    public int getPositionMs() {
        try {
            if (prepared) return mixer.getPositionMs();
        } catch (Exception ignored) {}
        return 0;
    }

    public int getDurationMs() {
        try {
            if (prepared) return mixer.getDurationMs();
        } catch (Exception ignored) {}
        return 0;
    }

    public void seekTo(int ms) {
        if (released || !prepared) return;
        try {
            mixer.seekTo(ms);
        } catch (Exception ignored) {}
    }

    public boolean attachNextMediaPlayer(TransportDeck next) {
        // Gapless MediaPlayer attaching not easily supported with unified mixer currently.
        return false;
    }

    public void release() {
        released = true;
        audio.removeCallbacks(fadeTick);
        releasePlayerOnly();
        listener = null;
    }

    private void applyVolume() {
        if (released) return;
        try {
            mixer.setMasterGain(gain);
        } catch (Exception ignored) {}
    }

    public void detachNext() {
    }

    private void releasePlayerOnly() {
        try { mixer.release(); } catch (Exception ignored) {}
    }

    public void setSpeed(float speed) {
        if (released) return;
        currentSpeed = speed;
    }
}
