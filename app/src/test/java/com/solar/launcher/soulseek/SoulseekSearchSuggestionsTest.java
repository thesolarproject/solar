package com.solar.launcher.soulseek;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SoulseekSearchSuggestionsTest {
    @Test
    public void findOtherCopies_joinsBasenamePhrases() {
        String q = SoulseekSearchSuggestions.findOtherCopies(
                "@@user\\Music\\Michael Jackson\\01 - Michael Jackson - Thriller.mp3");
        if (!"Michael Jackson Thriller".equals(q)) {
            throw new AssertionError("expected phrase join, got: " + q);
        }
    }

    @Test
    public void findOtherCopies_ignoresPathFolders() {
        String q = SoulseekSearchSuggestions.findOtherCopies(
                "@@user\\Music\\Michael Jackson\\01 - Thriller.mp3");
        if (!"Thriller".equals(q)) {
            throw new AssertionError("basename only, got: " + q);
        }
    }

    @Test
    public void reSearchQueries_beatItExactOrder() {
        List<String> s = SoulseekSearchSuggestions.reSearchQueries("Beat It - Michael Jackson.mp3");
        if (s.size() != 4) throw new AssertionError("expected 4, got " + s);
        if (!"Beat it".equals(s.get(0))) throw new AssertionError("slot 0: " + s);
        if (!"Michael Jackson".equals(s.get(1))) throw new AssertionError("slot 1: " + s);
        if (!"Beat It Michael Jackson".equals(s.get(2))) throw new AssertionError("slot 2: " + s);
        if (!"Michael Jackson Beat It".equals(s.get(3))) throw new AssertionError("slot 3: " + s);
    }

    @Test
    public void reSearchQueries_pathExcluded() {
        List<String> s = SoulseekSearchSuggestions.reSearchQueries(
                "@@user\\Downloads\\Beat It - Michael Jackson.mp3");
        if (s.size() != 4) throw new AssertionError("size " + s);
        rejectContains(s, "user", "Downloads", "Beat", "Michael", "Jackson");
    }

    @Test
    public void sentenceCase() {
        if (!"Beat it".equals(SoulseekSearchSuggestions.sentenceCase("Beat It"))) {
            throw new AssertionError("sentenceCase Beat It");
        }
        if (!"Michael jackson".equals(SoulseekSearchSuggestions.sentenceCase("Michael Jackson"))) {
            throw new AssertionError("sentenceCase artist");
        }
    }

    @Test
    public void ampersandArtist_phraseSuggestions() {
        List<String> s = SoulseekSearchSuggestions.reSearchQueries("Earth Wind & Fire - September.mp3");
        if (s.size() != 4) throw new AssertionError("size " + s);
        if (!"Earth wind & fire".equals(s.get(0))) throw new AssertionError("slot 0: " + s);
        requireContains(s, "September", "Earth Wind & Fire September", "September Earth Wind & Fire");
    }

    @Test
    public void reSearchQueries_threePartFilename() {
        List<String> s = SoulseekSearchSuggestions.reSearchQueries(
                "@@u\\Music\\01 - Michael Jackson - Thriller.mp3");
        if (s.size() != 4) throw new AssertionError("empty " + s);
        requireContains(s, "Thriller", "Michael Jackson Thriller", "Thriller Michael Jackson");
    }

    @Test
    public void similarFromResults_dedupesAndExcludesQuery() {
        List<SoulseekClient.Result> results = new ArrayList<SoulseekClient.Result>();
        results.add(new SoulseekClient.Result("u", "Beat It - Michael Jackson.mp3", 0, 0, 0, true, true, 0));
        results.add(new SoulseekClient.Result("u", "Billie Jean - Michael Jackson.mp3", 0, 0, 0, true, true, 0));
        List<String> pool = SoulseekSearchSuggestions.similarFromResults(results, "Michael Jackson", 20);
        if (pool.isEmpty()) throw new AssertionError("expected suggestions");
        for (String q : pool) {
            if ("Michael Jackson".equalsIgnoreCase(q)) {
                throw new AssertionError("excluded query in pool: " + pool);
            }
        }
        List<String> slice = SoulseekSearchSuggestions.rotatedSlice(pool, 1, 2);
        if (slice.size() != 2) throw new AssertionError("slice size " + slice);
    }

    private static void requireContains(List<String> s, String... expected) {
        for (String want : expected) {
            boolean found = false;
            for (String q : s) {
                if (want.equals(q)) {
                    found = true;
                    break;
                }
            }
            if (!found) throw new AssertionError("missing " + want + " in " + s);
        }
    }

    private static void rejectContains(List<String> s, String... forbidden) {
        for (String q : s) {
            String lower = q.toLowerCase();
            for (String bad : forbidden) {
                if (lower.equals(bad.toLowerCase())) {
                    throw new AssertionError("forbidden " + bad + " in " + s);
                }
            }
        }
    }
}
