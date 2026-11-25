package com.solar.launcher.youtube.api;

import com.solar.launcher.youtube.YouTubeComment;
import com.solar.launcher.youtube.YouTubeVideo;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 2026-11-25 — On-device YouTube extraction via NewPipeExtractor.
 * Layman: extracts stream URLs directly on the Y1/Y2 over Wi-Fi, no PC sidecar.
 * Technical: NewPipeExtractor + SolarHttp downloader; prefers progressive streams for old MediaPlayer.
 */
public final class NewPipeBackend implements YoutubeBackend {

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean inited;

    public NewPipeBackend() {
        ensureInit();
    }

    private static void ensureInit() {
        if (inited) return;
        synchronized (INIT_LOCK) {
            if (inited) return;
            try {
                NewPipe.init(SolarNewPipeDownloader.getInstance(),
                        new Localization("en", "US"));
            } catch (Throwable ignored) {
                // May already be initialized.
            }
            inited = true;
        }
    }

    @Override
    public String getName() {
        return "NewPipe";
    }

    @Override
    public String getHost() {
        return "youtube.com";
    }

    @Override
    public boolean supportsVideo360() {
        return true;
    }

    @Override
    public boolean supportsHqVideo() {
        return true;
    }

    @Override
    public List<YouTubeVideo> getPopularVideos() throws IOException {
        ensureInit();
        try {
            KioskExtractor<?> kiosk = ServiceList.YouTube
                    .getKioskList().getDefaultKioskExtractor();
            kiosk.fetchPage();
            return toVideos(kiosk.getInitialPage().getItems());
        } catch (Throwable e) {
            throw io(e, "popular");
        }
    }

    @Override
    public List<YouTubeVideo> search(String query) throws IOException {
        ensureInit();
        try {
            SearchExtractor se = ServiceList.YouTube.getSearchExtractor(query != null ? query : "");
            se.fetchPage();
            return toVideos(se.getInitialPage().getItems());
        } catch (Throwable e) {
            throw io(e, "search");
        }
    }

    @Override
    public List<YouTubeComment> getComments(String videoId) throws IOException {
        ensureInit();
        try {
            String url = "https://www.youtube.com/watch?v=" + enc(videoId);
            CommentsExtractor ce = ServiceList.YouTube.getCommentsExtractor(url);
            ce.fetchPage();
            List<CommentsInfoItem> items = ce.getInitialPage().getItems();
            List<YouTubeComment> out = new ArrayList<YouTubeComment>();
            if (items == null) return out;
            int max = Math.min(items.size(), 80);
            for (int i = 0; i < max; i++) {
                CommentsInfoItem c = items.get(i);
                if (c == null) continue;
                String author = c.getUploaderName() != null ? c.getUploaderName() : "";
                String text = c.getCommentText() != null ? c.getCommentText().getContent() : "";
                out.add(new YouTubeComment(author, text));
            }
            return out;
        } catch (Throwable e) {
            // Comments not critical; fail soft.
            throw io(e, "comments");
        }
    }

    @Override
    public String getVideoUrl(String videoId, String quality) throws IOException {
        ensureInit();
        try {
            org.schabi.newpipe.extractor.stream.StreamExtractor se =
                    ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=" + enc(videoId));
            se.fetchPage();
            int want = parseQuality(quality, 360);
            List<VideoStream> streams = se.getVideoStreams();
            VideoStream best = pickProgressive(streams, want);
            if (best != null && best.getContent() != null && best.getContent().length() > 0) {
                return best.getContent();
            }
            // Fallback: any progressive stream.
            if (streams != null) {
                for (int i = 0; i < streams.size(); i++) {
                    VideoStream vs = streams.get(i);
                    if (vs != null && vs.getContent() != null && vs.getContent().length() > 0) {
                        return vs.getContent();
                    }
                }
            }
            throw new IOException("NewPipe no progressive video stream");
        } catch (Throwable e) {
            throw io(e, "video");
        }
    }

