package com.solar.launcher.youtube;

/**
 * 2026-07-06 — One YouTube search/popular row from notPipe metadata.
 * Layman: title, channel, and id for a video the user can pick.
 * Technical: parsed from bridge JSON payload.
 * Reversal: delete; browse UI has no row model.
 */
public final class YouTubeVideo {
    private static final int MAX_DESCRIPTION_CHARS = 20_000;
    public final String id;
    public final String title;
    public final String author;
    public final String duration;
    /** Public description metadata from the official API; never used to resolve a stream. */
    public final String description;

    public YouTubeVideo(String id, String title, String author, String duration) {
        this(id, title, author, duration, "");
    }

    public YouTubeVideo(String id, String title, String author, String duration,
            String description) {
        this.id = id != null ? id : "";
        this.title = title != null ? title : "";
        this.author = author != null ? author : "";
        this.duration = duration != null ? duration : "";
        String safeDescription = description != null ? description : "";
        this.description = safeDescription.length() <= MAX_DESCRIPTION_CHARS
                ? safeDescription : safeDescription.substring(0, MAX_DESCRIPTION_CHARS);
    }

    /** 2026-07-06 — Subtitle for list row — channel plus optional duration. */
    public String subtitle() {
        if (duration != null && !duration.isEmpty()) {
            return author + " · " + duration;
        }
        return author;
    }
}
