package com.solar.launcher.soulseek;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

public class SoulseekShareIndexTest {

  @Test
  public void scanMapsVirtualPaths() throws Exception {
    File music = new File(System.getProperty("java.io.tmpdir"), "share_test_music");
    File podcasts = new File(System.getProperty("java.io.tmpdir"), "share_test_pod");
    deleteTree(music);
    deleteTree(podcasts);
    new File(music, "Artist").mkdirs();
    new File(podcasts, "Show").mkdirs();
    File track = new File(music, "Artist/song.mp3");
    writeEmpty(track);
    File ep = new File(podcasts, "Show/episode.mp3");
    writeEmpty(ep);

    SoulseekShareIndex idx = new SoulseekShareIndex();
    idx.scan("testuser", music, podcasts);
    if (idx.fileCount() != 2) throw new AssertionError("count=" + idx.fileCount());
    if (idx.dirCount() < 2) throw new AssertionError("dirs=" + idx.dirCount());
    File resolved = idx.resolve("@@testuser\\Music\\Artist\\song.mp3");
    if (resolved == null || !resolved.equals(track)) throw new AssertionError("music path");
    byte[] list = idx.buildShareListUncompressed();
    if (list == null || list.length < 12) throw new AssertionError("share list");
    byte[] zlib = SoulseekShareIndex.zlibCompress(list);
    if (zlib.length == 0) throw new AssertionError("zlib");

    deleteTree(music);
    deleteTree(podcasts);
  }

  private static void writeEmpty(File f) throws Exception {
    f.getParentFile().mkdirs();
    FileOutputStream out = new FileOutputStream(f);
    out.write(1);
    out.close();
  }

  private static void deleteTree(File f) {
    if (f == null || !f.exists()) return;
    if (f.isDirectory()) {
      File[] kids = f.listFiles();
      if (kids != null) for (File k : kids) deleteTree(k);
    }
    f.delete();
  }
}
