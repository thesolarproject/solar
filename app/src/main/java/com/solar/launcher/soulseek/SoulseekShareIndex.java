package com.solar.launcher.soulseek;

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

        Entry(String virtualPath, String dir, String name, File file, long size, String ext) {
            this.virtualPath = virtualPath;
            this.dir = dir;
            this.name = name;
            this.file = file;
            this.size = size;
            this.ext = ext;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private final Map<String, File> byVirtualPath = new HashMap<String, File>();
    private final Map<String, List<Entry>> byDir = new LinkedHashMap<String, List<Entry>>();

    public static SoulseekShareIndex empty() {
        return new SoulseekShareIndex();
    }

    public synchronized void scan(String username, File musicRoot, File podcastRoot) {
        entries.clear();
        byVirtualPath.clear();
        byDir.clear();
        if (username == null || username.trim().isEmpty()) return;
        String user = username.trim();
        if (musicRoot != null && musicRoot.isDirectory()) {
            scanRoot(user, "Music", musicRoot, musicRoot);
        }
        if (podcastRoot != null && podcastRoot.isDirectory()) {
            scanRoot(user, "Podcasts", podcastRoot, podcastRoot);
        }
        for (Entry e : entries) {
            List<Entry> list = byDir.get(e.dir);
            if (list == null) {
                list = new ArrayList<Entry>();
                byDir.put(e.dir, list);
            }
            list.add(e);
        }
    }

    private void scanRoot(String user, String libName, File root, File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                scanRoot(user, libName, root, f);
            } else if (isShareableAudio(f.getName())) {
                String rel = relativize(root, f);
                String virtual = "@@" + user + "\\" + libName + "\\" + rel.replace('/', '\\');
                int slash = virtual.lastIndexOf('\\');
                String parent = slash > 0 ? virtual.substring(0, slash) : virtual;
                String name = f.getName();
                long size = f.length();
                String ext = extension(name);
                Entry e = new Entry(virtual, parent, name, f, size, ext);
                entries.add(e);
                byVirtualPath.put(normalizePath(virtual), f);
            }
        }
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
                writeString(out, e.name);
                writeFileSize(out, e.size);
                writeString(out, e.ext);
                out.writeInt(Integer.reverseBytes(0));
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
                writeString(out, e.name);
                writeFileSize(out, e.size);
                writeString(out, e.ext);
                out.writeInt(Integer.reverseBytes(0));
            }
        }
        return bos.toByteArray();
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
