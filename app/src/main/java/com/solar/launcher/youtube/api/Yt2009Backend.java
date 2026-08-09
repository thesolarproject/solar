package com.solar.launcher.youtube.api;

import com.solar.launcher.youtube.YouTubeComment;
import com.solar.launcher.youtube.YouTubeVideo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2026-08-01 — yt2009 video-stream backend, aligned with notPipe Yt2009.java.
 * Layman: old-style YouTube frontend that serves progressive video files at 360-1080p.
 * Technical: redirect-based URLs; no search/popular/comments (video-only backend).
 * Reversal: delete; InstancePool drops yt2009 from stream order.
 */
public final class Yt2009Backend implements YoutubeBackend {

    private final String baseUrl;

    public Yt2009Backend(String baseUrl) {
        this.baseUrl = YoutubeApiUtil.trimSlash(baseUrl);
    }

    @Override
    public String getName() {
        return "yt2009";
    }

    @Override
    public String getHost() {
        return baseUrl.replace("https://", "").replace("http://", "");
    }

    /** yt2009 serves muxed 360p via /channel_fh264_getvideo. */
    @Override
    public boolean supportsVideo360() {
        return true;
    }

    /** yt2009 serves 480p (/get_480) and 720/1080p (/exp_hd). */
    @Override
    public boolean supportsHqVideo() {
        return true;
    }

    // ---- Video-only backend: search/popular/comments return empty / null ----

    @Override
    public List<YouTubeVideo> getPopularVideos() throws IOException {
        // ponytail: yt2009 has no metadata API; return empty so failover moves on.
        return Collections.emptyList();
    }

    @Override
    public List<YouTubeVideo> search(String query) throws IOException {
        return Collections.emptyList();
    }

    @Override
    public List<YouTubeComment> getComments(String videoId) throws IOException {
        return Collections.emptyList();
    }

    /**
     * yt2009 video URL — redirect-based endpoint.
     * ponytail: return the URL directly; IJK/MediaPlayer/OkHttp all follow 302 natively.
     * NotPipe resolves the redirect manually to control User-Agent, but Solar targets
     * Android 4.2+ where Main profile H.264 is fine — no transcoding risk.
     * Upgrade path: add SolarHttp.resolveRedirectUrl() if yt2009 UA-based transcoding
     * causes problems on Y1/Y2.
     */
    @Override
    public String getVideoUrl(String videoId, String quality) throws IOException {
        String path;
        if ("480".equals(quality)) {
            path = "/get_480?video_id=" + videoId;
        } else if ("720".equals(quality)) {
            path = "/exp_hd?video_id=" + videoId;
        } else if ("1080".equals(quality)) {
            path = "/exp_hd?video_id=" + videoId + "&fhd=1";
        } else {
            // 360 or default
            path = "/channel_fh264_getvideo?v=" + videoId;
        }
        return baseUrl + path;
    }

    @Override
    public AudioStream resolveAudio(String videoId) {
        // yt2009 does not provide audio-only streams.
        return null;
    }
}
