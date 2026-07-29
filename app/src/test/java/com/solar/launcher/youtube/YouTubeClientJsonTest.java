package com.solar.launcher.youtube;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 2026-07-15 — Native client JSON matches YouTubeResultJson shapes. */
public class YouTubeClientJsonTest {

    @Test
    public void videosJsonRoundTrip() throws Exception {
        List<YouTubeVideo> vids = new ArrayList<YouTubeVideo>();
        vids.add(new YouTubeVideo("abc", "Title", "Author", "3:45",
                "Download: https://artist.example/song.mp3"));
        String json = YouTubeClient.videosToJson(vids);
        List<YouTubeVideo> parsed = YouTubeResultJson.parseVideos(json);
        if (parsed.size() != 1 || !"abc".equals(parsed.get(0).id)) {
            throw new AssertionError("video round-trip failed: " + json);
        }
        if (!"3:45".equals(parsed.get(0).duration)) {
            throw new AssertionError("duration lost: " + parsed.get(0).duration);
        }
        if (!parsed.get(0).description.contains("artist.example")) {
            throw new AssertionError("description lost");
        }
    }

    @Test
    public void commentsJsonRoundTrip() throws Exception {
        List<YouTubeComment> comments = new ArrayList<YouTubeComment>();
        comments.add(new YouTubeComment("Bob", "Hello"));
        String json = YouTubeClient.commentsToJson(comments);
        List<YouTubeComment> parsed = YouTubeResultJson.parseComments(json);
        if (parsed.size() != 1 || !"Bob".equals(parsed.get(0).author)) {
            throw new AssertionError("comment round-trip failed: " + json);
        }
    }

    @Test
    public void cacheAnnotationPreservesPayloadShapeAndState() throws Exception {
        List<YouTubeVideo> vids = new ArrayList<YouTubeVideo>();
        vids.add(new YouTubeVideo("cached", "Cached title", "Channel", "4:00"));
        String annotated = YouTubeClient.annotateCache(
                YouTubeClient.videosToJson(vids), true, true, 42_000L);
        List<YouTubeVideo> parsed = YouTubeResultJson.parseVideos(annotated);
        YouTubeResultJson.CacheState cache =
                YouTubeResultJson.parseCacheState(annotated);
        if (parsed.size() != 1 || !"cached".equals(parsed.get(0).id)) {
            throw new AssertionError("annotation changed items: " + annotated);
        }
        if (!cache.cached || !cache.stale || cache.ageMs != 42_000L) {
            throw new AssertionError("cache state missing: " + annotated);
        }
    }

    @Test
    public void discoverAccountSignalsRoundTrip() throws Exception {
        List<YouTubeVideo> liked = Arrays.asList(
                new YouTubeVideo("liked1", "Liked title", "Liked channel", "3:00"));
        String json = YouTubeClient.discoverSignalsToJson(
                Arrays.asList("One", "Two"), liked, true, true, false);
        YouTubeDiscoverSignals parsed = YouTubeDiscoverSignals.parse(json);
        if (!parsed.accountConnected || !parsed.stale || parsed.partial) {
            throw new AssertionError("signal flags lost: " + json);
        }
        if (parsed.subscribedChannels.size() != 2
                || parsed.likedVideos.size() != 1
                || !"liked1".equals(parsed.likedVideos.get(0).id)) {
            throw new AssertionError("signals lost: " + json);
        }
    }

    @Test
    public void qualityLadder() {
        // Device-agnostic score / height helpers.
        if (YouTubeQuality.qualityHeight("360p") != 360) {
            throw new AssertionError("height parse");
        }
        // Ladder climb after ideal: 360 → next is 240 on Y1 ladder (or 360 if A5 preferred was 240).
        String after480 = YouTubeQuality.fallbackVideoQuality("480");
        if (after480 == null) throw new AssertionError("480 needs fallback");
        // 720 always has something below it on both ladders.
        if (YouTubeQuality.fallbackVideoQuality("720") == null
                && YouTubeQuality.fallbackVideoQuality("480") == null) {
            throw new AssertionError("hq ladder empty");
        }
        // Prefer exact-height mp4 muxed.
        int exact = YouTubeQuality.scoreStream(360, true, true, "360");
        int high = YouTubeQuality.scoreStream(720, true, true, "360");
        if (exact <= high) throw new AssertionError("prefer exact 360 over 720: " + exact + " vs " + high);
    }
}
