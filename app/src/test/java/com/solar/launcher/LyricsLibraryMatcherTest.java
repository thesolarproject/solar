package com.solar.launcher;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

/** LRCGET-in-Solar: sidecar write path + TrackLyrics sidecar detection (no network, no Android). */
public class LyricsLibraryMatcherTest {

    @Test
    public void writeSidecarCreatesLrcNextToAudioAndResolves() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "solar-lrcget-test");
        if (dir.exists()) deleteTree(dir);
        if (!dir.mkdirs()) throw new AssertionError("mkdir");
        File mp3 = new File(dir, "my song.mp3");
        writeBytes(mp3, new byte[] {0, 1, 2, 3, 4, 5});
        try {
            File lrc = LyricsLibraryMatcher.writeSidecar(mp3, "[00:10.00]Hello\n[00:20.00]World\n");
            if (lrc == null || !lrc.isFile()) throw new AssertionError("sidecar not written");
            if (!"my song.lrc".equals(lrc.getName())) throw new AssertionError("name=" + lrc.getName());
            if (!TrackLyrics.hasSidecar(mp3)) throw new AssertionError("hasSidecar should be true");
            TrackLyrics.Document doc = TrackLyrics.resolve(mp3);
            if (doc.isEmpty()) throw new AssertionError("resolve empty");
            if (!doc.synced) throw new AssertionError("expected synced");
            // Overwrite must replace cleanly (no .part leftover).
            LyricsLibraryMatcher.writeSidecar(mp3, "[00:01.00]New\n");
            File part = new File(dir, "my song.lrc.part");
            if (part.isFile()) throw new AssertionError(".part leftover");
            if (!"[00:01.00]New\n".equals(readUtf8(lrc))) throw new AssertionError("overwrite failed");
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void writeSidecarRejectsMissingParentOrNull() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "solar-lrcget-null-test");
        if (dir.exists()) deleteTree(dir);
        if (!dir.mkdirs()) throw new AssertionError("mkdir");
        try {
            File orphan = new File(dir, "orphan.mp3"); // not created — no parent dir entry issue
            if (LyricsLibraryMatcher.writeSidecar(orphan, "x") != null) {
                throw new AssertionError("should reject missing file parent dir");
            }
            if (LyricsLibraryMatcher.writeSidecar(new File(dir, "a.mp3"), null) != null) {
                throw new AssertionError("null content rejected");
            }
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void hasSidecarFalseWhenNoneExists() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "solar-lrcget-none-test");
        if (dir.exists()) deleteTree(dir);
        if (!dir.mkdirs()) throw new AssertionError("mkdir");
        try {
            File mp3 = new File(dir, "song.mp3");
            writeBytes(mp3, new byte[] {1, 2, 3});
            if (TrackLyrics.hasSidecar(mp3)) throw new AssertionError("no sidecar yet");
            if (TrackLyrics.hasSidecar(new File(dir, "missing.mp3"))) {
                throw new AssertionError("missing audio");
            }
            if (TrackLyrics.hasSidecar(null)) throw new AssertionError("null audio");
        } finally {
            deleteTree(dir);
        }
    }

    private static void writeBytes(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static String readUtf8(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void deleteTree(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) deleteTree(k);
                else k.delete();
            }
        }
        dir.delete();
    }
}
