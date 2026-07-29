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

  @Test
  public void scanFromKnownMusicFilesSkipsTreeWalk() throws Exception {
    File music = new File(System.getProperty("java.io.tmpdir"), "share_known_music");
    deleteTree(music);
    new File(music, "Artist").mkdirs();
    File track = new File(music, "Artist/song.mp3");
    writeEmpty(track);

    java.util.ArrayList<File> known = new java.util.ArrayList<File>();
    known.add(track);

    SoulseekShareIndex idx = new SoulseekShareIndex();
    idx.scan("testuser", music, null, null, known);
    if (idx.fileCount() != 1) throw new AssertionError("count=" + idx.fileCount());
    File resolved = idx.resolve("@@testuser\\Music\\Artist\\song.mp3");
    if (resolved == null || !resolved.equals(track)) throw new AssertionError("known file path");

    deleteTree(music);
  }

  /**
   * 2026-07-20 — SEGMENTED share scan builds knownMusicFiles from path→size (SQL), not customLibrary.
   * Layman: database paths still become shareable files when the in-RAM library list is empty.
   * Was: empty customLibrary snap → missing shares. Reversal: require non-empty File snap from RAM.
   */
  @Test
  public void musicFilesFromPathSizesThenScan() throws Exception {
    File music = new File(System.getProperty("java.io.tmpdir"), "share_path_sizes_music");
    deleteTree(music);
    new File(music, "Band").mkdirs();
    File track = new File(music, "Band/tune.mp3");
    writeEmpty(track);

    java.util.HashMap<String, Long> pathSizes = new java.util.HashMap<String, Long>();
    pathSizes.put(track.getAbsolutePath(), Long.valueOf(track.length()));
    pathSizes.put("/no/such/missing.mp3", Long.valueOf(1L)); // positive size but absent — filtered later
    pathSizes.put("", Long.valueOf(9L));
    pathSizes.put(null, Long.valueOf(9L));
    pathSizes.put(track.getAbsolutePath() + ".skip", Long.valueOf(0L)); // size 0 skipped

    java.util.List<File> fromDb = SoulseekShareIndex.musicFilesFromPathSizes(pathSizes);
    if (fromDb.size() != 2) throw new AssertionError("fromDb=" + fromDb.size());

    java.util.ArrayList<File> existing = new java.util.ArrayList<File>();
    for (File f : fromDb) {
      if (f != null && f.isFile()) existing.add(f);
    }
    if (existing.size() != 1) throw new AssertionError("existing=" + existing.size());

    SoulseekShareIndex idx = new SoulseekShareIndex();
    idx.scan("seguser", music, null, null, existing);
    if (idx.fileCount() != 1) throw new AssertionError("count=" + idx.fileCount());
    File resolved = idx.resolve("@@seguser\\Music\\Band\\tune.mp3");
    if (resolved == null || !resolved.equals(track)) throw new AssertionError("sql path share");

    deleteTree(music);
  }

  @Test
  public void knownFileOutsideMusicRootIsNotShared() throws Exception {
    File music = new File(System.getProperty("java.io.tmpdir"), "share_safe_root");
    File outside = new File(System.getProperty("java.io.tmpdir"), "share_private/song.mp3");
    deleteTree(music);
    deleteTree(outside.getParentFile());
    music.mkdirs();
    writeEmpty(outside);
    java.util.ArrayList<File> known = new java.util.ArrayList<File>();
    known.add(outside);

    SoulseekShareIndex idx = new SoulseekShareIndex();
    idx.scan("testuser", music, null, null, known);

    if (idx.fileCount() != 0) throw new AssertionError("outside file was shared");
    deleteTree(music);
    deleteTree(outside.getParentFile());
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
