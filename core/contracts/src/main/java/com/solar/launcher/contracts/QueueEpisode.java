package com.solar.launcher.contracts;

/** Minimal episode data for unified playback queue (decoupled from RSS client). */
public final class QueueEpisode {
    public final String title;
    public final String audioUrl;
    public final String pubDate;
    public final int durationSec;

    public QueueEpisode(String title, String audioUrl, String pubDate, int durationSec) {
        this.title = title != null ? title : "";
        this.audioUrl = audioUrl != null ? audioUrl : "";
        this.pubDate = pubDate != null ? pubDate : "";
        this.durationSec = durationSec > 0 ? durationSec : 0;
    }
}
