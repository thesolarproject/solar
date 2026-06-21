package com.solar.launcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Canonical album titles when ID3 tags disagree only by letter case. */
public final class AlbumNames {
    private AlbumNames() {}

    public static String matchKey(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.US);
    }

    public static boolean equals(String a, String b) {
        if (a == null || b == null) return false;
        String ka = matchKey(a);
        String kb = matchKey(b);
        return !ka.isEmpty() && ka.equals(kb);
    }

    /**
     * Pick the display form for a case-insensitive album group.
     * The most frequent exact spelling wins; when top counts tie, fall back to title case.
     */
    public static String chooseCanonical(Map<String, Integer> variantCounts) {
        if (variantCounts == null || variantCounts.isEmpty()) return "";
        int maxCount = 0;
        for (Map.Entry<String, Integer> e : variantCounts.entrySet()) {
            String variant = e.getKey();
            if (variant == null || variant.trim().isEmpty()) continue;
            int count = e.getValue() != null ? e.getValue() : 0;
            if (count > maxCount) maxCount = count;
        }
        if (maxCount <= 0) return "";

        List<String> leaders = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : variantCounts.entrySet()) {
            String variant = e.getKey();
            if (variant == null || variant.trim().isEmpty()) continue;
            int count = e.getValue() != null ? e.getValue() : 0;
            if (count == maxCount) leaders.add(variant);
        }
        if (leaders.isEmpty()) return "";
        if (leaders.size() == 1) return leaders.get(0);
        return toTitleCase(pickRepresentative(leaders));
    }

    /** Build case-insensitive album key → winning display title from raw tag values. */
    public static Map<String, String> canonicalTitles(Iterable<String> albumTitles) {
        Map<String, Map<String, Integer>> groups = new HashMap<String, Map<String, Integer>>();
        if (albumTitles != null) {
            for (String raw : albumTitles) {
                if (raw == null) continue;
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || isUnknown(trimmed)) continue;
                String key = matchKey(trimmed);
                Map<String, Integer> variants = groups.get(key);
                if (variants == null) {
                    variants = new HashMap<String, Integer>();
                    groups.put(key, variants);
                }
                Integer prev = variants.get(trimmed);
                variants.put(trimmed, prev == null ? 1 : prev + 1);
            }
        }
        Map<String, String> out = new HashMap<String, String>();
        for (Map.Entry<String, Map<String, Integer>> e : groups.entrySet()) {
            Map<String, Integer> variants = e.getValue();
            if (variants == null || variants.size() <= 1) continue;
            String chosen = chooseCanonical(variants);
            if (!chosen.isEmpty()) out.put(e.getKey(), chosen);
        }
        return out;
    }

    public static String normalizeDisplay(String raw, Map<String, String> canonicalByKey) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || isUnknown(trimmed)) return trimmed;
        if (canonicalByKey == null) return trimmed;
        String canon = canonicalByKey.get(matchKey(trimmed));
        return canon != null ? canon : trimmed;
    }

    /** Capitalize the first letter of each word; preserve punctuation and spacing. */
    public static String toTitleCase(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(t.length());
        boolean capNext = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(capNext ? Character.toUpperCase(c) : Character.toLowerCase(c));
                capNext = false;
            } else {
                sb.append(c);
                if (isWordBoundary(c)) capNext = true;
            }
        }
        return sb.toString();
    }

    private static boolean isWordBoundary(char c) {
        return c == ' ' || c == '\t' || c == '-' || c == '/' || c == ',' || c == '&'
                || c == ':' || c == '(' || c == ')';
    }

    /** Prefer the variant that preserves the most punctuation/structure for title-casing. */
    private static String pickRepresentative(List<String> leaders) {
        String best = leaders.get(0);
        for (int i = 1; i < leaders.size(); i++) {
            String candidate = leaders.get(i);
            if (candidate.length() > best.length()) best = candidate;
        }
        return best;
    }

    private static boolean isUnknown(String name) {
        return "Unknown Album".equalsIgnoreCase(name);
    }
}
