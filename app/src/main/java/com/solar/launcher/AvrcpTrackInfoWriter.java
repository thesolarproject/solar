package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/**
 * Publishes Koensayr-compatible {@code y1-track-info} for Y1Bridge + AVRCP trampolines.
 * Schema matches {@code com.koensayr.y1.trackinfo.TrackInfoWriter} / Y1Bridge offsets.
 */
public final class AvrcpTrackInfoWriter {

    private static final String TAG = "SolarAvrcp";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Y1Bridge hardcodes this path — not Solar's package dir. */
    public static final String TRACK_INFO_PATH =
            "/data/data/com.innioasis.y1/files/y1-track-info";

    private static final int FILE_SIZE = 2213;
    private static final int SLOT_SIZE = 1104;
    private static final int OFF_AUDIO_ID = 0;
    private static final int OFF_TITLE = 8;
    private static final int OFF_ARTIST = 264;
    private static final int OFF_ALBUM = 520;
    private static final int OFF_DURATION_MS = 776;
    private static final int OFF_POSITION_MS = 780;
    private static final int OFF_STATE_CHANGE_TIME = 784;
    private static final int OFF_PLAY_STATUS = 792;
    private static final int OFF_REPEAT_AVRCP = 795;
    private static final int OFF_SHUFFLE_AVRCP = 796;
    private static final int STRING_FIELD_LEN = 256;

  /** AVRCP play status: 0=stopped, 1=playing, 2=paused. */
    private static final byte PLAY_STOPPED = 0;
    private static final byte PLAY_PLAYING = 1;
    private static final byte PLAY_PAUSED = 2;

    private static AvrcpTrackInfoWriter instance;

    private final Context appContext;
    private boolean filesPrepared;
    private long audioId;
    private String title = "";
    private String artist = "";
    private String album = "";
    private int durationMs;
    private int positionAtStateChange;
    private long stateChangeElapsed;
    private byte playStatus = PLAY_STOPPED;
    private byte lastWakePlayStatus = PLAY_STOPPED;
    private long lastWakePlayStateAt;
    private long lastFlushedAudioId = -1;

    private AvrcpTrackInfoWriter(Context context) {
        appContext = context.getApplicationContext();
    }

    public static synchronized AvrcpTrackInfoWriter getInstance(Context context) {
        if (instance == null) {
            instance = new AvrcpTrackInfoWriter(context);
        }
        return instance;
    }

    /** Call from playback UI refresh — cheap no-op when nothing changed. */
    public synchronized void syncFromPlayback(
            String title, String artist, String album,
            int durationMs, int positionMs, boolean playing, boolean trackChanged) {
        long newId = stableAudioId(title, artist, album);
        boolean playEdge = playStatus != (playing ? PLAY_PLAYING : PLAY_PAUSED);
        this.title = safe(title);
        this.artist = safe(artist);
        this.album = safe(album);
        this.durationMs = Math.max(0, durationMs);
        byte newStatus = playing ? PLAY_PLAYING : (positionMs > 0 || durationMs > 0 ? PLAY_PAUSED : PLAY_STOPPED);
        if (playEdge || newStatus != playStatus) {
            positionAtStateChange = Math.max(0, positionMs);
            stateChangeElapsed = SystemClock.elapsedRealtime();
        }
        playStatus = newStatus;
        audioId = newId;

        if (!ensureFiles()) return;

        boolean metaEdge = trackChanged || newId != lastFlushedAudioId;
        flushLocked();
        if (metaEdge) {
            wakeTrackChanged();
        }
        if (metaEdge || playEdge) {
            wakePlayStateChanged();
        }
        lastFlushedAudioId = newId;
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("titleLen", title.length());
            d.put("artistLen", artist.length());
            d.put("playStatus", playStatus);
            d.put("durationMs", durationMs);
            d.put("filesPrepared", filesPrepared);
            DebugAgentLog.log(appContext, "AvrcpTrackInfoWriter.syncFromPlayback", "flush", "H-AV2", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    public synchronized void markTrackChange() {
        lastFlushedAudioId = -1;
    }

    /**
     * ~1 Hz playhead refresh while playing — car receivers poll GetPlayStatus / RegisterNotification.
     * ponytail: rate-limited wakePlayStateChanged matches Koensayr PositionTicker cadence.
     */
    public synchronized void tickPlayingPosition(int positionMs) {
        if (playStatus != PLAY_PLAYING || !filesPrepared) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastWakePlayStateAt < 900) return;
        positionAtStateChange = Math.max(0, positionMs);
        stateChangeElapsed = now;
        flushLocked();
        wakePlayStateChanged();
    }

    /** Prime y1-track-info file on BT connect so Y1Bridge can mmap before first track. */
    public void ensureReady() {
        ensureFiles();
    }

    private boolean ensureFiles() {
        if (filesPrepared) return true;
        String dir = "/data/data/com.innioasis.y1/files";
        String prep = "mkdir -p " + shQuote(dir)
                + " && chmod 711 /data/data/com.innioasis.y1 2>/dev/null; chmod 711 " + shQuote(dir)
                + " 2>/dev/null; [ -f " + shQuote(TRACK_INFO_PATH) + " ] || dd if=/dev/zero of="
                + shQuote(TRACK_INFO_PATH) + " bs=1 count=" + FILE_SIZE + " 2>/dev/null"
                + " && chmod 666 " + shQuote(TRACK_INFO_PATH);
        if (runSu(prep)) {
            filesPrepared = true;
            return true;
        }
        try {
            File parent = new File(dir);
            if (!parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "ensureFiles: mkdirs failed for " + dir);
                return false;
            }
            File f = new File(TRACK_INFO_PATH);
            if (!f.exists()) {
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(new byte[FILE_SIZE]);
                fos.close();
            }
            filesPrepared = true;
            return true;
        } catch (IOException e) {
            Log.w(TAG, "ensureFiles: " + e.getMessage());
            return false;
        }
    }

