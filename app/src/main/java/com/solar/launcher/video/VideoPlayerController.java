package com.solar.launcher.video;

import android.view.SurfaceHolder;

import java.io.File;
import java.io.IOException;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * Owns one {@link IjkMediaPlayer}, binds {@link SurfaceHolder} lifecycle, play/pause/seek/release.
 * Call {@link #attachHolder(SurfaceHolder)} from a {@link SurfaceRenderView} or SurfaceView host.
 */
public final class VideoPlayerController implements SurfaceHolder.Callback, IMediaPlayer.OnPreparedListener {
    private final IjkMediaPlayer player;
    private SurfaceHolder boundHolder;
    private boolean prepared;
    private boolean playWhenSurfaceReady;
    private boolean pausedForSurfaceLoss;
    private String pendingPath;

    public VideoPlayerController() {
        player = SolarIjkPlayerFactory.create();
        player.setOnPreparedListener(this);
    }

    public IjkMediaPlayer getPlayer() {
        return player;
    }

    /** Open a local file; prepares async once a surface is available. */
    public void open(File file) throws IOException {
        if (file == null) throw new IOException("no file");
        resetForNewSource();
        pendingPath = file.getAbsolutePath();
        player.setDataSource(pendingPath);
        maybePrepare();
    }

    public boolean isPrepared() {
        return prepared;
    }

    public boolean isPlaying() {
        try {
            return prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public long getCurrentPosition() {
        try {
            return prepared ? player.getCurrentPosition() : 0L;
        } catch (IllegalStateException e) {
            return 0L;
        }
    }

    public long getDuration() {
        try {
            return prepared ? player.getDuration() : 0L;
        } catch (IllegalStateException e) {
            return 0L;
        }
    }

    public void play() {
        if (!prepared) {
            playWhenSurfaceReady = true;
            return;
        }
        try {
            player.start();
            playWhenSurfaceReady = false;
            pausedForSurfaceLoss = false;
        } catch (IllegalStateException ignored) {}
    }

    public void pause() {
        playWhenSurfaceReady = false;
        if (!prepared) return;
        try {
            if (player.isPlaying()) player.pause();
        } catch (IllegalStateException ignored) {}
    }

    public void togglePlayPause() {
        if (isPlaying()) pause();
        else play();
    }

    public void seekTo(long positionMs) {
        if (!prepared) return;
        try {
            player.seekTo(positionMs);
        } catch (IllegalStateException ignored) {}
    }

    /** Wire surface callbacks from the hosting view. */
    public void attachHolder(SurfaceHolder holder) {
        if (holder == null) return;
        holder.addCallback(this);
        boundHolder = holder;
        player.setDisplay(holder);
        maybePrepare();
    }

    public void detachHolder(SurfaceHolder holder) {
        if (holder != null) holder.removeCallback(this);
        if (boundHolder == holder) boundHolder = null;
    }

    /** Stop playback and release native player — call on Back / screen exit. */
    public void release() {
        playWhenSurfaceReady = false;
        pausedForSurfaceLoss = false;
        prepared = false;
        pendingPath = null;
        try {
            player.stop();
        } catch (IllegalStateException ignored) {}
        try {
            player.setDisplay(null);
        } catch (IllegalStateException ignored) {}
        player.release();
    }

    @Override
    public void onPrepared(IMediaPlayer mp) {
        prepared = true;
        if (playWhenSurfaceReady) play();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        boundHolder = holder;
        player.setDisplay(holder);
        maybePrepare();
        if (pausedForSurfaceLoss && prepared) {
            pausedForSurfaceLoss = false;
            play();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        boundHolder = holder;
        player.setDisplay(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (prepared) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                    pausedForSurfaceLoss = true;
                }
            } catch (IllegalStateException ignored) {}
        }
        player.setDisplay(null);
        if (boundHolder == holder) boundHolder = null;
    }

    private void maybePrepare() {
        if (prepared || pendingPath == null || boundHolder == null) return;
        try {
            player.prepareAsync();
        } catch (IllegalStateException ignored) {}
    }

    private void resetForNewSource() {
        prepared = false;
        playWhenSurfaceReady = false;
        pausedForSurfaceLoss = false;
        try {
            player.reset();
            SolarIjkPlayerFactory.applyY1Options(player);
            player.setOnPreparedListener(this);
        } catch (IllegalStateException ignored) {}
    }
}
