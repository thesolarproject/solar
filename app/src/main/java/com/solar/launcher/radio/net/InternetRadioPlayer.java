package com.solar.launcher.radio.net;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.DebugAgentLog;
import com.solar.launcher.net.SolarHttp;
import com.solar.launcher.radio.RadioRingBuffer;
import com.solar.launcher.radio.RadioSettings;

import java.io.IOException;
import java.io.InputStream;

/**
 * Internet radio stream player with optional TiVo-style ring capture.
 * ponytail: parallel HTTP tee into {@link RadioRingBuffer} — single-pipe proxy is future work.
 */
public final class InternetRadioPlayer implements MediaPlayer.OnErrorListener,
    MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener {
  public interface Listener {
    void onPrepared();

    void onPlaying();

    void onStopped();

    void onError(String reason);
  }

  private final Context appCtx;
  private final Handler mainHandler;
  private MediaPlayer player;
  private RadioRingBuffer ringBuffer;
  private Thread captureThread;
  private volatile boolean captureRunning;
  private String currentUrl;
  private Listener listener;

  public InternetRadioPlayer(Context ctx) {
    appCtx = ctx.getApplicationContext();
    mainHandler = new Handler(Looper.getMainLooper());
  }

  public synchronized boolean isPlaying() {
    try {
      return player != null && player.isPlaying();
    } catch (IllegalStateException e) {
      return false;
    }
  }

  public synchronized String getCurrentUrl() {
    return currentUrl;
  }

  public RadioRingBuffer getRingBuffer() {
    return ringBuffer;
  }

  public synchronized void setListener(Listener l) {
    listener = l;
  }

  public synchronized void play(String streamUrl) throws IOException {
    stop();
    if (streamUrl == null || streamUrl.trim().isEmpty()) {
      throw new IOException("Empty stream URL");
    }
    currentUrl = streamUrl.trim();
    boolean onSd = RadioSettings.getBufferOnSd(appCtx);
    try {
      ringBuffer = new RadioRingBuffer(appCtx, onSd);
    } catch (IOException e) {
      ringBuffer = null;
      // #region agent log
      try {
        DebugAgentLog.log(
            appCtx,
            "InternetRadioPlayer.play",
            "ring buffer skipped",
            "E",
            new org.json.JSONObject().put("err", e.getClass().getSimpleName()));
      } catch (Exception ignored) {}
      // #endregion
    }
    player = new MediaPlayer();
    player.setAudioStreamType(AudioManager.STREAM_MUSIC);
    player.setOnPreparedListener(this);
    player.setOnErrorListener(this);
    player.setOnCompletionListener(this);
    player.setDataSource(currentUrl);
    player.prepareAsync();
    startCapture(currentUrl);
  }

  public synchronized void pause() {
    if (player == null) return;
    try {
      if (player.isPlaying()) player.pause();
    } catch (IllegalStateException ignored) {}
  }

  public synchronized void resume() {
    if (player == null) return;
    try {
      if (!player.isPlaying()) player.start();
      notifyPlaying();
    } catch (IllegalStateException e) {
      notifyError("resume failed");
    }
  }

  public synchronized void stop() {
    stopCapture();
    if (player != null) {
      try {
        player.stop();
      } catch (IllegalStateException ignored) {}
      player.release();
      player = null;
    }
    if (ringBuffer != null) {
      ringBuffer.close();
      ringBuffer = null;
    }
    currentUrl = null;
    notifyStopped();
  }

  /** Seek within ring buffer timeline for rewind playback. ponytail: stub — live stream only for now. */
  public synchronized boolean seekBufferedMs(long offsetMs) {
    if (ringBuffer == null) return false;
    long dur = ringBuffer.getBufferedDurationMs();
    return offsetMs >= 0 && offsetMs < dur;
  }

  public synchronized long getBufferedDurationMs() {
    return ringBuffer != null ? ringBuffer.getBufferedDurationMs() : 0L;
  }

  public synchronized long getLivePositionMs() {
    return ringBuffer != null ? ringBuffer.getLivePositionMs() : 0L;
  }

  @Override
  public void onPrepared(MediaPlayer mp) {
    // #region agent log
    try {
      DebugAgentLog.log(
          appCtx,
          "InternetRadioPlayer.onPrepared",
          "ready",
          "E",
          new org.json.JSONObject().put("url", currentUrl));
    } catch (Exception ignored) {}
    // #endregion
    try {
      mp.start();
      notifyPlaying();
      notifyPrepared();
    } catch (IllegalStateException e) {
      notifyError("start failed");
    }
  }

  @Override
  public boolean onError(MediaPlayer mp, int what, int extra) {
    notifyError("MediaPlayer error " + what + "/" + extra);
    return true;
  }

  @Override
  public void onCompletion(MediaPlayer mp) {
    notifyStopped();
  }

  private void startCapture(final String url) {
    captureRunning = true;
    captureThread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                captureLoop(url);
              }
            },
            "InternetRadioCapture");
    captureThread.start();
  }

  private void stopCapture() {
    captureRunning = false;
    if (captureThread != null) {
      try {
        captureThread.interrupt();
        captureThread.join(1500L);
      } catch (InterruptedException ignored) {}
      captureThread = null;
    }
  }

  private void captureLoop(String url) {
    InputStream in = null;
    byte[] buf = new byte[8192];
    try {
      byte[] head = SolarHttp.getBytes(url, "audio/*", "SolarRadioCapture/1.0");
      if (head != null && head.length > 0 && ringBuffer != null) {
        ringBuffer.write(head, 0, head.length);
      }
      // ponytail: full stream tee needs ranged/chunked reader — partial capture seeds buffer
    } catch (Exception ignored) {
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException ignored) {}
      }
    }
    while (captureRunning && !Thread.currentThread().isInterrupted()) {
      try {
        Thread.sleep(500L);
      } catch (InterruptedException e) {
        break;
      }
    }
  }

  private void notifyPrepared() {
    final Listener l = listener;
    if (l == null) return;
    mainHandler.post(
        new Runnable() {
          @Override
          public void run() {
            l.onPrepared();
          }
        });
  }

  private void notifyPlaying() {
    final Listener l = listener;
    if (l == null) return;
    mainHandler.post(
        new Runnable() {
          @Override
          public void run() {
            l.onPlaying();
          }
        });
  }

  private void notifyStopped() {
    final Listener l = listener;
    if (l == null) return;
    mainHandler.post(
        new Runnable() {
          @Override
          public void run() {
            l.onStopped();
          }
        });
  }

  private void notifyError(final String reason) {
    final Listener l = listener;
    if (l == null) return;
    mainHandler.post(
        new Runnable() {
          @Override
          public void run() {
            l.onError(reason);
          }
        });
  }
}
