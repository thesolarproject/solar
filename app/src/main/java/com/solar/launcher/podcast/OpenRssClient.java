package com.solar.launcher.podcast;

import com.solar.launcher.net.SolarHttp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class OpenRssClient {
    public static final String UA = "SolarLauncher/1.0 (+https://openrss.org)";

    /** ponytail: ~128 kbps CBR guess — start play after ~60s buffered */
    public static final long PODCAST_EARLY_PLAY_BYTES = 960L * 1024L;
    public static final int PODCAST_ESTIMATE_KBPS = 128;
    /** ponytail: max audio probes when deciding if a show is worth listing */
    private static final int PODCAST_SHOW_PROBE_MAX = 3;
    private static final int PODCAST_PROBE_THREADS = 3;

    public static class Podcast {
        public final String title;
        public final String publisher;
        public final String feedUrl;
        public final String artworkUrl;

        public Podcast(String title, String publisher, String feedUrl, String artworkUrl) {
            this.title = title;
            this.publisher = publisher;
            this.feedUrl = feedUrl;
            this.artworkUrl = artworkUrl;
        }
    }

    public static class Episode {
        public final String title;
        public final String audioUrl;
        public final String pubDate;
        public final int durationSec;

        public Episode(String title, String audioUrl, String pubDate) {
            this(title, audioUrl, pubDate, 0);
        }

        public Episode(String title, String audioUrl, String pubDate, int durationSec) {
            this.title = title;
            this.audioUrl = preferHttps(audioUrl);
            this.pubDate = pubDate;
            this.durationSec = durationSec > 0 ? durationSec : 0;
        }
    }

    public static List<Podcast> searchPodcasts(String query, int limit) throws Exception {
        return searchPodcasts(query, null, null, limit);
    }

    public static List<Podcast> searchPodcasts(String term, String country, Integer genreId, int limit)
            throws Exception {
        String q = term == null ? "" : term.trim();
        if (q.isEmpty()) q = "podcast";
        if (limit < 1) limit = 10;
        if (limit > 50) limit = 50;
        String url = buildSearchUrl(q, country, genreId, limit);
        byte[] raw = httpGet(url, "application/json");
        JSONObject root = new JSONObject(new String(raw, "UTF-8"));
        JSONArray arr = root.optJSONArray("results");
        List<Podcast> out = new ArrayList<Podcast>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String title = o.optString("collectionName", "").trim();
            String publisher = o.optString("artistName", "").trim();
            String feed = o.optString("feedUrl", "").trim();
            String art = o.optString("artworkUrl100", "").trim();
            if (title.isEmpty() || feed.isEmpty()) continue;
            out.add(new Podcast(title, publisher, feed, art));
        }
        return out;
    }

    /** ponytail: package-visible for unit tests — iTunes Search URL shape */
    static String buildSearchUrl(String term, String country, Integer genreId, int limit) throws Exception {
        String q = term == null || term.trim().isEmpty() ? "podcast" : term.trim();
        StringBuilder url = new StringBuilder(
                "https://itunes.apple.com/search?media=podcast&entity=podcast&limit="
                        + limit + "&term=" + URLEncoder.encode(q, "UTF-8"));
        if (country != null && !country.trim().isEmpty()) {
            url.append("&country=").append(URLEncoder.encode(country.trim().toUpperCase(), "UTF-8"));
        }
        if (genreId != null && genreId > 0) {
            url.append("&genreId=").append(genreId);
        }
        return url.toString();
    }

    /** Drop shows whose feed or recent episode audio cannot be reached on this device. */
    public static List<Podcast> filterPlayablePodcasts(List<Podcast> shows) {
        List<Podcast> out = new ArrayList<Podcast>();
        if (shows == null) return out;
        for (Podcast p : shows) {
            if (p != null && isPodcastPlayable(p.feedUrl)) out.add(p);
        }
        return out;
    }

    public interface PodcastProbeCallback {
        /** Worker thread — one callback per show. */
        void onProbed(Podcast podcast, boolean playable);

        /** Worker thread — after the last show is probed. */
        void onComplete(int playableCount, int totalCount);
    }

    public interface EpisodeProbeCallback {
        /** Worker thread — one callback per episode index. */
        void onProbed(int index, Episode episode, boolean playable);

        /** Worker thread — after the last episode is probed. */
        void onComplete(int playableCount, int totalCount);
    }

    /** Probe shows in parallel; callbacks fire as each finishes (order not guaranteed). */
    public static void probePodcastsPlayable(final List<Podcast> shows, final PodcastProbeCallback cb) {
        if (cb == null) return;
        if (shows == null || shows.isEmpty()) {
            cb.onComplete(0, 0);
            return;
        }
        final int n = shows.size();
        final java.util.concurrent.atomic.AtomicInteger playable = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger(0);
        int workers = Math.min(PODCAST_PROBE_THREADS, n);
        for (int w = 0; w < workers; w++) {
            final int worker = w;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = worker; i < n; i += workers) {
                        Podcast p = shows.get(i);
                        boolean ok = p != null && isPodcastPlayable(p.feedUrl);
                        if (ok) playable.incrementAndGet();
                        cb.onProbed(p, ok);
                        if (finished.incrementAndGet() == n) {
                            cb.onComplete(playable.get(), n);
                        }
                    }
                }
            }, "PodShowProbe-" + w).start();
        }
    }

    /** Probe episodes in parallel; callbacks fire as each finishes (index stable for ordering). */
    public static void probeEpisodesPlayable(final List<Episode> episodes, final String showTitle,
            final EpisodeProbeCallback cb) {
        if (cb == null) return;
        if (episodes == null || episodes.isEmpty()) {
            cb.onComplete(0, 0);
            return;
        }
        final int n = episodes.size();
        final java.util.concurrent.atomic.AtomicInteger playable = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger(0);
        int workers = Math.min(PODCAST_PROBE_THREADS, n);
        for (int w = 0; w < workers; w++) {
            final int worker = w;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = worker; i < n; i += workers) {
                        Episode ep = episodes.get(i);
                        boolean ok = isEpisodePlayable(ep, showTitle);
                        if (ok) playable.incrementAndGet();
                        cb.onProbed(i, ep, ok);
                        if (finished.incrementAndGet() == n) {
                            cb.onComplete(playable.get(), n);
                        }
                    }
                }
            }, "PodEpProbe-" + w).start();
        }
    }

    /** Drop episodes whose audio URL fails TLS/HTTP reachability (saved/local files kept). */
    public static List<Episode> filterPlayableEpisodes(List<Episode> episodes, String showTitle) {
        if (episodes == null || episodes.isEmpty()) return new ArrayList<Episode>();
        final int n = episodes.size();
        final boolean[] ok = new boolean[n];
        int workers = Math.min(PODCAST_PROBE_THREADS, n);
        final CountDownLatch done = new CountDownLatch(n);
        for (int w = 0; w < workers; w++) {
            final int worker = w;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = worker; i < n; i += workers) {
                        ok[i] = isEpisodePlayable(episodes.get(i), showTitle);
                        done.countDown();
                    }
                }
            }, "PodcastProbe-" + w).start();
        }
        try {
            done.await(90, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Episode> out = new ArrayList<Episode>();
        for (int i = 0; i < n; i++) {
            if (ok[i]) out.add(episodes.get(i));
        }
        return out;
    }

    static boolean isPodcastPlayable(String feedUrl) {
        if (feedUrl == null || feedUrl.trim().isEmpty()) return false;
        try {
            List<Episode> eps = fetchEpisodes(feedUrl, 8);
            int probed = 0;
            for (Episode ep : eps) {
                if (isAudioUrlReachable(ep.audioUrl)) return true;
                if (++probed >= PODCAST_SHOW_PROBE_MAX) break;
            }
        } catch (Exception ignored) {}
        return false;
    }

    static boolean isEpisodePlayable(Episode ep, String showTitle) {
        if (ep == null || ep.audioUrl == null || ep.audioUrl.isEmpty()) return false;
        if (ep.audioUrl.startsWith("file://")) return true;
        if (showTitle != null && PodcastLibrary.findSaved(showTitle, ep.title, ep.audioUrl) != null) {
            return true;
        }
        return isAudioUrlReachable(ep.audioUrl);
    }

    static boolean isAudioUrlReachable(String audioUrl) {
        if (audioUrl == null || audioUrl.trim().isEmpty()) return false;
        return SolarHttp.probeAnyReachable(PodcastLibrary.httpsThenHttpVariants(audioUrl));
    }

    public static List<Episode> fetchEpisodes(String sourceFeedUrl, int limit) throws Exception {
        Exception direct = null;
        try {
            return parseEpisodes(httpGet(sourceFeedUrl, "application/rss+xml,application/xml,text/xml,*/*"), limit);
        } catch (Exception e) {
            direct = e;
        }
        String proxy = "https://openrss.org/feed?url=" + URLEncoder.encode(sourceFeedUrl, "UTF-8");
        try {
            return parseEpisodes(
                    httpGet(proxy, "application/rss+xml,application/xml,text/xml,*/*"), limit);
        } catch (Exception proxyErr) {
            if (direct != null) throw direct;
            throw proxyErr;
        }
    }

    static List<Episode> parseEpisodes(byte[] xml, int limit) throws Exception {
        List<Episode> out = new ArrayList<Episode>();
        if (xml == null || xml.length == 0) return out;
        if (limit < 1) limit = 20;
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser p = factory.newPullParser();
        p.setInput(new ByteArrayInputStream(xml), "UTF-8");
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT && out.size() < limit) {
            if (event == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if ("item".equals(tag) || "entry".equals(tag)) {
                    Episode ep = readEpisodeBlock(p, tag);
                    if (ep != null) out.add(ep);
                }
            }
            event = p.next();
        }
        return out;
    }

    private static Episode readEpisodeBlock(XmlPullParser p, String blockTag) throws Exception {
        String title = "";
        String pubDate = "";
        String audio = "";
        int durationSec = 0;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && blockTag.equals(p.getName())) break;
            if (event != XmlPullParser.START_TAG) continue;
            String n = p.getName();
            if ("title".equals(n)) {
                title = p.nextText();
            } else if ("pubDate".equals(n) || "updated".equals(n)) {
                pubDate = p.nextText();
            } else if ("duration".equals(n) || (n != null && n.endsWith(":duration"))) {
                durationSec = parseDurationSeconds(p.nextText());
            } else if ("enclosure".equals(n)) {
                String url = p.getAttributeValue(null, "url");
                String type = p.getAttributeValue(null, "type");
                if (url != null && !url.trim().isEmpty()
                        && (type == null || type.isEmpty() || type.startsWith("audio/"))) {
                    audio = url.trim();
                }
            } else if ("link".equals(n)) {
                String href = p.getAttributeValue(null, "href");
                String rel = p.getAttributeValue(null, "rel");
                String type = p.getAttributeValue(null, "type");
                if (href != null && !href.trim().isEmpty() && audio.isEmpty()) {
                    boolean audioType = type != null && type.startsWith("audio/");
                    boolean enclosure = "enclosure".equalsIgnoreCase(rel);
                    if (audioType || enclosure) audio = href.trim();
                }
            }
        }
        if (audio.isEmpty()) return null;
        String t = title == null || title.trim().isEmpty() ? "Untitled Episode" : title.trim();
        return new Episode(t, preferHttps(audio), pubDate == null ? "" : pubDate.trim(), durationSec);
    }

    /** ponytail: seconds int or HH:MM:SS / MM:SS from itunes:duration */
    static int parseDurationSeconds(String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        if (s.isEmpty()) return 0;
        if (s.indexOf(':') >= 0) {
            String[] parts = s.split(":");
            try {
                if (parts.length == 3) {
                    return Integer.parseInt(parts[0].trim()) * 3600
                            + Integer.parseInt(parts[1].trim()) * 60
                            + Integer.parseInt(parts[2].trim());
                }
                if (parts.length == 2) {
                    return Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
                }
            } catch (NumberFormatException ignored) {
                return 0;
            }
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String formatPodcastDate(String pubDate) {
        if (pubDate == null || pubDate.trim().isEmpty()) return "";
        String s = pubDate.trim();
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "EEE, dd MMM yyyy HH:mm:ss z",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd"
        };
        for (String pat : patterns) {
            try {
                SimpleDateFormat in = new SimpleDateFormat(pat, Locale.US);
                Date d = in.parse(s);
                if (d != null) {
                    return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(d);
                }
            } catch (ParseException ignored) {}
        }
        return s.length() > 28 ? s.substring(0, 28) : s;
    }

    public static String formatDuration(int durationSec) {
        if (durationSec <= 0) return "";
        int h = durationSec / 3600;
        int m = (durationSec % 3600) / 60;
        int sec = durationSec % 60;
        if (h > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, sec);
        }
        return String.format(Locale.getDefault(), "%d:%02d", m, sec);
    }

    private static String preferHttps(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.startsWith("http://")) return "https://" + u.substring(7);
        return u;
    }

    public interface AudioDownloadListener {
        void onProgress(long bytesRead, long totalBytes);

        /** @return true if playback started — cache stays at {@code .part} until renamed later */
        boolean onPartialReady(File partialFile, long bytesRead);
    }

    public static File downloadAudio(File cacheDir, String urlStr) throws Exception {
        return downloadAudio(cacheDir, urlStr, null, PODCAST_EARLY_PLAY_BYTES, null);
    }

    public static File downloadAudio(File cacheDir, String urlStr, AudioDownloadListener listener)
            throws Exception {
        return downloadAudio(cacheDir, urlStr, listener, PODCAST_EARLY_PLAY_BYTES, null);
    }

    public static File downloadAudio(File cacheDir, String urlStr, AudioDownloadListener listener,
            long startPlayBytes, java.util.concurrent.atomic.AtomicBoolean cancel) throws Exception {
        if (cacheDir == null) throw new IllegalArgumentException("cacheDir");
        if (urlStr == null || urlStr.trim().isEmpty()) throw new IllegalArgumentException("url");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) throw new Exception("Cannot create cache dir");
        String key = Integer.toHexString(urlStr.hashCode());
        File out = new File(cacheDir, "pod_" + key + ".audio");
        if (out.isFile() && out.length() > 0) return out;
        File tmp = new File(cacheDir, "pod_" + key + ".part");
        if (tmp.isFile() && tmp.length() > 0 && !out.isFile()) {
            if (tmp.renameTo(out)) return out;
        }
        final boolean[] playbackFromPartial = {false};
        final SolarHttp.PartialReadyListener partialReady = listener == null ? null
                : new SolarHttp.PartialReadyListener() {
                    @Override
                    public void onPartialReady(File dest, long bytesRead) {
                        if (listener.onPartialReady(dest, bytesRead)) playbackFromPartial[0] = true;
                    }
                };
        SolarHttp.DownloadProgress progress = listener == null ? null
                : new SolarHttp.DownloadProgress() {
                    @Override
                    public void onProgress(long bytesRead, long totalBytes) {
                        listener.onProgress(bytesRead, totalBytes);
                    }
                };
        Exception last = null;
        long resumeFrom = tmp.isFile() ? tmp.length() : 0L;
        for (String tryUrl : PodcastLibrary.httpsThenHttpVariants(urlStr)) {
            try {
                SolarHttp.downloadToFile(tryUrl, tmp, progress, startPlayBytes, partialReady, cancel,
                        resumeFrom);
                if (playbackFromPartial[0]) return tmp;
                if (out.isFile()) out.delete();
                if (!tmp.renameTo(out)) {
                    copyFile(tmp, out);
                    tmp.delete();
                }
                return out;
            } catch (Exception e) {
                last = e;
                resumeFrom = tmp.isFile() ? tmp.length() : 0L;
                if (resumeFrom <= 0 && tmp.isFile()) tmp.delete();
                if (cancel != null && cancel.get()) throw e;
            }
        }
        throw last != null ? last : new Exception("Download failed");
    }

    static long bytesForSeconds(int seconds, int kbps) {
        return (long) seconds * kbps * 1024L / 8L;
    }

    /** Inverse of bytesForSeconds at {@link #PODCAST_ESTIMATE_KBPS}. */
    public static long msFromBytes(long bytes) {
        if (bytes <= 0) return 0;
        return bytes * 8L * 1000L / ((long) PODCAST_ESTIMATE_KBPS * 1024L);
    }

    private static void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private static byte[] httpGet(String urlStr, String accept) throws Exception {
        try {
            if (urlStr != null && urlStr.startsWith("https://")) {
                String http = "http://" + urlStr.substring(8);
                return SolarHttp.getBytesFirstOk(new String[] {urlStr, http}, accept, UA);
            }
            return SolarHttp.getBytes(urlStr, accept, UA);
        } catch (java.io.IOException e) {
            throw new Exception(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
        }
    }
}
