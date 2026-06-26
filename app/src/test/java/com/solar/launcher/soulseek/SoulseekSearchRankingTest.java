package com.solar.launcher.soulseek;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SoulseekSearchRankingTest {

    private static SoulseekClient.Result r(String user, String file, boolean live, boolean slot, int speed) {
        return new SoulseekClient.Result(user, file, 3 * 1024 * 1024, 192, 180, live, slot, speed, 0);
    }

    @Test
    public void gorillazTrackRanksAboveUnrelatedClintEastwood() {
        String query = "Gorillaz Clint Eastwood";
        SoulseekClient.Result gorillaz = r("a", "Gorillaz - Clint Eastwood.mp3", true, true, 500_000);
        SoulseekClient.Result other = r("b", "Marcus Mumford - Clint Eastwood.mp3", true, true, 800_000);
        assertTrue(SoulseekSearchRanking.compareResults(gorillaz, other, query) < 0);
        assertTrue(SoulseekSearchRanking.relevanceScore(query, gorillaz.filename)
                > SoulseekSearchRanking.relevanceScore(query, other.filename));
    }

    @Test
    public void artistFolderScoresAboveTitleOnlyUnrelated() {
        String query = "Gorillaz Clint Eastwood";
        SoulseekClient.Result inFolder = r("a", "@@u\\Music\\Gorillaz\\01 Clint Eastwood.mp3", true, true, 200_000);
        SoulseekClient.Result unrelated = r("b", "Random - Clint Eastwood.mp3", true, true, 500_000);
        assertTrue(SoulseekSearchRanking.relevanceScore(query, inFolder.filename)
                > SoulseekSearchRanking.relevanceScore(query, unrelated.filename));
    }

    @Test
    public void fallbackQueries_includeReversedOrderForThreeTerms() {
        List<String> fb = SoulseekSearchRanking.fallbackQueries("Gorillaz Clint Eastwood");
        boolean hasReversed = false;
        for (String q : fb) {
            if ("clint eastwood gorillaz".equalsIgnoreCase(q)) hasReversed = true;
        }
        if (!hasReversed) {
            throw new AssertionError("expected reversed fallback in " + fb);
        }
    }

    @Test
    public void fallbackQueries_includeTitleOnlyForThreeTerms() {
        List<String> fb = SoulseekSearchRanking.fallbackQueries("Gorillaz Clint Eastwood");
        boolean hasTitle = false;
        for (String q : fb) {
            if ("clint eastwood".equalsIgnoreCase(q)) hasTitle = true;
        }
        if (!hasTitle) {
            throw new AssertionError("expected title fallback in " + fb);
        }
    }

    @Test
    public void broadFallbackGate_rejectsTitleWithoutArtist() {
        String query = "Gorillaz Clint Eastwood";
        assertFalse(SoulseekSearchRanking.passesBroadFallbackGate(query, "@@u\\Music\\Pop\\Clint Eastwood.mp3"));
        assertTrue(SoulseekSearchRanking.passesBroadFallbackGate(query,
                "@@u\\Music\\Gorillaz\\Clint Eastwood.mp3"));
    }

    @Test
    public void isBroadFallbackQuery_detectsTitleOnly() {
        assertTrue(SoulseekSearchRanking.isBroadFallbackQuery(
                "Gorillaz Clint Eastwood", "Clint Eastwood"));
        assertFalse(SoulseekSearchRanking.isBroadFallbackQuery(
                "Gorillaz Clint Eastwood", "Clint Eastwood Gorillaz"));
    }

    @Test
    public void tieOnRelevance_usesDownloadReliability() {
        String query = "Gorillaz Clint Eastwood";
        SoulseekClient.Result fast = r("a", "Gorillaz - Clint Eastwood.mp3", true, true, 600_000);
        SoulseekClient.Result slow = r("b", "Gorillaz - Clint Eastwood.flac", true, true, 80_000);
        assertTrue(SoulseekSearchRanking.compareResults(fast, slow, query) < 0);
    }

    @Test
    public void sortPutsBestMatchFirst() {
        String query = "Gorillaz Clint Eastwood";
        List<SoulseekClient.Result> list = new ArrayList<SoulseekClient.Result>();
        list.add(r("z", "Marcus Mumford - Clint Eastwood.mp3", true, true, 800_000));
        list.add(r("best", "Gorillaz - Clint Eastwood.mp3", true, true, 200_000));
        Collections.sort(list, new Comparator<SoulseekClient.Result>() {
            @Override
            public int compare(SoulseekClient.Result a, SoulseekClient.Result b) {
                return SoulseekSearchRanking.compareResults(a, b, query);
            }
        });
        if (!"best".equals(list.get(0).username)) {
            throw new AssertionError("expected Gorillaz first, got " + list.get(0).filename);
        }
    }
}
