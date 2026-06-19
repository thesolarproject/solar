package com.solar.launcher.soulseek;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** ponytail: filename → four one-tap Reach re-search queries from title/artist phrases. */
public final class SoulseekSearchSuggestions {
    private static final int MAX_RESEARCH = 4;

    private static final Pattern LEADING_TRACK = Pattern.compile("^\\d{1,4}[\\s.\\-_)]*");
    private static final Pattern PHRASE_HYPHEN = Pattern.compile("\\s-\\s");
    private static final Pattern PHRASE_AMP = Pattern.compile("\\s&\\s");

    private SoulseekSearchSuggestions() {}

    public static String findOtherCopies(String filename) {
        ParsedFilename parsed = parseFilename(filename);
        if (parsed.phrases.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parsed.phrases.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(parsed.phrases.get(i));
        }
        return sb.toString().trim();
    }

    public static String findOtherCopies(SoulseekClient.Result result) {
        if (result == null) return "";
        String q = findOtherCopies(result.filename);
        return q.isEmpty() ? "" : q;
    }

    /** Ordered one-tap re-search kit (max 4 for title/artist filenames). */
    public static List<String> reSearchQueries(String filename) {
        return reSearchQueries(filename, MAX_RESEARCH);
    }

    public static List<String> reSearchQueries(SoulseekClient.Result result) {
        if (result == null) return new ArrayList<String>();
        return reSearchQueries(result.filename, MAX_RESEARCH);
    }

    static List<String> reSearchQueries(String filename, int max) {
        ParsedFilename parsed = parseFilename(filename);
        List<String> phrases = parsed.phrases;
        List<String> out = new ArrayList<String>();
        if (phrases.size() >= 3) {
            addReSearchPair(out, phrases.get(0), phrases.get(phrases.size() - 1), max);
        } else if (phrases.size() == 2) {
            addReSearchPair(out, phrases.get(0), phrases.get(1), max);
        } else if (phrases.size() == 1) {
            String p = phrases.get(0);
            addIfValidOrdered(out, sentenceCase(p));
            addIfValidOrdered(out, p);
        }
        while (out.size() > max) out.remove(out.size() - 1);
        return out;
    }

    public static List<String> suggestedQueries(String filename) {
        return reSearchQueries(filename);
    }

    public static List<String> suggestedQueries(SoulseekClient.Result result) {
        return reSearchQueries(result);
    }

    /** ID3 tags → re-search permutations (artist/album/title/genre combos). */
    public static List<String> suggestionsFromId3(String title, String artist, String album, String genre) {
        List<String> phrases = new ArrayList<String>();
        addPhrase(phrases, artist);
        addPhrase(phrases, album);
        addPhrase(phrases, title);
        addPhrase(phrases, genre);
        List<String> out = new ArrayList<String>();
        if (phrases.size() >= 2) {
            for (int i = 0; i < phrases.size() && out.size() < MAX_RESEARCH; i++) {
                for (int j = i + 1; j < phrases.size() && out.size() < MAX_RESEARCH; j++) {
                    addReSearchPair(out, phrases.get(i), phrases.get(j), MAX_RESEARCH);
                }
            }
        } else if (phrases.size() == 1) {
            addIfValidOrdered(out, sentenceCase(phrases.get(0)));
            addIfValidOrdered(out, phrases.get(0));
        }
        while (out.size() > MAX_RESEARCH) out.remove(out.size() - 1);
        return out;
    }

    private static void addPhrase(List<String> phrases, String s) {
        if (s == null) return;
        String t = s.trim();
        if (t.isEmpty() || "Unknown Artist".equalsIgnoreCase(t) || "Unknown Album".equalsIgnoreCase(t)) return;
        String key = t.toLowerCase(Locale.US);
        for (String p : phrases) {
            if (p.toLowerCase(Locale.US).equals(key)) return;
        }
        phrases.add(t);
    }

    static List<String> suggestedQueries(String filename, int max) {
        return reSearchQueries(filename, max);
    }

