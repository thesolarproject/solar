package com.solar.launcher;

import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.KeyEvent;

/** API 21+ only — loaded via reflection so API 17 never links MediaSession at startup. */
final class MediaSessionShim {
  private final MediaSession session;

  private MediaSessionShim(MainActivity activity) {
    session = new MediaSession(activity, "SOLAR_MEDIA_SESSION");
    session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    session.setCallback(new MediaSession.Callback() {
      @Override
      public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
        KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
          if (activity.handleMediaSessionKey(event.getKeyCode())) return true;
        }
        return super.onMediaButtonEvent(mediaButtonIntent);
      }
    });
    session.setActive(true);
  }

  static MediaSessionShim create(MainActivity activity) {
    return new MediaSessionShim(activity);
  }

  void setPlaying(int positionMs) {
    session.setPlaybackState(new PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        .setState(PlaybackState.STATE_PLAYING, positionMs, 1.0f)
        .build());
  }

  void setPaused(int positionMs) {
    session.setPlaybackState(new PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        .setState(PlaybackState.STATE_PAUSED, positionMs, 1.0f)
        .build());
  }

  void release() {
    session.release();
  }
}
