package com.solar.launcher.soulseek;

import android.media.MediaMetadataRetriever;

import com.solar.launcher.podcast.PodcastLibrary;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;

/** Indexes local Music/Podcasts for Soulseek virtual share paths. */
public final class SoulseekShareIndex {
    static final class Entry {
        final String virtualPath;
        final String dir;
        final String name;
        final File file;
        final long size;
        final String ext;
        final int bitrate;
        final int duration;

        Entry(String virtualPath, String dir, String name, File file, long size, String ext,
                int bitrate, int duration) {
            this.virtualPath = virtualPath;
            this.dir = dir;
            this.name = name;
            this.file = file;
            this.size = size;
            this.ext = ext;
            this.bitrate = bitrate;
            this.duration = duration;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private final Map<String, File> byVirtualPath = new HashMap<String, File>();
    private final Map<String, List<Entry>> byDir = new LinkedHashMap<String, List<Entry>>();

    public static SoulseekShareIndex empty() {
        return new SoulseekShareIndex();
    }

    public synchronized void scan(String username, File musicRoot, File podcastRoot) {
        scan(username, musicRoot, podcastRoot, null, null);
    }

    /** @param cachedDurationSecByPath lower-case absolute path → duration seconds from library cache */
    public synchronized void scan(String username, File musicRoot, File podcastRoot,
            Map<String, Integer> cachedDurationSecByPath) {
        scan(username, musicRoot, podcastRoot, cachedDurationSecByPath, null);
    }

    /**
     * @param knownMusicFiles when non-empty, index Music from this list instead of re-walking the tree
     *        (ponytail: avoids a second O(files) filesystem walk after library scan).
     */
    public synchronized void scan(String username, File musicRoot, File podcastRoot,
            Map<String, Integer> cachedDurationSecByPath, List<File> knownMusicFiles) {
        List<Entry> newEntries = new ArrayList<Entry>();
        Map<String, File> newByVirtualPath = new HashMap<String, File>();
        Map<String, List<Entry>> newByDir = new LinkedHashMap<String, List<Entry>>();
        if (username != null && !username.trim().isEmpty()) {
            String user = username.trim();
            if (musicRoot != null && musicRoot.isDirectory()) {
                if (knownMusicFiles != null && !knownMusicFiles.isEmpty()) {
                    for (File f : knownMusicFiles) {
                        addShareableFile(user, "Music", musicRoot, f, newEntries, newByVirtualPath,
                                cachedDurationSecByPath);
                    }
                } else {
                    scanRoot(user, "Music", musicRoot, musicRoot, newEntries, newByVirtualPath,
                            cachedDurationSecByPath);
                }
            }
            if (podcastRoot != null && podcastRoot.isDirectory()) {
                scanRoot(user, "Podcasts", podcastRoot, podcastRoot, newEntries, newByVirtualPath,
                        cachedDurationSecByPath);
            }
            for (Entry e : newEntries) {
                List<Entry> list = newByDir.get(e.dir);
                if (list == null) {
                    list = new ArrayList<Entry>();
                    newByDir.put(e.dir, list);
                }
                list.add(e);
            }
        }
        entries.clear();
        entries.addAll(newEntries);
        byVirtualPath.clear();
        byVirtualPath.putAll(newByVirtualPath);
        byDir.clear();
        byDir.putAll(newByDir);
    }

    /**
     * Add diagnostic log files to the share index. These are always shared
     * regardless of the user's library sharing preference — they help the
     * community diagnose and fix Solar issues.
     *
     * Logs appear under @@username\Solar Diagnostics\
     */
    public synchronized void scanDiagnosticLogs(String username, File sdcardRoot, File filesDir) {
        if (username == null || username.trim().isEmpty()) return;
        String user = username.trim();
        List<File> logFiles = new ArrayList<File>();
        // Collect debug-*.log files from sdcard0
        if (sdcardRoot != null && sdcardRoot.isDirectory()) {
            File[] kids = sdcardRoot.listFiles();
            if (kids != null) {
                for (File f : kids) {
                    if (f.isFile() && f.getName().startsWith("debug-") && f.getName().endsWith(".log")) {
                        logFiles.add(f);
                    }
                }
            }
        }
        // Also collect from app internal filesDir
        if (filesDir != null && filesDir.isDirectory()) {
            File[] kids = filesDir.listFiles();
            if (kids != null) {
                for (File f : kids) {
                    if (f.isFile() && f.getName().startsWith("debug-") && f.getName().endsWith(".log")) {
                        // Only add if not already found on sdcard (avoid duplicates)
                        boolean dupe = false;
                        for (File existing : logFiles) {
                            if (existing.getName().equals(f.getName())) { dupe = true; break; }
                        }
                        if (!dupe) logFiles.add(f);
                    }
                }
            }
        }
        for (File f : logFiles) {
            String virtual = "@@" + user + "\\Solar Diagnostics\\" + f.getName();
            String parent = "@@" + user + "\\Solar Diagnostics";
            Entry e = new Entry(virtual, parent, f.getName(), f, f.length(),
                    "log", 0, 0);
            entries.add(e);
            byVirtualPath.put(normalizePath(virtual), f);
            List<Entry> list = byDir.get(parent);
            if (list == null) {
                list = new ArrayList<Entry>();
                byDir.put(parent, list);
            }
            list.add(e);
        }
    }

    private void scanRoot(String user, String libName, File root, File dir,
            List<Entry> outEntries, Map<String, File> outByVirtualPath,
            Map<String, Integer> cachedDurationSecByPath) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                scanRoot(user, libName, root, f, outEntries, outByVirtualPath, cachedDurationSecByPath);
            } else {
                addShareableFile(user, libName, root, f, outEntries, outByVirtualPath,
                        cachedDurationSecByPath);
            }
        }
    }

    private static void addShareableFile(String user, String libName, File root, File f,
            List<Entry> outEntries, Map<String, File> outByVirtualPath,
            Map<String, Integer> cachedDurationSecByPath) {
        if (f == null || !f.isFile() || !isShareableAudio(f.getName())) return;
        String rel = relativize(root, f);
        String virtual = "@@" + user + "\\" + libName + "\\" + rel.replace('/', '\\');
        int slash = virtual.lastIndexOf('\\');
        String parent = slash > 0 ? virtual.substring(0, slash) : virtual;
        String name = f.getName();
        long size = f.length();
        String ext = extension(name);
        int[] meta = readAudioMetadata(f, cachedDurationSecByPath);
        Entry e = new Entry(virtual, parent, name, f, size, ext, meta[0], meta[1]);
        outEntries.add(e);
        outByVirtualPath.put(normalizePath(virtual), f);
    }

    private static String relativize(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (filePath.startsWith(rootPath)) {
            String rel = filePath.substring(rootPath.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return rel;
        }
        return file.getName();
    }

    static boolean isShareableAudio(String name) {
        return PodcastLibrary.isAudioFileName(name);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    static String normalizePath(String path) {
        if (path == null) return "";
        return path.replace('/', '\\').toLowerCase(Locale.US);
    }

    public synchronized int fileCount() {
        return entries.size();
    }

    public synchronized int dirCount() {
        return byDir.size();
    }

    public synchronized File resolve(String virtualPath) {
        if (virtualPath == null) return null;
        File f = byVirtualPath.get(normalizePath(virtualPath));
        if (f != null && f.isFile()) return f;
        String norm = normalizePath(virtualPath);
        for (Map.Entry<String, File> e : byVirtualPath.entrySet()) {
            if (e.getKey().endsWith(norm) || norm.endsWith(e.getKey())) {
                if (e.getValue().isFile()) return e.getValue();
            }
        }
        return null;
    }

    /** Uncompressed peer code 5 body (before zlib). */
    public synchronized byte[] buildShareListUncompressed() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(Integer.reverseBytes(byDir.size()));
        for (Map.Entry<String, List<Entry>> dirEntry : byDir.entrySet()) {
            writeString(out, dirEntry.getKey());
            List<Entry> files = dirEntry.getValue();
            out.writeInt(Integer.reverseBytes(files.size()));
            for (Entry e : files) {
                out.writeByte(1);
                writeString(out, e.virtualPath);
                writeFileSize(out, e.size);
                writeObsoleteExtField(out);
                writeFileAttributes(out, e);
            }
        }
        out.writeInt(Integer.reverseBytes(0));
        out.writeInt(Integer.reverseBytes(0));
        return bos.toByteArray();
    }

    public synchronized byte[] buildFolderContentsUncompressed(int token, String folder) throws IOException {
        String normFolder = normalizePath(folder);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(Integer.reverseBytes(token));
        writeString(out, folder != null ? folder : "");
        List<Entry> direct = byDir.get(folder);
        if (direct == null) {
            for (Map.Entry<String, List<Entry>> e : byDir.entrySet()) {
                if (normalizePath(e.getKey()).equals(normFolder)) {
                    direct = e.getValue();
                    break;
                }
            }
        }
        Map<String, List<Entry>> subDirs = new LinkedHashMap<String, List<Entry>>();
        if (direct != null) {
            subDirs.put(folder, direct);
        } else if (normFolder.length() > 0) {
            for (Map.Entry<String, List<Entry>> e : byDir.entrySet()) {
                if (normalizePath(e.getKey()).startsWith(normFolder)) {
                    subDirs.put(e.getKey(), e.getValue());
                }
            }
        }
        out.writeInt(Integer.reverseBytes(subDirs.size()));
        for (Map.Entry<String, List<Entry>> sub : subDirs.entrySet()) {
            writeString(out, sub.getKey());
            List<Entry> files = sub.getValue();
            out.writeInt(Integer.reverseBytes(files.size()));
            for (Entry e : files) {
                out.writeByte(1);
                writeString(out, e.virtualPath);
                writeFileSize(out, e.size);
                writeObsoleteExtField(out);
                writeFileAttributes(out, e);
            }
        }
        return bos.toByteArray();
    }

    private static int[] readAudioMetadata(File file, Map<String, Integer> cachedDurationSecByPath) {
        int[] out = new int[] { 0, 0 };
        if (file == null || !file.isFile()) return out;
        if (cachedDurationSecByPath != null) {
            Integer cached = cachedDurationSecByPath.get(
                    file.getAbsolutePath().toLowerCase(Locale.US));
            if (cached != null && cached > 0) {
                out[1] = cached;
                return out;
            }
        }
        return readAudioMetadata(file);
    }

    private static int[] readAudioMetadata(File file) {
        int[] out = new int[] { 0, 0 };
        if (file == null || !file.isFile()) return out;
        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(file.getAbsolutePath());
            String br = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (br != null) {
                try {
                    out[0] = Integer.parseInt(br);
                } catch (NumberFormatException ignored) {}
            }
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                try {
                    int ms = Integer.parseInt(dur);
                    if (ms > 0) out[1] = ms / 1000;
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {
        } finally {
            if (mmr != null) {
                try {
                    mmr.release();
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    private static void writeObsoleteExtField(DataOutputStream out) throws IOException {
        out.writeInt(Integer.reverseBytes(0));
    }

    private static void writeFileAttributes(DataOutputStream out, Entry e) throws IOException {
        int count = 0;
        if (e.bitrate > 0) count += 2;
        if (e.duration > 0) count++;
        out.writeInt(Integer.reverseBytes(count));
        if (e.bitrate > 0) {
            out.writeInt(Integer.reverseBytes(0));
            out.writeInt(Integer.reverseBytes(e.bitrate));
            out.writeInt(Integer.reverseBytes(2));
            out.writeInt(Integer.reverseBytes(1));
        }
        if (e.duration > 0) {
            out.writeInt(Integer.reverseBytes(1));
            out.writeInt(Integer.reverseBytes(e.duration));
        }
    }

    public static byte[] zlibCompress(byte[] raw) {
        Deflater def = new Deflater();
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length);
        byte[] buf = new byte[8192];
        while (!def.finished()) {
            int n = def.deflate(buf);
            if (n > 0) bos.write(buf, 0, n);
        }
        def.end();
        return bos.toByteArray();
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        if (s == null) s = "";
        byte[] raw = s.getBytes("UTF-8");
        out.writeInt(Integer.reverseBytes(raw.length));
        out.write(raw);
    }

    private static void writeFileSize(DataOutputStream out, long size) throws IOException {
        if (size < 0) size = 0;
        if (size <= 0xffffffffL) {
            out.writeInt(Integer.reverseBytes((int) size));
            out.writeInt(Integer.reverseBytes(0xffffffff));
        } else {
            out.writeLong(Long.reverseBytes(size));
        }
    }

    public synchronized List<Entry> entriesForTest() {
        return Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }
}
