package com.solar.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves lyrics for a local track — sidecar .lrc first, then embedded tags.
 * 2026-07-06 — unified document for sync + plain display.
 */
public final class TrackLyrics {
    public static final class Document {
        public final List<LrcParser.Line> lines;
        public final boolean synced;
        public final String plainText;

        Document(List<LrcParser.Line> lines, boolean synced, String plainText) {
            this.lines = lines != null ? lines : new ArrayList<LrcParser.Line>();
            this.synced = synced;
            this.plainText = plainText != null ? plainText : "";
        }

        public boolean isEmpty() {
            return lines.isEmpty() && plainText.isEmpty();
        }
    }

    private TrackLyrics() {}

    /** Resolve lyrics for audio file; empty document when none found. */
    public static Document resolve(File audioFile) {
        if (audioFile == null || !audioFile.isFile()) return empty();
        Document fromLrc = readSidecarLrc(audioFile);
        if (fromLrc != null && !fromLrc.isEmpty()) return fromLrc;
        String embedded = Id3UsltReader.readFromAudioFile(audioFile);
        if (embedded != null && !embedded.trim().isEmpty()) {
            List<LrcParser.Line> lines = new ArrayList<LrcParser.Line>();
            lines.add(new LrcParser.Line(-1L, embedded.trim()));
            return new Document(lines, false, embedded.trim());
        }
        return empty();
    }

    /** True when a .lrc sidecar file exists next to the audio (no embedded-tag read). */
    public static boolean hasSidecar(File audioFile) {
        if (audioFile == null || !audioFile.isFile()) return false;
        return sidecarFor(audioFile) != null;
    }

    private static Document readSidecarLrc(File audioFile) {
        File lrc = sidecarFor(audioFile);
        if (lrc == null || !lrc.isFile()) return null;
        String raw = readUtf8(lrc);
        if (raw == null || raw.trim().isEmpty()) return null;
        List<LrcParser.Line> lines = LrcParser.parse(raw);
        boolean synced = LrcParser.hasSyncedTimestamps(lines);
        String plain = synced ? "" : LrcParser.joinPlainText(lines);
        return new Document(lines, synced, plain);
    }

    private static File sidecarFor(File audioFile) {
        String name = audioFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File parent = audioFile.getParentFile();
        if (parent == null) return null;
        File exact = new File(parent, base + ".lrc");
        if (exact.isFile()) return exact;
        File[] siblings = parent.listFiles();
        if (siblings == null) return null;
        String want = base.toLowerCase(Locale.US);
        for (File f : siblings) {
            if (!f.isFile()) continue;
            String n = f.getName();
            if (!n.toLowerCase(Locale.US).endsWith(".lrc")) continue;
            int d = n.lastIndexOf('.');
            String b = d > 0 ? n.substring(0, d) : n;
            if (b.equalsIgnoreCase(base) || b.toLowerCase(Locale.US).equals(want)) return f;
        }
        return null;
    }

    private static String readUtf8(File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
        }
    }

    private static Document empty() {
        return new Document(new ArrayList<LrcParser.Line>(), false, "");
    }
}
