package com.solar.launcher.radio;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RadioRingBufferTest {

  @Test
  public void writeRead_roundTripWithinBuffer() throws Exception {
    File dir = File.createTempFile("ringbuf", "");
    if (!dir.delete() || !dir.mkdir()) throw new AssertionError("tmpdir");
    RadioRingBuffer buf = new RadioRingBuffer(dir);
    byte[] payload = new byte[] {1, 2, 3, 4, 5};
    int written = buf.write(payload, 0, payload.length);
    assertEquals(5, written);
    assertTrue(buf.getBufferedDurationMs() > 0);
    byte[] out = new byte[5];
    int read = buf.read(out, 0, 5, 0L);
    assertEquals(5, read);
    for (int i = 0; i < 5; i++) {
      assertEquals(payload[i], out[i]);
    }
    buf.close();
  }

  @Test
  public void livePosition_tracksBufferedDuration() throws Exception {
    File dir = File.createTempFile("ringbuf2", "");
    if (!dir.delete() || !dir.mkdir()) throw new AssertionError("tmpdir");
    RadioRingBuffer buf = new RadioRingBuffer(dir);
    byte[] chunk = new byte[RadioRingBuffer.NOMINAL_KBPS * 128 / 8];
    buf.write(chunk, 0, chunk.length);
    assertEquals(buf.getBufferedDurationMs(), buf.getLivePositionMs());
    buf.close();
  }

  @Test
  public void bufferedDuration_capsAtMax() throws Exception {
    File dir = File.createTempFile("ringbuf3", "");
    if (!dir.delete() || !dir.mkdir()) throw new AssertionError("tmpdir");
    RadioRingBuffer buf = new RadioRingBuffer(dir);
    int block = 64 * 1024;
    byte[] chunk = new byte[block];
    long targetBytes = (RadioRingBuffer.MAX_BUFFER_MS * RadioRingBuffer.NOMINAL_KBPS * 1000) / (8 * 1000) + block;
    long written = 0L;
    while (written < targetBytes) {
      written += buf.write(chunk, 0, chunk.length);
    }
    assertEquals(RadioRingBuffer.MAX_BUFFER_MS, buf.getBufferedDurationMs());
    buf.close();
  }
}
