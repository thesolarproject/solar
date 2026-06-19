package com.solar.launcher.avrcp;

import android.content.Context;
import android.media.MediaPlayer;

/** Hooks Solar playback into Koensayr y1-track-info + wake broadcasts. */
public final class PlaybackStateBridge {
    private static PlaybackStateBridge instance;
    private final PositionTicker positionTicker = new PositionTicker();

    private PlaybackStateBridge() {}

    public static PlaybackStateBridge get() {
        if (instance == null) instance = new PlaybackStateBridge();
        return instance;
    }

    public void init(Context context, PappStateBroadcaster.Listener pappListener) {
        TrackInfoWriter.INSTANCE.init(context);
        PappStateBroadcaster.register(context, pappListener);
        BatteryReceiver.register(context);
    }

    public void syncFromPlayer(MediaPlayer player, String title, String artist, String album,
            String path, boolean podcastActive) {
        if (podcastActive) {
            syncPodcast(title, artist, album, path, player);
            return;
        }
        long dur = 0;
        int pos = 0;
        byte status = 0;
        try {
            if (player != null) {
                dur = player.getDuration();
                pos = player.getCurrentPosition();
                status = player.isPlaying() ? (byte) 1 : (byte) 2;
            }
        } catch (Throwable ignored) {}
        TrackInfoWriter w = TrackInfoWriter.INSTANCE;
        w.setTrack(title, artist, album, path, "", dur, 0);
        w.setPlayStatus(status, pos);
        if (status == 1) positionTicker.start(player);
        else positionTicker.stop();
    }

    private void syncPodcast(String title, String artist, String album, String path, MediaPlayer player) {
        long dur = 0;
        int pos = 0;
        byte status = 0;
        try {
            if (player != null) {
                dur = player.getDuration();
                pos = player.getCurrentPosition();
                status = player.isPlaying() ? (byte) 1 : (byte) 2;
            }
        } catch (Throwable ignored) {}
        TrackInfoWriter w = TrackInfoWriter.INSTANCE;
        w.setTrack(title, artist, album, path, "Podcast", dur, 1);
        w.setPlayStatus(status, pos);
        if (status == 1) positionTicker.start(player);
        else positionTicker.stop();
    }

    public void onTrackPrepared(MediaPlayer player, String title, String artist, String album,
            String path, long durationMs, int trackIndex, boolean podcastActive) {
        TrackInfoWriter w = TrackInfoWriter.INSTANCE;
        w.setTrack(title, artist, album, path, podcastActive ? "Podcast" : "", durationMs,
                trackIndex + 1);
        w.resetWakeRateLimit();
        w.onTrackEdge();
        byte status = 0;
        try {
            if (player != null && player.isPlaying()) status = 1;
            else if (player != null) status = 2;
        } catch (Throwable ignored) {}
        int pos = 0;
        try {
            if (player != null) pos = player.getCurrentPosition();
        } catch (Throwable ignored) {}
        w.setPlayStatus(status, pos);
        if (status == 1) positionTicker.start(player);
    }

    public void onPlayPause(MediaPlayer player) {
        if (player == null) return;
        try {
            byte status = player.isPlaying() ? (byte) 1 : (byte) 2;
            TrackInfoWriter.INSTANCE.setPlayStatus(status, player.getCurrentPosition());
            if (status == 1) positionTicker.start(player);
            else positionTicker.stop();
        } catch (Throwable ignored) {}
    }

    public void onNaturalEnd() {
        TrackInfoWriter.INSTANCE.markNaturalEnd();
        positionTicker.stop();
    }

    public void onRepeatShuffle(int repeatMode, boolean shuffle) {
        TrackInfoWriter.INSTANCE.setRepeatShuffle(repeatMode, shuffle);
    }

    public void shutdown() {
        positionTicker.stop();
        BatteryReceiver.unregister();
        PappStateBroadcaster.unregister();
    }
}
