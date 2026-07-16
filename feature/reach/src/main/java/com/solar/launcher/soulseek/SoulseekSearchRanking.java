package com.solar.launcher.soulseek;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Query ↔ filename relevance for Reach search ranking and sparse-result fallbacks. */
public final class SoulseekSearchRanking {
    private static final int SCORE_TERM = 15;
    private static final int SCORE_ALL_TERMS = 60;
    private static final int SCORE_PHRASE_ALIGN = 40;
    private static final int SCORE_FOLDER = 25;
    private static final int SCORE_CONTIGUOUS = 80;
    private static final int PENALTY_MISSING_ARTIST = 200;
    private static final int MAX_FALLBACK_QUERIES = 2;

    private SoulseekSearchRanking() {}

    public static final class ParsedQuery {
        public final List<String> terms;
        public final String hintedArtist;
        public final String hintedTitle;
        public final String guessedArtist;
        public final String guessedTitle;

        ParsedQuery(List<String> terms, String hintedArtist, String hintedTitle,
                    String guessedArtist, String guessedTitle) {
            this.terms = terms;
            this.hintedArtist = hintedArtist;
            this.hintedTitle = hintedTitle;
            this.guessedArtist = guessedArtist;
            this.guessedTitle = guessedTitle;
        }
    }

    public static List<String> tokenizeQuery(String query) {
        return parseQuery(query).terms;
    }

    public static ParsedQuery parseQuery(String query) {
        List<String> terms = new ArrayList<String>();
        String hintedArtist = null;
        String hintedTitle = null;
        if (query == null) {
            return new ParsedQuery(terms, null, null, null, null);
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return new ParsedQuery(terms, null, null, null, null);
        }

        if (trimmed.contains(" - ")) {
            int idx = trimmed.indexOf(" - ");
            hintedArtist = trimmed.substring(0, idx).trim();
            hintedTitle = trimmed.substring(idx + 3).trim();
            addTerms(terms, hintedArtist);
            addTerms(terms, hintedTitle);
        } else {
            addTerms(terms, trimmed);
        }

        String guessedArtist = null;
        String guessedTitle = null;
        if (terms.size() >= 3) {
            guessedTitle = terms.get(terms.size() - 2) + " " + terms.get(terms.size() - 1);
            StringBuilder artist = new StringBuilder();
            for (int i = 0; i < terms.size() - 2; i++) {
                if (artist.length() > 0) artist.append(' ');
                artist.append(terms.get(i));
            }
            guessedArtist = artist.toString();
        }
        return new ParsedQuery(terms, hintedArtist, hintedTitle, guessedArtist, guessedTitle);
    }

    public static int relevanceScore(String query, String filename) {
        if (filename == null || filename.isEmpty()) return 0;
        ParsedQuery pq = parseQuery(query);
        if (pq.terms.isEmpty()) return 0;

        String haystack = filename.toLowerCase(Locale.US);
        int score = 0;
        int matched = 0;
        for (String term : pq.terms) {
            if (haystack.contains(term)) {
                score += SCORE_TERM;
                matched++;
            }
        }
        if (matched == pq.terms.size() && pq.terms.size() > 0) {
            score += SCORE_ALL_TERMS;
        }

        for (String folder : pathFolders(filename)) {
            String f = folder.toLowerCase(Locale.US);
            for (String term : pq.terms) {
                if (f.equals(term)) {
                    score += SCORE_FOLDER;
                    break;
                }
            }
        }

        SoulseekSearchSuggestions.ParsedFilename parsed =
                SoulseekSearchSuggestions.parseFilename(filename);
        String artist = pq.hintedArtist != null ? pq.hintedArtist : pq.guessedArtist;
        String title = pq.hintedTitle != null ? pq.hintedTitle : pq.guessedTitle;
        if (artist != null && title != null && parsed.phrases.size() >= 2) {
            String p0 = parsed.phrases.get(0).toLowerCase(Locale.US);
            String p1 = parsed.phrases.get(parsed.phrases.size() - 1).toLowerCase(Locale.US);
            String a = artist.toLowerCase(Locale.US);
            String t = title.toLowerCase(Locale.US);
            if ((p0.contains(a) || a.contains(p0)) && (p1.contains(t) || t.contains(p1))) {
                score += SCORE_PHRASE_ALIGN;
            } else if ((p0.contains(t) || t.contains(p0)) && (p1.contains(a) || a.contains(p1))) {
                score += SCORE_PHRASE_ALIGN;
            }
        } else if (artist != null && title != null && parsed.phrases.size() == 1) {
            String p = parsed.phrases.get(0).toLowerCase(Locale.US);
            String a = artist.toLowerCase(Locale.US);
            String t = title.toLowerCase(Locale.US);
            if (p.contains(a) && p.contains(t)) {
                score += SCORE_PHRASE_ALIGN;
            }
        }

        if (artist != null && title != null) {
            String contiguous = (artist + " " + title).toLowerCase(Locale.US);
            String basename = basenameNoExt(filename).toLowerCase(Locale.US);
            if (basename.contains(contiguous)) {
                score += SCORE_CONTIGUOUS;
            }
            String reversed = (title + " " + artist).toLowerCase(Locale.US);
            if (basename.contains(reversed)) {
                score += SCORE_CONTIGUOUS / 2;
            }
        }

        if (pq.terms.size() >= 3) {
            String requiredArtist = pq.hintedArtist != null ? pq.hintedArtist : pq.guessedArtist;
            if (requiredArtist != null && requiredArtist.length() > 0) {
                if (!haystack.contains(requiredArtist.toLowerCase(Locale.US))) {
                    score -= PENALTY_MISSING_ARTIST;
                }
            }
        }
        return score;
    }

