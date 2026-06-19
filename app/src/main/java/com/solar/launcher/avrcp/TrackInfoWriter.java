package com.solar.launcher.avrcp;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/** Double-buffer writer for Koensayr y1-track-info (2213 B). */
public final class TrackInfoWriter {
    public static final TrackInfoWriter INSTANCE = new TrackInfoWriter();

    private static final int SLOT_SIZE = 1104;
    private static final int FILE_SIZE = 2213;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Context context;
    private File trackInfoFile;
    private byte playStatus;
    private long positionAtStateChange;
    private long stateChangeTime;
    private boolean previousTrackNaturalEnd;
    private byte batteryStatus;
    private byte repeatAvrcp = 0x01;
    private byte shuffleAvrcp = 0x01;
    private long cachedAudioId;
    private String cachedTitle = "";
    private String cachedArtist = "";
    private String cachedAlbum = "";
    private long lastKnownDuration;
    private long lastWakePlayStateAt;
    private byte lastWakePlayStatus = -1;

    private String title = "";
    private String artist = "";
    private String album = "";
    private String path = "";
    private String genre = "";
    private long durationMs;
    private int trackNumber;

    private long mmrAudioId;
    private long mmrDurationMs;

    private TrackInfoWriter() {}

    public synchronized void init(Context ctx) {
        if (context != null) return;
        context = ctx.getApplicationContext();
        AvrcpPaths.ensureFilesDir();
        trackInfoFile = new File(AvrcpPaths.TRACK_INFO);
        prepareFile();
        flushLocked(false, false);
    }

    private void prepareFile() {
        try {
            if (!trackInfoFile.exists()) {
                RandomAccessFile raf = new RandomAccessFile(trackInfoFile, "rw");
                raf.setLength(FILE_SIZE);
                raf.close();
            }
            trackInfoFile.setReadable(true, false);
            trackInfoFile.setWritable(true, true);
        } catch (Throwable ignored) {}
    }

    public synchronized void setTrack(String title, String artist, String album, String path,
            String genre, long durationMs, int trackNumber) {
        this.title = safe(title);
        this.artist = safe(artist);
        this.album = safe(album);
        this.path = path != null ? path : "";
        this.genre = safe(genre);
        long resolved = durationMs > 0 ? durationMs : resolveDurationMs(path, syntheticAudioId(this.path));
        if (resolved > 0) lastKnownDuration = resolved;
        this.durationMs = resolved > 0 ? resolved : lastKnownDuration;
        this.trackNumber = trackNumber > 0 ? trackNumber : 1;
    }

    public synchronized void setPlayStatus(byte status, long positionMs) {
        if (playStatus != status) {
            positionAtStateChange = positionMs >= 0 ? positionMs : positionAtStateChange;
            stateChangeTime = SystemClock.elapsedRealtime() & 0xFFFFFFFFL;
            if (status == 0) previousTrackNaturalEnd = false;
        }
        playStatus = status;
        flushLocked(false, true);
    }

    public synchronized void markNaturalEnd() {
        previousTrackNaturalEnd = true;
        flushLocked(false, true);
    }

    public synchronized void updatePosition(long positionMs) {
        positionAtStateChange = positionMs >= 0 ? positionMs : 0;
        stateChangeTime = SystemClock.elapsedRealtime() & 0xFFFFFFFFL;
        flushLocked(false, true);
    }

    public synchronized void setBattery(byte status) {
        batteryStatus = status;
        flushLocked(false, true);
    }

    public synchronized void setRepeatShuffle(int repeatMode, boolean shuffle) {
        repeatAvrcp = repeatMode == 1 ? (byte) 0x02 : repeatMode == 2 ? (byte) 0x03 : (byte) 0x01;
        shuffleAvrcp = shuffle ? (byte) 0x02 : (byte) 0x01;
        AvrcpPaths.writePappSet(repeatAvrcp, shuffleAvrcp);
        flushLocked(false, true);
    }

    public synchronized void onTrackEdge() {
        previousTrackNaturalEnd = false;
        flushLocked(true, true);
    }