    /** Pool of related queries from top search results (deduped, excludes current query). */
    public static List<String> similarFromResults(List<SoulseekClient.Result> results,
            String excludeQuery, int poolMax) {
        List<String> pool = new ArrayList<String>();
        if (results == null || results.isEmpty() || poolMax <= 0) return pool;
        Set<String> seen = new HashSet<String>();
        String exclude = excludeQuery != null ? excludeQuery.trim().toLowerCase(Locale.US) : "";
        int n = Math.min(30, results.size());
        for (int i = 0; i < n; i++) {
            SoulseekClient.Result r = results.get(i);
            if (r == null) continue;
            for (String q : reSearchQueries(r.filename, MAX_RESEARCH)) {
                String key = q.toLowerCase(Locale.US);
                if (key.length() < 2 || key.equals(exclude)) continue;
                if (seen.add(key)) pool.add(q);
                if (pool.size() >= poolMax) return pool;
            }
        }
        return pool;
    }

    /** Up to count queries from pool starting at offset (wraps). */
    public static List<String> rotatedSlice(List<String> pool, int offset, int count) {
        List<String> out = new ArrayList<String>();
        if (pool == null || pool.isEmpty() || count <= 0) return out;
        int start = offset % pool.size();
        for (int i = 0; i < count && i < pool.size(); i++) {
            out.add(pool.get((start + i) % pool.size()));
        }
        return out;
    }

    /** Title-only search form: first char upper, remainder lower. */
    static String sentenceCase(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() == 1) return s.toUpperCase(Locale.US);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.US);
    }

    private static void addReSearchPair(List<String> out, String a, String b, int max) {
        addIfValidOrdered(out, sentenceCase(a));
        if (out.size() >= max) return;
        addIfValidOrdered(out, b);
        if (out.size() >= max) return;
        addIfValidOrdered(out, a + " " + b);
        if (out.size() >= max) return;
        addIfValidOrdered(out, b + " " + a);
    }

    /** Basename-only phrase parse; ignores path folders and @@username segments. */
    static ParsedFilename parseFilename(String filename) {
        ParsedFilename parsed = new ParsedFilename();
        if (filename == null || filename.isEmpty()) return parsed;

        List<String> bracketTokens = new ArrayList<String>();
        String base = extractBracketTokens(filename, bracketTokens);

        int slash = Math.max(base.lastIndexOf('\\'), base.lastIndexOf('/'));
        String basename = slash >= 0 ? base.substring(slash + 1) : base;

        int dot = basename.lastIndexOf('.');
        if (dot > 0) basename = basename.substring(0, dot);

        basename = LEADING_TRACK.matcher(basename).replaceFirst("").trim();
        basename = basename.replace('_', ' ').trim();
        if (basename.isEmpty()) return parsed;

        parsed.phrases = splitPhrases(basename);
        return parsed;
    }

    private static List<String> splitPhrases(String basename) {
        String[] raw;
        if (basename.contains(" - ")) {
            raw = PHRASE_HYPHEN.split(basename, -1);
        } else if (basename.contains(" & ")) {
            raw = PHRASE_AMP.split(basename, -1);
        } else {
            String one = cleanPhrase(basename);
            List<String> single = new ArrayList<String>();
            if (one != null) single.add(one);
            return single;
        }

        List<String> out = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (String part : raw) {
            String cleaned = cleanPhrase(part);
            if (cleaned == null) continue;
            String key = cleaned.toLowerCase(Locale.US);
            if (seen.add(key)) out.add(cleaned);
        }
        return out;
    }

    static final class ParsedFilename {
        List<String> phrases = new ArrayList<String>();
    }

    private static String extractBracketTokens(String text, List<String> out) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') {
                char close = c == '(' ? ')' : ']';
                int end = text.indexOf(close, i + 1);
                if (end > i + 1) {
                    out.add(text.substring(i + 1, end));
                    i = end + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String cleanPhrase(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        t = t.replaceAll("(?i)\\b320\\s*kbps\\b", "").trim();
        t = t.replaceAll("(?i)\\b(flac|mp3|ape|wav|ogg|m4a|aac)\\b", "").trim();
        if (t.length() < 2) return null;
        if (t.matches("(?i)\\d+")) return null;
        return t;
    }

    private static void addIfValidOrdered(List<String> out, String query) {
        if (query == null) return;
        String q = query.trim();
        if (q.length() < 2) return;
        for (String existing : out) {
            if (existing.equalsIgnoreCase(q)) return;
        }
        out.add(q);
    }
}