    @Override
    public YoutubeBackend.AudioStream resolveAudio(String videoId) throws IOException {
        ensureInit();
        try {
            org.schabi.newpipe.extractor.stream.StreamExtractor se =
                    ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=" + enc(videoId));
            se.fetchPage();
            List<org.schabi.newpipe.extractor.stream.AudioStream> audios = se.getAudioStreams();
            if (audios == null || audios.isEmpty()) throw new IOException("NewPipe no audio");
            org.schabi.newpipe.extractor.stream.AudioStream best = audios.get(0);
            int bestRate = -1;
            for (int i = 0; i < audios.size(); i++) {
                org.schabi.newpipe.extractor.stream.AudioStream a = audios.get(i);
                if (a == null || a.getContent() == null || a.getContent().length() == 0) continue;
                int rate = a.getAverageBitrate();
                String fmt = a.getFormat() != null ? a.getFormat().getSuffix() : "";
                // Prefer m4a/aac for old Android.
                int score = rate;
                if ("m4a".equalsIgnoreCase(fmt) || "mp4".equalsIgnoreCase(fmt)) score += 100000;
                if (score > bestRate) { bestRate = score; best = a; }
            }
            String url = best.getContent();
            String ext = best.getFormat() != null ? best.getFormat().getSuffix() : "m4a";
            if (ext == null || ext.length() == 0) ext = "m4a";
            if (url == null || url.length() == 0) throw new IOException("NewPipe empty audio url");
            return new YoutubeBackend.AudioStream(url, ext);
        } catch (Throwable e) {
            throw io(e, "audio");
        }
    }

    private static VideoStream pickProgressive(List<VideoStream> streams, int wantHeight) {
        if (streams == null || streams.isEmpty()) return null;
        VideoStream best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < streams.size(); i++) {
            VideoStream v = streams.get(i);
            if (v == null || v.getContent() == null || v.getContent().length() == 0) continue;
            int h = v.getHeight();
            String fmt = v.getFormat() != null ? v.getFormat().getSuffix() : "";
            int score = 0;
            // Prefer mp4 for old Android/MediaPlayer.
            if ("mp4".equalsIgnoreCase(fmt)) score += 10000;
            // Prefer closest to wanted height, not above if possible.
            int diff = Math.abs(h - wantHeight);
            score -= diff;
            if (h <= wantHeight) score += 500;
            if (score > bestScore) { bestScore = score; best = v; }
        }
        return best;
    }

    private static List<YouTubeVideo> toVideos(List<?> items) {
        List<YouTubeVideo> out = new ArrayList<YouTubeVideo>();
        if (items == null) return out;
        for (int i = 0; i < items.size(); i++) {
            Object o = items.get(i);
            if (!(o instanceof StreamInfoItem)) continue;
            StreamInfoItem s = (StreamInfoItem) o;
            String id = idFromUrl(s.getUrl());
            if (id.length() == 0) continue;
            String title = s.getName() != null ? s.getName() : "";
            String author = s.getUploaderName() != null ? s.getUploaderName() : "";
            String dur = s.getDuration() > 0 ? String.valueOf(s.getDuration()) : "";
            out.add(new YouTubeVideo(id, title, author, dur));
        }
        return out;
    }

    private static String idFromUrl(String url) {
        if (url == null) return "";
        int idx = url.indexOf("v=");
        if (idx >= 0) {
            String s = url.substring(idx + 2);
            int amp = s.indexOf('&');
            if (amp >= 0) s = s.substring(0, amp);
            return s;
        }
        // /watch/ID or youtu.be/ID
        int slash = url.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < url.length()) return url.substring(slash + 1);
        return "";
    }

    private static int parseQuality(String q, int def) {
        if (q == null) return def;
        try {
            String n = q.replaceAll("[^0-9]", "");
            if (n.length() == 0) return def;
            return Integer.parseInt(n);
        } catch (Exception ignored) {
            return def;
        }
    }

    private static String enc(String s) {
        return YoutubeApiUtil.urlEncode(s != null ? s : "");
    }

    private static IOException io(Throwable e, String op) {
        if (e instanceof IOException) return (IOException) e;
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return new IOException("NewPipe " + op + ": " + msg, e);
    }
}