    private void flushLocked(boolean trackChanged, boolean wakePlayState) {
        if (trackInfoFile == null) return;
        try {
            long audioId = syntheticAudioId(path);
            cachedAudioId = audioId;
            cachedTitle = title;
            cachedArtist = artist;
            cachedAlbum = album;
            if (durationMs <= 0) durationMs = lastKnownDuration;

            byte[] slot = new byte[SLOT_SIZE];
            putBE64(slot, 0, audioId);
            putUtf8Padded(slot, 8, 256, title);
            putUtf8Padded(slot, 264, 256, artist);
            putUtf8Padded(slot, 520, 256, album);
            putBE32(slot, 776, (int) Math.min(durationMs, 0xFFFFFFFFL));
            putBE32(slot, 780, (int) Math.min(positionAtStateChange, 0xFFFFFFFFL));
            putBE32(slot, 784, (int) stateChangeTime);
            slot[792] = playStatus;
            slot[793] = (byte) (previousTrackNaturalEnd ? 1 : 0);
            slot[794] = batteryStatus;
            slot[795] = repeatAvrcp;
            slot[796] = shuffleAvrcp;
            putUtf8Padded(slot, 800, 16, Integer.toString(trackNumber));
            putUtf8Padded(slot, 816, 16, "");
            putUtf8Padded(slot, 832, 16, durationMs > 0 ? Long.toString(durationMs) : "");
            putUtf8Padded(slot, 848, 256, genre);

            RandomAccessFile raf = new RandomAccessFile(trackInfoFile, "rw");
            int active = raf.read();
            if (active < 0 || active > 1) active = 0;
            int inactive = 1 - active;
            int slotOffset = 4 + inactive * SLOT_SIZE;
            raf.seek(slotOffset);
            raf.write(slot);
            raf.seek(0);
            raf.write(inactive);
            raf.close();

            if (trackChanged) wakeTrackChanged();
            if (wakePlayState) wakePlayStateChanged();
        } catch (Throwable ignored) {}
    }

    private void wakeTrackChanged() {
        if (context == null) return;
        try {
            Intent i = new Intent("com.android.music.metachanged");
            i.putExtra("id", cachedAudioId);
            i.putExtra("track", cachedTitle);
            i.putExtra("artist", cachedArtist);
            i.putExtra("album", cachedAlbum);
            context.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    public void wakePlayStateChanged() {
        if (context == null) return;
        long now = SystemClock.elapsedRealtime();
        if (playStatus == lastWakePlayStatus && now - lastWakePlayStateAt < 800) return;
        lastWakePlayStatus = playStatus;
        lastWakePlayStateAt = now;
        try {
            context.sendBroadcast(new Intent("com.android.music.playstatechanged"));
        } catch (Throwable ignored) {}
    }

    public void resetWakeRateLimit() {
        lastWakePlayStateAt = 0;
        lastWakePlayStatus = -1;
    }

    private long resolveDurationMs(String trackPath, long audioId) {
        if (audioId == mmrAudioId && mmrDurationMs > 0) return mmrDurationMs;
        if (trackPath == null || trackPath.isEmpty()) return 0;
        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(trackPath);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                long ms = Long.parseLong(d);
                if (ms > 0) {
                    mmrAudioId = audioId;
                    mmrDurationMs = ms;
                    return ms;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (mmr != null) try { mmr.release(); } catch (Throwable ignored) {}
        }
        return 0;
    }

    private static long syntheticAudioId(String p) {
        if (p == null || p.isEmpty()) return 0x100000000L;
        return ((p.hashCode() & 0xFFFFFFFFL) | 0x100000000L);
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    private static void putBE64(byte[] buf, int off, long v) {
        for (int i = 0; i < 8; i++) {
            buf[off + i] = (byte) ((v >> ((7 - i) * 8)) & 0xFF);
        }
    }

    private static void putBE32(byte[] buf, int off, int v) {
        buf[off] = (byte) ((v >> 24) & 0xFF);
        buf[off + 1] = (byte) ((v >> 16) & 0xFF);
        buf[off + 2] = (byte) ((v >> 8) & 0xFF);
        buf[off + 3] = (byte) (v & 0xFF);
    }

    private static void putUtf8Padded(byte[] buf, int off, int len, String s) {
        byte[] raw = safe(s).getBytes(UTF8);
        int n = Math.min(raw.length, len);
        System.arraycopy(raw, 0, buf, off, n);
    }
}
