package com.solar.launcher.flow;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** One cover in the Flow carousel. */
public final class FlowItem {

    public enum Kind {
        ALBUM, ARTIST, PLAYLIST, PODCAST
    }

    public final Kind kind;
    public final String id;
    public final String title;
    public final String subtitle;
    /** Stable key for scroll lookup (ArtistNames / AlbumNames / path / feed URL). */
    public final String matchKey;
    /** Cache key for cover bitmap. */
    public final String coverKey;
    public final List<File> tracks;
    /** Browsed artist when album is under artist-albums drill. */
    public final String browsedArtist;
    public final String podcastFeedUrl;
    public final String podcastArtUrl;

    public FlowItem(Kind kind, String id, String title, String subtitle, String matchKey,
            String coverKey, List<File> tracks, String browsedArtist,
            String podcastFeedUrl, String podcastArtUrl) {
        this.kind = kind;
        this.id = id != null ? id : "";
        this.title = title != null ? title : "";
        this.subtitle = subtitle != null ? subtitle : "";
        this.matchKey = matchKey != null ? matchKey : "";
        this.coverKey = coverKey != null ? coverKey : matchKey;
        this.tracks = tracks != null ? tracks : Collections.<File>emptyList();
        this.browsedArtist = browsedArtist != null ? browsedArtist : "";
        this.podcastFeedUrl = podcastFeedUrl != null ? podcastFeedUrl : "";
        this.podcastArtUrl = podcastArtUrl != null ? podcastArtUrl : "";
    }

    public static FlowItem album(String album, String subtitle, String matchKey,
            List<File> tracks, String browsedArtist) {
        return new FlowItem(Kind.ALBUM, matchKey, album, subtitle, matchKey, matchKey,
                tracks, browsedArtist, null, null);
    }

    public static FlowItem artist(String name, String subtitle, String matchKey) {
        return new FlowItem(Kind.ARTIST, matchKey, name, subtitle, matchKey, matchKey,
                Collections.<File>emptyList(), "", null, null);
    }

    public static FlowItem playlist(String id, String name, String matchKey, List<File> tracks) {
        return new FlowItem(Kind.PLAYLIST, id, name, "", matchKey, matchKey, tracks, "", null, null);
    }

    public static FlowItem podcast(String feedUrl, String title, String subtitle, String artUrl) {
        String key = FlowCatalog.podcastMatchKey(feedUrl);
        return new FlowItem(Kind.PODCAST, feedUrl, title, subtitle, key, key,
                Collections.<File>emptyList(), "", feedUrl, artUrl);
    }
}
