package com.solar.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Split combined ID3 artist strings into individual credited artists. */
public final class ArtistParser {
    private static final Pattern LIST_SEP = Pattern.compile("\\s*[;,]\\s*");
    /** feat., ft., featuring, w/, vs., x — common tag and filename conventions. */
    private static final Pattern FEAT_SEP = Pattern.compile(
            "(?i)\\s+(?:feat\\.?|ft\\.?|featuring\\.?|w/|vs\\.?|versus|x)\\s+");
    private static final Pattern SLASH_SEP = Pattern.compile("\\s*/\\s*");
    private static final Pattern AMP_SEP = Pattern.compile("\\s*&\\s*");
    private static final Pattern PAREN_COLLAB = Pattern.compile(
            "(?i)\\((?:feat\\.?|ft\\.?|featuring\\.?|w/)\\s+([^)]+)\\)");

    private ArtistParser() {}

    public static List<String> splitArtists(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        String t = raw.trim();
        if (t.isEmpty() || isUnknown(t)) return out;

        if (ArtistSeparatorCatalog.get().isNoSplit(t)) {
            addUnique(out, new HashSet<String>(), t);
            return out;
        }

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
            return artistField != null && (artistField.trim().equalsIgnoreCase(q)
                    || ArtistNames.equals(artistField, q));
        }
        for (String p : parts) {
            if (p.equalsIgnoreCase(q) || ArtistNames.equals(p, q)) return true;
        }
        return false;
    }

    public static String primaryArtist(String raw) {
        List<String> parts = splitArtists(raw);
        return parts.isEmpty() ? (raw != null ? raw.trim() : "") : parts.get(0);
    }

    private static void addSplitSegment(List<String> out, Set<String> seen, String segment) {
        if (segment == null) return;
        List<String> parenGuests = new ArrayList<String>();
        String seg = peelParentheticalCollabs(segment.trim(), parenGuests);
        if (seg.isEmpty() && parenGuests.isEmpty()) return;

        if (!seg.isEmpty()) {
            addSplitSegmentBody(out, seen, seg);
        }
        for (String guest : parenGuests) {
            addSplitSegment(out, seen, guest);
        }
    }

    private static void addSplitSegmentBody(List<String> out, Set<String> seen, String seg) {
        if (ArtistSeparatorCatalog.get().isNoSplit(seg)) {
            addUnique(out, seen, seg);
            return;
        }

        String[] featParts = FEAT_SEP.split(seg);
        if (featParts.length > 1) {
            for (String part : featParts) {
                addSplitSegmentBody(out, seen, part.trim());
            }
            return;
        }

        if (ArtistSeparatorCatalog.get().splitAmpersand()) {
            String[] ampParts = AMP_SEP.split(seg);
            if (ampParts.length > 1) {
                for (String part : ampParts) {
                    addSplitSegmentBody(out, seen, part.trim());
                }
                return;
            }
        }

        List<String> slashParts = splitSlashSegment(seg);
        if (slashParts.size() > 1) {
            for (String part : slashParts) {
                addSplitSegmentBody(out, seen, part);
            }
            return;
        }

        addUnique(out, seen, seg);
    }

    /** Remove "(feat. …)" segments; collected guests are appended after the main artist. */
    private static String peelParentheticalCollabs(String seg, List<String> guests) {
        Matcher m = PAREN_COLLAB.matcher(seg);
        if (!m.find()) return seg;
        StringBuffer sb = new StringBuffer();
        do {
            for (String guest : LIST_SEP.split(m.group(1))) {
                String cleaned = cleanName(guest);
                if (cleaned != null) guests.add(cleaned);
            }
            m.appendReplacement(sb, " ");
        } while (m.find());
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * Split on / (with or without surrounding spaces). Keeps band names like AC/DC intact
     * when both sides are short single tokens without spaces.
     */
    private static List<String> splitSlashSegment(String seg) {
        if (seg == null || seg.indexOf('/') < 0) {
            List<String> one = new ArrayList<String>();
            one.add(seg);
            return one;
        }
        String[] parts = SLASH_SEP.split(seg);
        if (parts.length <= 1) {
            List<String> one = new ArrayList<String>();
            one.add(seg);
            return one;
        }
        List<String> merged = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String left = parts[i].trim();
            if (left.isEmpty()) continue;
            if (i + 1 < parts.length) {
                String right = parts[i + 1].trim();
                if (!right.isEmpty() && shouldMergeSlashPair(left, right)) {
                    merged.add(left + "/" + right);
                    i++;
                    continue;
                }
            }
            merged.add(left);
        }
        if (merged.isEmpty()) {
            merged.add(seg);
        }
        return merged;
    }

    /** AC/DC — merge only when both sides look like a single short token. */
    private static boolean shouldMergeSlashPair(String left, String right) {
        if (left.contains(" ") || right.contains(" ")) return false;
        return left.length() <= 4 && right.length() <= 4;
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
