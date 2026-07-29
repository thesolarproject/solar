package com.solar.launcher.video;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * Local files → IJK; progressive HTTP → MediaPlayer (notPipe VideoView path).
 * 2026-07-16 — Fix Y1 black screen: wait for valid surface; Context+Uri setDataSource;
 * software IJK fallback; host may download-then-open(file) if stream still fails.
 */
public final class VideoPlayerController implements SurfaceHolder.Callback,
        IMediaPlayer.OnPreparedListener,
        IMediaPlayer.OnErrorListener,
        IMediaPlayer.OnCompletionListener,
        IMediaPlayer.OnVideoSizeChangedListener,
        IMediaPlayer.OnBufferingUpdateListener,
        IMediaPlayer.OnSeekCompleteListener,
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnVideoSizeChangedListener,
        MediaPlayer.OnBufferingUpdateListener,
        MediaPlayer.OnSeekCompleteListener {

    private static final String TAG = "SolarVideoPlayer";

    public interface PlaybackListener {
        void onError(int what, int extra);
        void onCompletion();
        void onVideoSize(int width, int height);
    }

    public interface BufferingListener {
        void onBuffering(int percent);
        void onReadyToPlay();
    }

    public interface SeekListener {
        void onSeekComplete(long positionMs);
    }

    private final Context appCtx;
    private final IjkMediaPlayer ijkPlayer;
    private MediaPlayer mediaPlayer;
    private boolean useMediaPlayer;
    private SurfaceHolder boundHolder;
    private boolean prepared;
    private boolean playWhenSurfaceReady;
    private boolean pausedForSurfaceLoss;
    private String pendingPath;
    private PlaybackListener playbackListener;
    private BufferingListener bufferingListener;
    private SeekListener seekListener;
    private boolean released;
    private boolean triedIjkFallback;
    private boolean preparePosted;

    public VideoPlayerController(Context context) {
        appCtx = context != null ? context.getApplicationContext() : null;
        ijkPlayer = SolarIjkPlayerFactory.create();
        wireIjkListeners();
    }

    /** @deprecated use {@link #VideoPlayerController(Context)} */
    public VideoPlayerController() {
        this(null);
    }

    public void setPlaybackListener(PlaybackListener listener) {
        playbackListener = listener;
    }

    public void setBufferingListener(BufferingListener listener) {
        bufferingListener = listener;
    }

    public void setSeekListener(SeekListener listener) {
        seekListener = listener;
    }

    public IjkMediaPlayer getPlayer() {
        return ijkPlayer;
    }

    /**
     * Local file — prefer stock MediaPlayer (notPipe VideoView path). IJK HW often
     * black-screens progressive YouTube MP4 on MT6572 even when the file is valid.
     */
    public void open(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("no file");
        useMediaPlayer = true;
        triedIjkFallback = false;
        preparePosted = false;
        resetForNewSource();
        pendingPath = file.getAbsolutePath();
        Log.i(TAG, "open file mediaPlayer=true " + pendingPath
                + " bytes=" + file.length());
        ensureMediaPlayer();
        setMediaDataSource(pendingPath);
        maybePrepare();
    }

    /**
     * Progressive HTTP — MediaPlayer with Context+Uri (API 17 safe), after surface is valid.
     * Prefer download-then-open(file) for YouTube: MediaPlayer HTTPS TLS is weak on API 17.
     */
    public void openUrl(String url) throws IOException {
        if (url == null || url.isEmpty()) throw new IOException("no url");
        boolean isProxyOrYt = url.contains("/stream?url=") || url.contains("googlevideo.com") || url.contains("piped");
        useMediaPlayer = isHttpUrl(url) && !isProxyOrYt;
        triedIjkFallback = !useMediaPlayer;
        preparePosted = false;
        resetForNewSource();
        pendingPath = url;
        Log.i(TAG, "openUrl mediaPlayer=" + useMediaPlayer + " "
                + (url.length() > 120 ? url.substring(0, 120) : url));
        if (useMediaPlayer) {
            ensureMediaPlayer();
            setMediaDataSource(url);
        } else {
            ijkPlayer.setDataSource(url);
        }
        maybePrepare();
    }

    private void setMediaDataSource(String pathOrUrl) throws IOException {
        if (mediaPlayer == null) ensureMediaPlayer();
        // Local absolute path — plain setDataSource(path) is most reliable on API 17.
        if (pathOrUrl != null && pathOrUrl.startsWith("/") && !isHttpUrl(pathOrUrl)) {
            mediaPlayer.setDataSource(pathOrUrl);
            return;
        }
        try {
            if (appCtx != null) {
                Map<String, String> headers = new HashMap<String, String>();
                headers.put("User-Agent",
                        "Mozilla/5.0 (Linux; Android 4.2) AppleWebKit/537.36");
                mediaPlayer.setDataSource(appCtx, Uri.parse(pathOrUrl), headers);
            } else {
                mediaPlayer.setDataSource(pathOrUrl);
            }
        } catch (IOException e) {
            // Older devices sometimes only accept plain string
            mediaPlayer.setDataSource(pathOrUrl);
        }
    }

    public boolean isPrepared() {
        return prepared;
    }

    public boolean isPlaying() {
        try {
            if (!prepared) return false;
            if (useMediaPlayer && mediaPlayer != null) return mediaPlayer.isPlaying();
            return ijkPlayer.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public long getCurrentPosition() {
        try {
            if (!prepared) return 0L;
            if (useMediaPlayer && mediaPlayer != null) return mediaPlayer.getCurrentPosition();
            return ijkPlayer.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0L;
        }
    }

    public long getDuration() {
        try {
            if (!prepared) return 0L;
            if (useMediaPlayer && mediaPlayer != null) return mediaPlayer.getDuration();
            return ijkPlayer.getDuration();
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
            if (useMediaPlayer && mediaPlayer != null) mediaPlayer.start();
            else ijkPlayer.start();
            playWhenSurfaceReady = false;
            pausedForSurfaceLoss = false;
        } catch (IllegalStateException e) {
            Log.e(TAG, "start failed", e);
        }
    }

    public void pause() {
        playWhenSurfaceReady = false;
        if (!prepared) return;
        try {
            if (useMediaPlayer && mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            } else if (ijkPlayer.isPlaying()) {
                ijkPlayer.pause();
            }
        } catch (IllegalStateException ignored) {}
    }

    public void togglePlayPause() {
        if (isPlaying()) pause();
        else play();
    }

    /**
     * Submit the target directly to the active engine. Network players own their Range/buffering
     * behavior; gating this call on a buffering percentage can leave a seek pending forever.
     */
    public boolean seekTo(long positionMs) {
        if (!prepared) return false;
        long targetMs = VideoSeekPolicy.clampTarget(positionMs, getDuration());
        try {
            if (useMediaPlayer && mediaPlayer != null) {
                mediaPlayer.seekTo((int) Math.min(Integer.MAX_VALUE, targetMs));
            } else {
                ijkPlayer.seekTo(targetMs);
            }
            Log.i(TAG, "seek submitted targetMs=" + targetMs
                    + " engine=" + (useMediaPlayer ? "mediaplayer" : "ijk"));
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "seek failed targetMs=" + targetMs, e);
            return false;
        }
    }

    public void attachHolder(SurfaceHolder holder) {
        if (holder == null) return;
        holder.addCallback(this);
        boundHolder = holder;
        if (hasValidSurface()) {
            applyDisplay(holder);
            maybePrepare();
        }
    }

    public void detachHolder(SurfaceHolder holder) {
        if (holder != null) holder.removeCallback(this);
        if (boundHolder == holder) boundHolder = null;
    }

    public void release() {
        released = true;
        playWhenSurfaceReady = false;
        pausedForSurfaceLoss = false;
        prepared = false;
        pendingPath = null;
        playbackListener = null;
        bufferingListener = null;
        seekListener = null;
        releaseMediaPlayer();
        try {
            ijkPlayer.stop();
        } catch (IllegalStateException ignored) {}
        try {
            ijkPlayer.setDisplay(null);
        } catch (IllegalStateException ignored) {}
        ijkPlayer.release();
    }

    @Override
    public void onPrepared(IMediaPlayer mp) {
        onEnginePrepared("ijk");
    }

    @Override
    public boolean onError(IMediaPlayer mp, int what, int extra) {
        return onEngineError(what, extra, true);
    }

    @Override
    public void onCompletion(IMediaPlayer mp) {
        onEngineCompletion();
    }

    @Override
    public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sarNum, int sarDen) {
        onEngineVideoSize(width, height);
    }

    @Override
    public void onBufferingUpdate(IMediaPlayer mp, int percent) {
        onEngineBuffering(percent);
    }

    @Override
    public void onSeekComplete(IMediaPlayer mp) {
        onEngineSeekComplete();
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        onEnginePrepared("mediaplayer");
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        return onEngineError(what, extra, false);
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        onEngineCompletion();
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        onEngineVideoSize(width, height);
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        onEngineBuffering(percent);
    }

    @Override
    public void onSeekComplete(MediaPlayer mp) {
        onEngineSeekComplete();
    }

    private void onEnginePrepared(String eng) {
        if (released) return;
        prepared = true;
        Log.i(TAG, "prepared ok eng=" + eng);
        if (playWhenSurfaceReady) play();
        BufferingListener bl = bufferingListener;
        if (bl != null) bl.onReadyToPlay();
    }

    private boolean onEngineError(int what, int extra, boolean fromIjk) {
        if (released) return true;
        prepared = false;
        playWhenSurfaceReady = false;
        Log.e(TAG, "error what=" + what + " extra=" + extra + " ijk=" + fromIjk
                + " path=" + pendingPath);
        // MediaPlayer failed (HTTP or local) → IJK software decode once.
        if (!fromIjk && !triedIjkFallback && pendingPath != null) {
            triedIjkFallback = true;
            try {
                Log.i(TAG, "MediaPlayer failed — IJK software decode retry");
                useMediaPlayer = false;
                releaseMediaPlayer();
                resetIjkOnly();
                ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
                ijkPlayer.setDataSource(pendingPath);
                applyDisplay(boundHolder);
                preparePosted = false;
                maybePrepare();
                return true;
            } catch (Exception e) {
                Log.e(TAG, "IJK fallback failed", e);
            }
        }
        PlaybackListener l = playbackListener;
        if (l != null) l.onError(what, extra);
        return true;
    }

    private void onEngineCompletion() {
        if (released) return;
        PlaybackListener l = playbackListener;
        if (l != null) l.onCompletion();
    }

    private void onEngineVideoSize(int width, int height) {
        if (released) return;
        PlaybackListener l = playbackListener;
        if (l != null) l.onVideoSize(width, height);
    }

    private void onEngineBuffering(int percent) {
        if (released) return;
        BufferingListener bl = bufferingListener;
        if (bl != null) bl.onBuffering(percent);
    }

    private void onEngineSeekComplete() {
        if (released) return;
        long positionMs = getCurrentPosition();
        Log.i(TAG, "seek complete positionMs=" + positionMs);
        SeekListener listener = seekListener;
        if (listener != null) listener.onSeekComplete(positionMs);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (released) return;
        Log.i(TAG, "surfaceCreated");
        boundHolder = holder;
        applyDisplay(holder);
        preparePosted = false;
        maybePrepare();
        if (pausedForSurfaceLoss && prepared) {
            pausedForSurfaceLoss = false;
            play();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (released) return;
        boundHolder = holder;
        applyDisplay(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (released) return;
        Log.i(TAG, "surfaceDestroyed");
        if (prepared) {
            try {
                if (isPlaying()) {
                    pause();
                    pausedForSurfaceLoss = true;
                }
            } catch (Exception ignored) {}
        }
        try {
            if (useMediaPlayer && mediaPlayer != null) mediaPlayer.setDisplay(null);
            else ijkPlayer.setDisplay(null);
        } catch (Exception ignored) {}
        if (boundHolder == holder) boundHolder = null;
        preparePosted = false;
    }

    private void maybePrepare() {
        if (released || prepared || pendingPath == null) return;
        if (!hasValidSurface()) {
            Log.i(TAG, "wait for valid surface");
            return;
        }
        if (preparePosted) return;
        preparePosted = true;
        applyDisplay(boundHolder);
        if (useMediaPlayer && mediaPlayer != null) {
            // notPipe: prepare off UI thread to avoid 4.2 hang
            final MediaPlayer mp = mediaPlayer;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (released) return;
                    try {
                        Log.i(TAG, "MediaPlayer.prepareAsync");
                        mp.prepareAsync();
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "prepareAsync ISE", e);
                        preparePosted = false;
                        hostError(1, -1);
                    }
                }
            }, "SolarMpPrepare").start();
            return;
        }
        try {
            Log.i(TAG, "IJK.prepareAsync");
            ijkPlayer.prepareAsync();
        } catch (IllegalStateException e) {
            preparePosted = false;
            Log.e(TAG, "IJK prepareAsync", e);
        }
    }

    private void hostError(final int what, final int extra) {
        PlaybackListener l = playbackListener;
        if (l != null) l.onError(what, extra);
    }

    private boolean hasValidSurface() {
        if (boundHolder == null) return false;
        try {
            return boundHolder.getSurface() != null && boundHolder.getSurface().isValid();
        } catch (Exception e) {
            return false;
        }
    }

    private void resetForNewSource() {
        prepared = false;
        playWhenSurfaceReady = false;
        pausedForSurfaceLoss = false;
        preparePosted = false;
        if (useMediaPlayer) {
            releaseMediaPlayer();
            ensureMediaPlayer();
        } else {
            resetIjkOnly();
        }
    }

    private void resetIjkOnly() {
        try {
            ijkPlayer.reset();
            SolarIjkPlayerFactory.applyY1Options(ijkPlayer);
            if (pendingPath != null && isHttpUrl(pendingPath)) {
                ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
            }
            wireIjkListeners();
        } catch (IllegalStateException ignored) {}
    }

    private void ensureMediaPlayer() {
        if (mediaPlayer != null) return;
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnErrorListener(this);
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnVideoSizeChangedListener(this);
        mediaPlayer.setOnBufferingUpdateListener(this);
        mediaPlayer.setOnSeekCompleteListener(this);
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setScreenOnWhilePlaying(true);
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.reset();
        } catch (Exception ignored) {}
        try {
            mediaPlayer.release();
        } catch (Exception ignored) {}
        mediaPlayer = null;
    }

    private void applyDisplay(SurfaceHolder holder) {
        if (holder == null || !hasValidSurface()) return;
        try {
            if (useMediaPlayer && mediaPlayer != null) mediaPlayer.setDisplay(holder);
            else ijkPlayer.setDisplay(holder);
        } catch (Exception e) {
            Log.w(TAG, "setDisplay", e);
        }
    }

    private void wireIjkListeners() {
        ijkPlayer.setOnPreparedListener(this);
        ijkPlayer.setOnErrorListener(this);
        ijkPlayer.setOnCompletionListener(this);
        ijkPlayer.setOnVideoSizeChangedListener(this);
        ijkPlayer.setOnBufferingUpdateListener(this);
        ijkPlayer.setOnSeekCompleteListener(this);
    }

    private static boolean isHttpUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }
}
