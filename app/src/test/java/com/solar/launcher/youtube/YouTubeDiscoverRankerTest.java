package com.solar.launcher.youtube;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YouTubeDiscoverRankerTest {

    @Test
    public void accountAndLocalSignalsProduceExplainableDeterministicOrder() {
        YouTubeVideo synth = video("synth01", "Analog synth performance",
                "Signal Channel", "5:00");
        YouTubeVideo jazz = video("jazz001", "Modern jazz session",
                "Other Channel", "8:10");
        YouTubeVideo liked = video("liked01", "Ambient modular set",
                "Liked Channel", "20:00");
        YouTubeDiscoverRanker.Signals signals = new YouTubeDiscoverRanker.Signals(
                Arrays.asList(video("saved01", "Favorite synth albums",
                        "Signal Channel", "4:00")),
                Arrays.asList(liked),
                Arrays.asList("Signal Channel"),
                Arrays.asList("analog synth"),
                YouTubeDiscoverRanker.Feedback.empty(), 0, 0);

        List<YouTubeDiscoverRanker.Recommendation> first =
                YouTubeDiscoverRanker.rank(Arrays.asList(jazz, synth), signals, 10);
        List<YouTubeDiscoverRanker.Recommendation> second =
                YouTubeDiscoverRanker.rank(Arrays.asList(jazz, synth), signals, 10);

        assertEquals(3, first.size());
        assertEquals(synth.id, first.get(0).video.id);
        assertEquals(YouTubeDiscoverRanker.Reason.SUBSCRIBED_CHANNEL,
                first.get(0).reason);
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).video.id, second.get(i).video.id);
            assertEquals(first.get(i).score, second.get(i).score);
        }
    }

    @Test
    public void feedbackBlocksItemsBoostsSimilarAndReducesChannel() {
        YouTubeVideo blocked = video("blocked", "Guitar lesson",
                "Channel A", "5:00");
        YouTubeVideo boosted = video("boosted", "Guitar practice",
                "Channel B", "6:00");
        YouTubeVideo reduced = video("reduced", "News report",
                "Channel C", "7:00");
        Set<String> blockedIds = new HashSet<String>();
        blockedIds.add(blocked.id);
        Map<String, Integer> channels = new HashMap<String, Integer>();
        channels.put("channel b", 2);
        Map<String, Integer> reductions = new HashMap<String, Integer>();
        reductions.put("channel c", 3);
        Map<String, Integer> terms = new HashMap<String, Integer>();
        terms.put("guitar", 2);

        YouTubeDiscoverRanker.Feedback feedback =
                new YouTubeDiscoverRanker.Feedback(
                        blockedIds, channels, reductions, terms);
        YouTubeDiscoverRanker.Signals signals =
                new YouTubeDiscoverRanker.Signals(null, null, null, null,
                        feedback, 0, 0);
        List<YouTubeDiscoverRanker.Recommendation> ranked =
                YouTubeDiscoverRanker.rank(
                        Arrays.asList(reduced, blocked, boosted), signals, 10);

        assertEquals(2, ranked.size());
        assertEquals(boosted.id, ranked.get(0).video.id);
        assertEquals(YouTubeDiscoverRanker.Reason.MORE_LIKE,
                ranked.get(0).reason);
        for (YouTubeDiscoverRanker.Recommendation item : ranked) {
            assertFalse(blocked.id.equals(item.video.id));
        }
    }

    @Test
    public void localLibraryArtistsAndGenresRankWithAnExplainableReason() {
        YouTubeVideo matchingArtist = video("local01",
                "Boards of Canada studio session", "Archive Channel", "12:00");
        YouTubeVideo matchingGenre = video("local02",
                "Ambient essentials", "Compilation Channel", "9:00");
        YouTubeVideo unrelated = video("other01",
                "Daily news briefing", "News Channel", "6:00");
        YouTubeDiscoverRanker.Signals signals =
                new YouTubeDiscoverRanker.Signals(
                        null, null, null, null,
                        Arrays.asList("Boards of Canada"),
                        Arrays.asList("Ambient"),
                        YouTubeDiscoverRanker.Feedback.empty(), 0, 0);

        List<YouTubeDiscoverRanker.Recommendation> ranked =
                YouTubeDiscoverRanker.rank(
                        Arrays.asList(unrelated, matchingGenre, matchingArtist),
                        signals, 10);

        assertEquals(matchingArtist.id, ranked.get(0).video.id);
        assertEquals(YouTubeDiscoverRanker.Reason.LOCAL_LIBRARY_ARTIST,
                ranked.get(0).reason);
        assertEquals("Boards of Canada", ranked.get(0).detail);
        boolean foundGenreReason = false;
        for (YouTubeDiscoverRanker.Recommendation item : ranked) {
            if (matchingGenre.id.equals(item.video.id)) {
                foundGenreReason =
                        item.reason == YouTubeDiscoverRanker.Reason.LOCAL_LIBRARY_GENRE;
                assertEquals("Ambient", item.detail);
            }
        }
        assertTrue(foundGenreReason);
    }

    @Test
    public void appliesDurationFilterAndChannelDiversity() {
        List<YouTubeVideo> popular = new ArrayList<YouTubeVideo>();
        popular.add(video("a00001", "A one", "Same", "0:30"));
        popular.add(video("a00002", "A two", "Same", "3:00"));
        popular.add(video("a00003", "A three", "Same", "4:00"));
        popular.add(video("b00001", "B one", "Different", "5:00"));
        YouTubeDiscoverRanker.Signals signals =
                new YouTubeDiscoverRanker.Signals(null, null, null, null,
                        YouTubeDiscoverRanker.Feedback.empty(), 60, 600);
        List<YouTubeDiscoverRanker.Recommendation> ranked =
                YouTubeDiscoverRanker.rank(popular, signals, 3);

        assertEquals(3, ranked.size());
        for (YouTubeDiscoverRanker.Recommendation item : ranked) {
            assertFalse("a00001".equals(item.video.id));
        }
        assertTrue(ranked.get(0).video.author.equals("Same"));
        assertTrue(ranked.get(1).video.author.equals("Same")
                || ranked.get(1).video.author.equals("Different"));
        assertTrue(ranked.get(2).video.author.equals("Different")
                || ranked.get(1).video.author.equals("Different"));
    }

    @Test
    public void parsesOfficialMinuteAndHourStyleDurations() {
        assertEquals(59, YouTubeDiscoverRanker.parseDurationSeconds("0:59"));
        assertEquals(3723, YouTubeDiscoverRanker.parseDurationSeconds("62:03"));
        assertEquals(3723, YouTubeDiscoverRanker.parseDurationSeconds("1:02:03"));
        assertEquals(0, YouTubeDiscoverRanker.parseDurationSeconds("unknown"));
    }

    private static YouTubeVideo video(String id, String title,
            String channel, String duration) {
        return new YouTubeVideo(id, title, channel, duration);
    }
}
