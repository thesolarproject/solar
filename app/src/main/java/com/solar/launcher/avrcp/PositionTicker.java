package com.solar.launcher.avrcp;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

/** ~1 Hz position updates for AVRCP PLAYBACK_POS_CHANGED. */
final class PositionTicker implements Runnable {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;

    void start(MediaPlayer mp) {
        player = mp;
        handler.removeCallbacks(this);
        handler.postDelayed(this, 1000);
    }

    void stop() {
        player = null;
        handler.removeCallbacks(this);
    }

    @Override
    public void run() {
        MediaPlayer mp = player;
        if (mp == null) return;
        try {
            if (mp.isPlaying()) {
                TrackInfoWriter.INSTANCE.resetWakeRateLimit();
                TrackInfoWriter.INSTANCE.updatePosition(mp.getCurrentPosition());
                handler.postDelayed(this, 1000);
            }
        } catch (Throwable ignored) {
            stop();
        }
    }
}