    private void flushLocked() {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(TRACK_INFO_PATH, "rw");
            int active = raf.read();
            if (active < 0 || active > 1) active = 0;
            int inactive = 1 - active;
            int slotOff = 4 + inactive * SLOT_SIZE;

            byte[] slot = new byte[SLOT_SIZE];
            putBe64(slot, OFF_AUDIO_ID, audioId);
            putUtf8Padded(slot, OFF_TITLE, STRING_FIELD_LEN, title);
            putUtf8Padded(slot, OFF_ARTIST, STRING_FIELD_LEN, artist);
            putUtf8Padded(slot, OFF_ALBUM, STRING_FIELD_LEN, album);
            putBe32(slot, OFF_DURATION_MS, durationMs);
            putBe32(slot, OFF_POSITION_MS, positionAtStateChange);
            putBe32(slot, OFF_STATE_CHANGE_TIME, (int) (stateChangeElapsed & 0xffffffffL));
            slot[OFF_PLAY_STATUS] = playStatus;
            slot[OFF_REPEAT_AVRCP] = 0x01; // AVRCP repeat off
            slot[OFF_SHUFFLE_AVRCP] = 0x01; // AVRCP shuffle off

            raf.seek(slotOff);
            raf.write(slot);
            raf.seek(0);
            raf.write(inactive);
        } catch (IOException e) {
            Log.w(TAG, "flush: " + e.getMessage());
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void wakeTrackChanged() {
        Intent intent = new Intent("com.android.music.metachanged");
        intent.putExtra("id", audioId);
        intent.putExtra("track", title);
        intent.putExtra("artist", artist);
        intent.putExtra("album", album);
        // Sticky so late-starting mtkbt sees metadata after BT connect.
        appContext.sendStickyBroadcast(intent);
    }

    private void wakePlayStateChanged() {
        long now = SystemClock.elapsedRealtime();
        if (playStatus == lastWakePlayStatus && now - lastWakePlayStateAt < 800) return;
        lastWakePlayStateAt = now;
        lastWakePlayStatus = playStatus;

        Intent intent = new Intent("com.android.music.playstatechanged");
        intent.putExtra("id", audioId);
        intent.putExtra("track", title);
        intent.putExtra("artist", artist);
        intent.putExtra("album", album);
        intent.putExtra("playing", playStatus == PLAY_PLAYING);
        appContext.sendStickyBroadcast(intent);
    }

    private static long stableAudioId(String title, String artist, String album) {
        String key = safe(title) + "\0" + safe(artist) + "\0" + safe(album);
        long h = 0;
        for (int i = 0; i < key.length(); i++) {
            h = 31 * h + key.charAt(i);
        }
        return h & 0xffffffffL;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void putBe32(byte[] buf, int off, int v) {
        buf[off] = (byte) ((v >> 24) & 0xff);
        buf[off + 1] = (byte) ((v >> 16) & 0xff);
        buf[off + 2] = (byte) ((v >> 8) & 0xff);
        buf[off + 3] = (byte) (v & 0xff);
    }

    private static void putBe64(byte[] buf, int off, long v) {
        putBe32(buf, off, (int) (v >> 32));
        putBe32(buf, off + 4, (int) v);
    }

    private static void putUtf8Padded(byte[] buf, int off, int len, String s) {
        byte[] raw = safe(s).getBytes(UTF8);
        int copy = Math.min(raw.length, len - 1);
        if (copy > 0) System.arraycopy(raw, 0, buf, off, copy);
    }

    private static boolean runSu(String command) {
        for (String su : new String[] {"/system/xbin/su", "su"}) {
            java.io.DataOutputStream os = null;
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(su);
                os = new java.io.DataOutputStream(process.getOutputStream());
                os.writeBytes(command + "\n");
                os.writeBytes("exit\n");
                os.flush();
                if (process.waitFor() == 0) return true;
            } catch (Throwable ignored) {
            } finally {
                if (os != null) try { os.close(); } catch (Exception ignored) {}
                if (process != null) process.destroy();
            }
        }
        return false;
    }

    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static void selfCheckSchema() {
        if (OFF_PLAY_STATUS != 792) throw new AssertionError("play status offset");
        if (4 + SLOT_SIZE * 2 + 1 != FILE_SIZE) throw new AssertionError("file size");
    }
}
