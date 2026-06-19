package com.solar.launcher.soulseek;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ponytail: expand one user query into a small deduped set for parallel Soulseek search
 * (accents, acronym punctuation). Max {@link #MAX_VARIANTS} wire messages per search.
 */
public final class SoulseekSearchVariants {
    static final int MAX_VARIANTS = 6;

    private SoulseekSearchVariants() {}

    public static List<String> expand(String query) {
        if (query == null) return new ArrayList<String>();
        String q = query.trim();
        if (q.isEmpty()) return new ArrayList<String>();

        Set<String> out = new LinkedHashSet<String>();
        addVariant(out, q);

        String folded = stripAccents(q);
        if (!folded.equals(q)) {
            addVariant(out, folded);
        } else {
            maybeAddAccentedGuess(out, q);
        }

        addAcronymVariants(out, q);

        List<String> list = new ArrayList<String>(out);
        while (list.size() > MAX_VARIANTS) list.remove(list.size() - 1);
        return list;
    }

    static String stripAccents(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) sb.append(c);
        }
        return sb.toString();
    }

    private static void addAcronymVariants(Set<String> out, String q) {
        String compact = compactLetters(q);
        if (compact.length() < 2 || compact.length() > 6) return;

        String lower = compact.toLowerCase(Locale.US);
        String upper = compact.toUpperCase(Locale.US);
        boolean hadSeparators = hasSeparators(q);

        addVariant(out, lower);
        if (!upper.equals(lower)) addVariant(out, upper);

        if (hadSeparators) {
            addVariant(out, compact);
        }

        String dotted = dotSeparate(lower);
        if (dotted != null) addVariant(out, dotted);
        if (dotted != null && !dotted.equalsIgnoreCase(upper)) {
            addVariant(out, dotSeparate(upper));
        }
    }

    private static boolean hasSeparators(String q) {
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '.' || c == '-' || c == '_' || c == ' ') return true;
        }
        return false;
    }

    private static String compactLetters(String q) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (Character.isLetter(c)) sb.append(c);
        }
        return sb.toString();
    }

    private static String dotSeparate(String letters) {
        if (letters.length() < 2 || letters.length() > 5) return null;
        StringBuilder sb = new StringBuilder(letters.length() * 2 - 1);
        for (int i = 0; i < letters.length(); i++) {
            if (i > 0) sb.append('.');
            sb.append(letters.charAt(i));
        }
        return sb.toString();
    }

  /** Guess café from cafe when the query is plain ASCII (one vowel swap max). */
    private static void maybeAddAccentedGuess(Set<String> out, String q) {
        if (!isAsciiText(q)) return;
        String lower = q.toLowerCase(Locale.US);
        int len = lower.length();
        if (len < 4) return;
        char last = lower.charAt(len - 1);
        if (last == 'e') {
            addVariant(out, q.substring(0, len - 1) + accentFor(q.charAt(len - 1), 'é', 'É'));
        } else if (last == 'a') {
            addVariant(out, q.substring(0, len - 1) + accentFor(q.charAt(len - 1), 'á', 'Á'));
        } else if (last == 'o') {
            addVariant(out, q.substring(0, len - 1) + accentFor(q.charAt(len - 1), 'ó', 'Ó'));
        }
    }

    private static char accentFor(char source, char lowerAccent, char upperAccent) {
        return Character.isUpperCase(source) ? upperAccent : lowerAccent;
    }

    private static boolean isAsciiText(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    private static void addVariant(Set<String> out, String v) {
        if (v == null) return;
        String t = v.trim();
        if (t.isEmpty()) return;
        for (String existing : out) {
            if (existing.equalsIgnoreCase(t)) return;
        }
        out.add(t);
    }
}
