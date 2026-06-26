package com.solar.launcher.radio;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * TiVo-style rolling capture — 30 minutes of stream audio in a circular file.
 * ponytail: 128 kbps CBR estimate for ms timeline; upgrade to codec timestamps if drift matters.
 */
public final class RadioRingBuffer {
  public static final long MAX_BUFFER_MS = 30L * 60L * 1000L;
  /** ponytail: fixed nominal bitrate for ms↔bytes mapping */
  public static final int NOMINAL_KBPS = 128;

  private static final int BYTES_PER_MS = (NOMINAL_KBPS * 1000) / (8 * 1000);
  private static final long MAX_BYTES = MAX_BUFFER_MS * BYTES_PER_MS;
  private static final String SD_DIR = "/storage/sdcard0/RadioBuffer";
  private static final String FILE_NAME = "live.ts";

  private final File file;
  private final RandomAccessFile raf;
  private long totalWrittenBytes;
  private long writeHeadByte;

  public RadioRingBuffer(Context ctx, boolean preferSd) throws IOException {
    File dir = resolveDir(ctx, preferSd);
    if (!dir.isDirectory() && !dir.mkdirs()) {
      throw new IOException("Cannot create buffer dir: " + dir);
    }
    file = new File(dir, FILE_NAME);
    raf = new RandomAccessFile(file, "rw");
    if (raf.length() < MAX_BYTES) {
      raf.setLength(MAX_BYTES);
    }
    writeHeadByte = 0L;
    totalWrittenBytes = 0L;
  }

  /** Test hook — explicit directory, no Context. */
  RadioRingBuffer(File dir) throws IOException {
    if (!dir.isDirectory() && !dir.mkdirs()) {
      throw new IOException("Cannot create buffer dir: " + dir);
    }
    file = new File(dir, FILE_NAME);
    raf = new RandomAccessFile(file, "rw");
    if (raf.length() < MAX_BYTES) {
      raf.setLength(MAX_BYTES);
    }
    writeHeadByte = 0L;
    totalWrittenBytes = 0L;
  }

  public static File resolveDir(Context ctx, boolean preferSd) {
    if (preferSd) {
      File sd = new File(SD_DIR);
      if (sd.isDirectory() || sd.mkdirs()) return sd;
    }
    if (ctx != null) {
      File internal = new File(ctx.getFilesDir(), "RadioBuffer");
      if (internal.isDirectory() || internal.mkdirs()) return internal;
    }
    File fallback = new File(SD_DIR);
    if (!fallback.isDirectory()) fallback.mkdirs();
    return fallback;
  }

  public synchronized int write(byte[] data, int offset, int len) throws IOException {
    if (data == null || len <= 0) return 0;
    if (offset < 0) offset = 0;
    if (offset + len > data.length) len = data.length - offset;
    int written = 0;
    while (written < len) {
      int chunk = (int) Math.min(len - written, MAX_BYTES - writeHeadByte);
      raf.seek(writeHeadByte);
      raf.write(data, offset + written, chunk);
      written += chunk;
      writeHeadByte = (writeHeadByte + chunk) % MAX_BYTES;
      totalWrittenBytes += chunk;
    }
    return written;
  }

  /**
   * Read {@code len} bytes at {@code offsetMs} from buffer start (0 = oldest retained).
   * Returns bytes actually read (may be short near edges).
   */
  public synchronized int read(byte[] out, int outOffset, int len, long offsetMs) throws IOException {
    if (out == null || len <= 0) return 0;
    long avail = Math.min(totalWrittenBytes, MAX_BYTES);
    if (avail <= 0) return 0;
    long bufferedMs = getBufferedDurationMs();
    if (offsetMs < 0) offsetMs = 0;
    if (bufferedMs > 0 && offsetMs >= bufferedMs) return 0;

    long startByte;
    if (totalWrittenBytes <= MAX_BYTES) {
      startByte = bufferedMs > 0 ? (offsetMs * avail) / bufferedMs : 0L;
    } else {
      startByte = byteOffsetForTimelineMs(offsetMs);
    }
    long remaining = avail - (totalWrittenBytes <= MAX_BYTES ? startByte : 0L);
    if (totalWrittenBytes > MAX_BYTES) {
      remaining = bytesAvailableFromTimelineMs(offsetMs);
    } else if (startByte >= avail) {
      return 0;
    } else {
      remaining = avail - startByte;
    }
    int toRead = (int) Math.min(len, remaining);
    int read = 0;
    while (read < toRead) {
      long pos =
          totalWrittenBytes <= MAX_BYTES
              ? startByte + read
              : (startByte + read) % MAX_BYTES;
      int chunk = (int) Math.min(toRead - read, MAX_BYTES - pos);
      raf.seek(pos);
      int n = raf.read(out, outOffset + read, chunk);
      if (n <= 0) break;
      read += n;
    }
    return read;
  }

  /** Total captured timeline ms — capped at {@link #MAX_BUFFER_MS}. */
  public synchronized long getBufferedDurationMs() {
    if (totalWrittenBytes <= 0) return 0L;
    long ms = totalWrittenBytes / BYTES_PER_MS;
    // ponytail: sub-16-byte writes still expose a 1 ms tick for scrub UI
    if (ms == 0) ms = 1L;
    return Math.min(ms, MAX_BUFFER_MS);
  }

  /** Live edge position in buffer timeline (same as buffered duration while recording). */
  public synchronized long getLivePositionMs() {
    return getBufferedDurationMs();
  }

  public synchronized void reset() throws IOException {
    totalWrittenBytes = 0L;
    writeHeadByte = 0L;
    raf.seek(0L);
  }

  public File getFile() {
    return file;
  }

  public synchronized void close() {
    try {
      raf.close();
    } catch (IOException ignored) {}
  }

  private long byteOffsetForTimelineMs(long offsetMs) {
    long bufferedBytes = Math.min(totalWrittenBytes, MAX_BYTES);
    long offsetBytes = offsetMs * BYTES_PER_MS;
    if (totalWrittenBytes <= MAX_BYTES) {
      return Math.min(offsetBytes, bufferedBytes);
    }
    long liveStart = writeHeadByte;
    long oldestStart = (liveStart + MAX_BYTES - bufferedBytes) % MAX_BYTES;
    return (oldestStart + offsetBytes) % MAX_BYTES;
  }

  private long bytesAvailableFromTimelineMs(long offsetMs) {
    long bufferedMs = getBufferedDurationMs();
    if (offsetMs >= bufferedMs) return 0L;
    long remainingMs = bufferedMs - offsetMs;
    return remainingMs * BYTES_PER_MS;
  }
}
