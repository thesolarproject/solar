package com.solar.launcher.podcast;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OpenRssClientTest {
    @Test
    public void parseEpisodes_readsAudioItems() throws Exception {
        String xml = "<?xml version=\"1.0\"?><rss><channel>"
                + "<item><title>Ep1</title><enclosure url=\"https://x/1.mp3\" type=\"audio/mpeg\"/></item>"
                + "<item><title>Ep2</title><enclosure url=\"https://x/2.m4a\" type=\"audio/mp4\"/></item>"
                + "</channel></rss>";
        List<OpenRssClient.Episode> out = OpenRssClient.parseEpisodes(xml.getBytes("UTF-8"), 10);
        if (out.size() != 2) throw new AssertionError("episode count");
        if (!"Ep1".equals(out.get(0).title)) throw new AssertionError("episode title");
        if (!out.get(1).audioUrl.contains("2.m4a")) throw new AssertionError("episode url");
    }

    @Test
    public void earlyPlayBytesIsAboutSixtySecondsAt128k() {
        if (OpenRssClient.PODCAST_EARLY_PLAY_BYTES != OpenRssClient.bytesForSeconds(60, 128)) {
            throw new AssertionError("early play bytes");
        }
        if (OpenRssClient.msFromBytes(OpenRssClient.PODCAST_EARLY_PLAY_BYTES) < 59000) {
            throw new AssertionError("ms from early bytes");
        }
    }

    @Test
    public void filterPlayableEpisodes_keepsLocalFiles() {
        List<OpenRssClient.Episode> in = new java.util.ArrayList<OpenRssClient.Episode>();
        in.add(new OpenRssClient.Episode("local", "file:///storage/sdcard0/Podcasts/x.mp3", ""));
        List<OpenRssClient.Episode> out = OpenRssClient.filterPlayableEpisodes(in, "Show");
        if (out.size() != 1) throw new AssertionError("local file kept");
    }

    @Test
    public void parseEpisodes_readsItunesDuration() throws Exception {
        String xml = "<?xml version=\"1.0\"?><rss><channel>"
                + "<item><title>Sec</title><itunes:duration>183</itunes:duration>"
                + "<enclosure url=\"https://x/a.mp3\" type=\"audio/mpeg\"/></item>"
                + "<item><title>Hms</title><itunes:duration>01:02:03</itunes:duration>"
                + "<enclosure url=\"https://x/b.mp3\" type=\"audio/mpeg\"/></item>"
                + "</channel></rss>";
        List<OpenRssClient.Episode> out = OpenRssClient.parseEpisodes(xml.getBytes("UTF-8"), 10);
        if (out.size() != 2) throw new AssertionError("count");
        if (out.get(0).durationSec != 183) throw new AssertionError("seconds");
        if (out.get(1).durationSec != 3723) throw new AssertionError("hms");
    }

    @Test
    public void parseDurationSeconds_variants() {
        if (OpenRssClient.parseDurationSeconds("45") != 45) throw new AssertionError("int");
        if (OpenRssClient.parseDurationSeconds("3:05") != 185) throw new AssertionError("ms");
        if (OpenRssClient.parseDurationSeconds("1:02:03") != 3723) throw new AssertionError("hms");
    }

    @Test
    public void formatPodcastDate_andDuration() {
        String d = OpenRssClient.formatPodcastDate("Wed, 12 Jun 2024 08:00:00 GMT");
        if (d == null || d.isEmpty()) throw new AssertionError("date");
        if (!OpenRssClient.formatDuration(125).endsWith("05")) throw new AssertionError("duration");
        if (!OpenRssClient.formatDuration(3661).startsWith("1:")) throw new AssertionError("hour");
    }

    @Test
    public void buildSearchUrl_includesCountryAndGenre() throws Exception {
        String url = OpenRssClient.buildSearchUrl("news", "KR", 1312, 20);
        if (!url.contains("country=KR")) throw new AssertionError("country param");
        if (!url.contains("genreId=1312")) throw new AssertionError("genreId param");
        if (!url.contains("term=news")) throw new AssertionError("term param");
    }

    @Test
    public void buildSearchUrl_defaultsEmptyTermToPodcast() throws Exception {
        String url = OpenRssClient.buildSearchUrl("", "US", null, 10);
        if (!url.contains("term=podcast")) throw new AssertionError("default term");
        if (!url.contains("country=US")) throw new AssertionError("country");
    }

    @Test
    public void normalizeFeedUrl_treatsHttpAndHttpsSame() {
        String a = OpenRssClient.normalizeFeedUrl("https://feeds.example.com/podcast.xml/");
        String b = OpenRssClient.normalizeFeedUrl("http://feeds.example.com/podcast.xml");
        if (!a.equals(b)) throw new AssertionError("scheme + trailing slash");
    }

    @Test
    public void mergePodcasts_dedupesByFeedUrl() {
        List<OpenRssClient.Podcast> out = new ArrayList<OpenRssClient.Podcast>();
        Set<String> seen = new HashSet<String>();
        List<OpenRssClient.Podcast> first = new ArrayList<OpenRssClient.Podcast>();
        first.add(new OpenRssClient.Podcast("A", "Pub", "https://x.test/feed.xml", null));
        List<OpenRssClient.Podcast> second = new ArrayList<OpenRssClient.Podcast>();
        second.add(new OpenRssClient.Podcast("B", "Pub", "http://x.test/feed.xml", null));
        second.add(new OpenRssClient.Podcast("C", "Pub", "https://y.test/other.rss", null));
        OpenRssClient.mergePodcasts(out, seen, first);
        OpenRssClient.mergePodcasts(out, seen, second);
        if (out.size() != 2) throw new AssertionError("deduped count");
        if (!"A".equals(out.get(0).title)) throw new AssertionError("itunes order kept");
    }

    @Test
    public void httpsThenHttpVariants_orderMatchesScheme() {
        String[] httpsFirst = PodcastLibrary.httpsThenHttpVariants("https://host/ep.mp3");
        if (httpsFirst.length != 2 || !httpsFirst[0].startsWith("https://")) {
            throw new AssertionError("https first");
        }
        String[] httpFirst = PodcastLibrary.httpsThenHttpVariants("http://host/ep.mp3");
        if (httpFirst.length != 2 || !httpFirst[0].startsWith("https://")) {
            throw new AssertionError("upgrade http to https first");
        }
    }
}
