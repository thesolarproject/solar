package com.solar.launcher;

import com.solar.launcher.contracts.QueueEpisode;
import com.solar.launcher.podcast.EpisodeBridge;
import com.solar.launcher.podcast.OpenRssClient;

import java.util.ArrayList;
import java.util.List;

/** App-layer bridge between RSS episodes and core playback queue types. */
final class PodcastPlaybackBridge {
    private PodcastPlaybackBridge() {}

    static List<QueueEpisode> toQueue(List<OpenRssClient.Episode> episodes) {
        List<QueueEpisode> out = new ArrayList<QueueEpisode>();
        if (episodes == null) return out;
        for (OpenRssClient.Episode ep : episodes) {
            out.add(EpisodeBridge.toQueue(ep));
        }
        return out;
    }

    static OpenRssClient.Episode fromQueue(QueueEpisode ep) {
        return EpisodeBridge.fromQueue(ep);
    }

    static List<OpenRssClient.Episode> fromCoordinator(PlaybackCoordinator coordinator) {
        List<OpenRssClient.Episode> out = new ArrayList<OpenRssClient.Episode>();
        if (coordinator == null) return out;
        for (QueueEpisode ep : coordinator.podcastQueue()) {
            out.add(EpisodeBridge.fromQueue(ep));
        }
        return out;
    }

    static OpenRssClient.Episode currentEpisode(PlaybackCoordinator coordinator) {
        if (coordinator == null) return null;
        int idx = coordinator.podcastIndex();
        List<QueueEpisode> q = coordinator.podcastQueue();
        if (idx < 0 || idx >= q.size()) return null;
        return EpisodeBridge.fromQueue(q.get(idx));
    }
}
