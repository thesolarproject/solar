package com.solar.launcher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** ponytail: M3U/M3U8 playlists under music tree + Playlists/ folder — line-based parse, no URL playlists. */
public final class PlaylistManager {
  public static final class Entry {
    public final String name;
    public final File sourceFile;
    public final List<File> tracks;

    public Entry(String name, File sourceFile, List<File> tracks) {
      this.name = name;
      this.sourceFile = sourceFile;
      this.tracks = tracks != null ? tracks : new ArrayList<File>();
    }
  }

  private PlaylistManager() {}

  public static File playlistsDir(File musicRoot) {
    return new File(musicRoot, "Playlists");
  }

  public static List<Entry> scan(File musicRoot) {
    List<Entry> out = new ArrayList<Entry>();
    if (musicRoot == null || !musicRoot.isDirectory()) return out;
    collectM3u(musicRoot, musicRoot, out, new java.util.HashSet<String>());
    File dedicated = playlistsDir(musicRoot);
    if (dedicated.isDirectory()) collectM3u(dedicated, musicRoot, out, new java.util.HashSet<String>());
    return out;
  }

  private static void collectM3u(File dir, File musicRoot, List<Entry> out, java.util.Set<String> seen) {
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File f : files) {
      if (f.isDirectory()) {
        if (!f.getName().equals("Playlists") || dir.equals(playlistsDir(musicRoot))) {
          collectM3u(f, musicRoot, out, seen);
        }
      } else if (isM3u(f)) {
        String key = f.getAbsolutePath();
        if (seen.add(key)) {
          Entry e = parse(f, musicRoot);
          out.add(e);
        }
      }
    }
  }

  public static boolean isM3u(File f) {
    if (f == null || !f.isFile()) return false;
    String n = f.getName().toLowerCase(Locale.US);
    return n.endsWith(".m3u") || n.endsWith(".m3u8");
  }

  public static Entry parse(File m3u, File musicRoot) {
    String label = m3u.getName();
    int dot = label.lastIndexOf('.');
    if (dot > 0) label = label.substring(0, dot);
    List<File> tracks = new ArrayList<File>();
    try {
      BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(m3u), "UTF-8"));
      String line;
      String pendingTitle = null;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;
        if (line.startsWith("#")) {
          if (line.startsWith("#EXTINF:")) {
            int comma = line.lastIndexOf(',');
            pendingTitle = comma >= 0 && comma + 1 < line.length() ? line.substring(comma + 1).trim() : null;
          }
          continue;
        }
        File resolved = resolvePath(line, m3u.getParentFile(), musicRoot);
        if (resolved != null && resolved.isFile()) tracks.add(resolved);
        pendingTitle = null;
      }
      br.close();
    } catch (Exception ignored) {}
    return new Entry(label, m3u, tracks);
  }

  static File resolvePath(String raw, File playlistDir, File musicRoot) {
    if (raw == null) return null;
    String p = raw.trim();
    if (p.isEmpty()) return null;
    if (p.startsWith("file://")) p = p.substring(7);
    File f = new File(p);
    if (f.isFile()) return f;
    if (!f.isAbsolute() && playlistDir != null) {
      File rel = new File(playlistDir, p);
      if (rel.isFile()) return rel;
    }
    if (musicRoot != null) {
      File under = new File(musicRoot, p);
      if (under.isFile()) return under;
    }
    return null;
  }

  public static void saveM3u(Entry entry, File dest) throws Exception {
    if (entry == null || dest == null) throw new IllegalArgumentException("entry/dest");
    File parent = dest.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();
    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(dest), "UTF-8"));
    w.write("#EXTM3U\n");
    for (File t : entry.tracks) {
      if (t == null || !t.isFile()) continue;
      String title = t.getName();
      int dot = title.lastIndexOf('.');
      if (dot > 0) title = title.substring(0, dot);
      w.write("#EXTINF:-1," + title + "\n");
      w.write(t.getAbsolutePath() + "\n");
    }
    w.close();
  }

  /** Write M3U with paths relative to the playlist file directory. */
  public static void saveM3uRelative(Entry entry, File dest) throws Exception {
    if (entry == null || dest == null) throw new IllegalArgumentException("entry/dest");
    File baseDir = dest.getParentFile();
    if (baseDir != null && !baseDir.exists()) baseDir.mkdirs();
    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(dest), "UTF-8"));
    w.write("#EXTM3U\n");
    for (File t : entry.tracks) {
      if (t == null || !t.isFile()) continue;
      String title = t.getName();
      int dot = title.lastIndexOf('.');
      if (dot > 0) title = title.substring(0, dot);
      w.write("#EXTINF:-1," + title + "\n");
      w.write(relativize(baseDir, t) + "\n");
    }
    w.close();
  }

  /** ponytail: URI relativize — M3U paths use forward slashes on all platforms. */
  static String relativize(File baseDir, File file) {
    if (baseDir == null || file == null) return file != null ? file.getAbsolutePath() : "";
    try {
      java.net.URI baseUri = baseDir.getCanonicalFile().toURI();
      java.net.URI fileUri = file.getCanonicalFile().toURI();
      String path = baseUri.relativize(fileUri).getPath();
      if (path == null || path.isEmpty()) return file.getName();
      return path;
    } catch (Exception ignored) {}
    return file.getAbsolutePath();
  }

  public static Entry fromTracks(String name, List<File> tracks) {
    return new Entry(name, null, new ArrayList<File>(tracks));
  }

  /** Filesystem-safe base name for a new M3U (no extension). */
  public static String safeFileName(String displayName) {
    if (displayName == null) return "Playlist";
    String s = displayName.trim();
    if (s.isEmpty()) return "Playlist";
    s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
    s = s.replaceAll("\\s+", " ").trim();
    if (s.isEmpty()) return "Playlist";
    if (s.length() > 80) s = s.substring(0, 80).trim();
    return s;
  }

  /** Unique .m3u path under dir — appends (n) on collision. */
  public static File uniqueM3uFile(File dir, String baseName) {
    String safe = safeFileName(baseName);
    File m3u = new File(dir, safe + ".m3u");
    int n = 1;
    while (m3u.exists()) {
      m3u = new File(dir, safe + " (" + n + ").m3u");
      n++;
    }
    return m3u;
  }

  /** Create a new playlist under musicRoot/Playlists/. */
  public static Entry createPlaylist(File musicRoot, String displayName, List<File> tracks)
      throws Exception {
    if (musicRoot == null || !musicRoot.isDirectory()) {
      throw new IllegalArgumentException("musicRoot");
    }
    File dir = playlistsDir(musicRoot);
    if (!dir.exists() && !dir.mkdirs()) {
      throw new IllegalStateException("mkdir Playlists");
    }
    String label = displayName != null ? displayName.trim() : "";
    if (label.isEmpty()) label = "Playlist";
    File dest = uniqueM3uFile(dir, label);
    List<File> list = tracks != null ? new ArrayList<File>(tracks) : new ArrayList<File>();
    Entry entry = new Entry(label, dest, list);
    saveM3u(entry, dest);
    return new Entry(label, dest, list);
  }

  /** Merge tracks into an existing M3U — skips duplicate paths. */
  public static Entry appendTracks(File m3u, File musicRoot, List<File> toAdd) throws Exception {
    if (m3u == null || !m3u.isFile()) throw new IllegalArgumentException("m3u");
    Entry existing = parse(m3u, musicRoot);
    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
    List<File> merged = new ArrayList<File>();
    for (File t : existing.tracks) {
      if (t == null || !t.isFile()) continue;
      String key = canonicalKey(t);
      if (seen.add(key)) merged.add(t);
    }
    if (toAdd != null) {
      for (File t : toAdd) {
        if (t == null || !t.isFile()) continue;
        String key = canonicalKey(t);
        if (seen.add(key)) {
          merged.add(t);
        }
      }
    }
    Entry out = new Entry(existing.name, m3u, merged);
    saveM3u(out, m3u);
    return out;
  }

  private static String canonicalKey(File f) {
    try {
      return f.getCanonicalPath();
    } catch (Exception ignored) {
      return f.getAbsolutePath();
    }
  }

  /** Delete local M3U playlist file — does not remove referenced audio files. */
  public static boolean deletePlaylistFile(File m3u) {
    if (m3u == null || !m3u.isFile()) return false;
    return m3u.delete();
  }
}
