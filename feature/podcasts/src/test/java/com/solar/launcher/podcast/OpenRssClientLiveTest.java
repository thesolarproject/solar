package com.solar.launcher.podcast;

import org.junit.Test;

import java.util.List;

/** Live network smoke tests — run with: ./gradlew :app:testReleaseUnitTest --tests OpenRssClientLiveTest */
public class OpenRssClientLiveTest {
    @Test
    public void live_itunesSearch_returnsShowsWithFeedUrl() throws Exception {
        List<OpenRssClient.Podcast> shows = OpenRssClient.searchPodcasts("technology", 10);
        if (shows.isEmpty()) throw new AssertionError("iTunes returned 0 podcasts for technology");
        boolean hasFeed = false;
        for (OpenRssClient.Podcast p : shows) {
            if (p.feedUrl != null && !p.feedUrl.isEmpty()) hasFeed = true;
        }
        if (!hasFeed) throw new AssertionError("iTunes results missing feedUrl");
    }

    @Test
    public void live_featured99pi_returnsEpisodes() throws Exception {
        List<OpenRssClient.Episode> eps = OpenRssClient.fetchEpisodes(
                "https://feeds.99percentinvisible.org/99percentinvisible", 5);
        if (eps.isEmpty()) throw new AssertionError("99pi feed returned 0 episodes");
    }

    @Test
    public void live_featuredCatalogFeeds_returnEpisodes() throws Exception {
        for (OpenRssClient.Podcast p : PodcastCatalog.FEATURED) {
            List<OpenRssClient.Episode> eps = OpenRssClient.fetchEpisodes(p.feedUrl, 3);
            if (eps.isEmpty()) throw new AssertionError("Featured feed empty: " + p.title);
        }
    }

    @Test
    public void live_nprNewsArticleFeed_returnsZeroEpisodes() throws Exception {
        List<OpenRssClient.Episode> eps = OpenRssClient.fetchEpisodes(
                "https://feeds.npr.org/1001/rss.xml", 5);
        if (!eps.isEmpty()) throw new AssertionError("NPR news RSS should have no audio enclosures");
    }

    @Test
    public void live_nprPodcastFeed_returnsEpisodes() throws Exception {
        List<OpenRssClient.Episode> eps = OpenRssClient.fetchEpisodes(
                "https://feeds.npr.org/510289/podcast.xml", 5);
        if (eps.isEmpty()) throw new AssertionError("NPR Up First podcast feed returned 0 episodes");
    }
}
