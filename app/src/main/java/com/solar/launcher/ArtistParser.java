package com.solar.launcher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Split combined ID3 artist strings into individual credited artists. */
public final class ArtistParser {
    private static final Pattern LIST_SEP = Pattern.compile("\\s*[;,]\\s*");
    private static final Pattern FEAT_SEP = Pattern.compile(
            "(?i)\\s+(?:feat\\.?|ft\\.?|featuring|with|vs\\.?|versus|x)\\s+");
    private static final Pattern SLASH_SEP = Pattern.compile("\\s+/\\s+");

    private ArtistParser() {}

    public static List<String> splitArtists(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        String t = raw.trim();
        if (t.isEmpty() || isUnknown(t)) return out;

        Set<String> seen = new HashSet<String>();
        for (String segment : LIST_SEP.split(t)) {
            addSplitSegment(out, seen, segment);
        }
        if (out.isEmpty()) {
            addSplitSegment(out, seen, t);
        }
        return out;
    }

    public static boolean containsArtist(String artistField, String query) {
        if (query == null || query.trim().isEmpty()) return false;
        String q = query.trim();
        List<String> parts = splitArtists(artistField);
        if (parts.isEmpty()) {
            return artistField != null && artistField.trim().equalsIgnoreCase(q);
        }
        for (String p : parts) {
            if (p.equalsIgnoreCase(q)) return true;
        }
        return false;
    }

    public static String primaryArtist(String raw) {
        List<String> parts = splitArtists(raw);
        return parts.isEmpty() ? (raw != null ? raw.trim() : "") : parts.get(0);
    }

    private static void addSplitSegment(List<String> out, Set<String> seen, String segment) {
        if (segment == null) return;
        String seg = segment.trim();
        if (seg.isEmpty()) return;

        String[] featParts = FEAT_SEP.split(seg);
        if (featParts.length > 1) {
            for (String part : featParts) addUnique(out, seen, part);
            return;
        }

        String[] slashParts = SLASH_SEP.split(seg);
        if (slashParts.length > 1) {
            for (String part : slashParts) addUnique(out, seen, part);
            return;
        }

        addUnique(out, seen, seg);
    }

    private static void addUnique(List<String> out, Set<String> seen, String raw) {
        String cleaned = cleanName(raw);
        if (cleaned == null) return;
        String key = cleaned.toLowerCase(Locale.US);
        if (seen.add(key)) out.add(cleaned);
    }

    private static String cleanName(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        while (t.startsWith("\"") && t.endsWith("\"") && t.length() > 1) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.isEmpty() || isUnknown(t)) return null;
        return t;
    }

    private static boolean isUnknown(String t) {
        return "Unknown Artist".equalsIgnoreCase(t) || "Various Artists".equalsIgnoreCase(t);
    }
}
