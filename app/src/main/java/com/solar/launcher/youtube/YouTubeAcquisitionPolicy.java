package com.solar.launcher.youtube;

/**
 * Single policy boundary shared by UI, downloader compatibility calls, and tests.
 */
public final class YouTubeAcquisitionPolicy {

    private YouTubeAcquisitionPolicy() {}

    public static boolean remoteStreamsAllowed() {
        return false;
    }

    public static String canonicalUrl(YouTubeVideo video) {
        String id = video != null ? safeVideoId(video.id) : "";
        return id.length() > 0 ? "https://www.youtube.com/watch?v=" + id : "";
    }

    public static String soulseekQuery(YouTubeVideo video) {
        if (video == null) return "";
        String title = clean(video.title);
        String author = clean(video.author);
        if (author.length() == 0) return title;
        if (title.length() == 0) return author;
        return title + " " + author;
    }

    private static String safeVideoId(String value) {
        String clean = value != null ? value.trim() : "";
        return clean.matches("[A-Za-z0-9_-]{6,64}") ? clean : "";
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
