package com.solar.launcher.podcast;

import com.solar.launcher.contracts.QueueEpisode;

/** Convert between RSS episode types and queue-neutral episode data. */
public final class EpisodeBridge {
    private EpisodeBridge() {}

    public static QueueEpisode toQueue(OpenRssClient.Episode ep) {
        if (ep == null) return new QueueEpisode("", "", "", 0);
        return new QueueEpisode(ep.title, ep.audioUrl, ep.pubDate, ep.durationSec);
    }

    public static OpenRssClient.Episode fromQueue(QueueEpisode ep) {
        if (ep == null) return new OpenRssClient.Episode("", "", "");
        return new OpenRssClient.Episode(ep.title, ep.audioUrl, ep.pubDate, ep.durationSec);
    }
}