    public static int compareByRelevance(SoulseekClient.Result a, SoulseekClient.Result b, String query) {
        return Integer.compare(relevanceScore(query, b.filename), relevanceScore(query, a.filename));
    }

    /** Relevance first, then Nicotine+-style download reliability. */
    public static int compareResults(SoulseekClient.Result a, SoulseekClient.Result b, String query) {
        int rel = compareByRelevance(a, b, query);
        if (rel != 0) return rel;
        return SoulseekClient.Result.compareByDownloadReliability(a, b);
    }

    /** When a broad title-only fallback ran, keep rows that still mention the guessed artist. */
    public static boolean passesBroadFallbackGate(String originalQuery, String filename) {
        ParsedQuery pq = parseQuery(originalQuery);
        if (pq.terms.size() < 3) return true;
        String artist = pq.hintedArtist != null ? pq.hintedArtist : pq.guessedArtist;
        if (artist == null || artist.isEmpty()) return true;
        return filename.toLowerCase(Locale.US).contains(artist.toLowerCase(Locale.US));
    }

    /** True when this fallback query is broader than the original (title-only from 3+ terms). */
    public static boolean isBroadFallbackQuery(String originalQuery, String fallbackQuery) {
        if (originalQuery == null || fallbackQuery == null) return false;
        ParsedQuery pq = parseQuery(originalQuery);
        if (pq.terms.size() < 3) return false;
        String title = pq.hintedTitle != null ? pq.hintedTitle : pq.guessedTitle;
        return title != null && fallbackQuery.trim().equalsIgnoreCase(title);
    }

    /** Up to two alternate queries when the primary AND search is sparse. */
    public static List<String> fallbackQueries(String query) {
        List<String> out = new ArrayList<String>();
        if (query == null) return out;
        String primary = query.trim();
        if (primary.isEmpty()) return out;

        ParsedQuery pq = parseQuery(primary);
        Set<String> seen = new HashSet<String>();
        seen.add(primary.toLowerCase(Locale.US));

        String artist = pq.hintedArtist != null ? pq.hintedArtist : pq.guessedArtist;
        String title = pq.hintedTitle != null ? pq.hintedTitle : pq.guessedTitle;
        if (artist != null && title != null) {
            addFallback(out, seen, title + " " + artist);
        } else if (pq.terms.size() >= 2) {
            StringBuilder reversed = new StringBuilder();
            for (int i = pq.terms.size() - 1; i >= 0; i--) {
                if (reversed.length() > 0) reversed.append(' ');
                reversed.append(pq.terms.get(i));
            }
            addFallback(out, seen, reversed.toString());
        }

        if (pq.terms.size() >= 3 && title != null) {
            addFallback(out, seen, title);
        }

        while (out.size() > MAX_FALLBACK_QUERIES) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static void addFallback(List<String> out, Set<String> seen, String q) {
        String trimmed = q.trim();
        if (trimmed.length() < 2) return;
        String key = trimmed.toLowerCase(Locale.US);
        if (!seen.add(key)) return;
        out.add(trimmed);
    }

    private static void addTerms(List<String> terms, String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        String[] parts = t.split("\\s+");
        Set<String> seen = new HashSet<String>();
        for (String part : parts) {
            String lower = part.toLowerCase(Locale.US);
            if (lower.length() < 1) continue;
            if (seen.add(lower)) terms.add(lower);
        }
    }

    private static List<String> pathFolders(String filename) {
        List<String> folders = new ArrayList<String>();
        if (filename == null) return folders;
        String path = filename.replace('\\', '/');
        int last = path.lastIndexOf('/');
        if (last <= 0) return folders;
        String dir = path.substring(0, last);
        String[] parts = dir.split("/");
        for (String part : parts) {
            if (part.isEmpty() || part.startsWith("@@")) continue;
            folders.add(part);
        }
        return folders;
    }

    private static String basenameNoExt(String filename) {
        String path = filename.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }
}
