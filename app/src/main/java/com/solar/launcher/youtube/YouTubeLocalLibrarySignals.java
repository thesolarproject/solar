package com.solar.launcher.youtube;

import android.content.Context;

import com.solar.launcher.MusicLibraryStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, bounded snapshot of local library tags used only by the on-device Discover ranker.
 *
 * <p>No filenames or paths are retained, and these values are never sent to YouTube.</p>
 */
public final class YouTubeLocalLibrarySignals {
    private static final int ARTIST_LIMIT = 16;
    private static final int GENRE_LIMIT = 8;

    public final List<String> artists;
    public final List<String> genres;
    public final boolean partial;

    public YouTubeLocalLibrarySignals(List<String> artists, List<String> genres,
            boolean partial) {
        this.artists = artists != null
                ? new ArrayList<String>(artists) : new ArrayList<String>();
        this.genres = genres != null
                ? new ArrayList<String>(genres) : new ArrayList<String>();
        this.partial = partial;
    }

    public static YouTubeLocalLibrarySignals empty() {
        return new YouTubeLocalLibrarySignals(null, null, false);
    }

    public static YouTubeLocalLibrarySignals load(Context context) {
        if (context == null) return new YouTubeLocalLibrarySignals(null, null, true);
        try {
            MusicLibraryStore store =
                    MusicLibraryStore.getInstance(context.getApplicationContext());
            return new YouTubeLocalLibrarySignals(
                    store.listTopArtistsForDiscover(ARTIST_LIMIT),
                    store.listTopGenresForDiscover(GENRE_LIMIT),
                    false);
        } catch (RuntimeException e) {
            return new YouTubeLocalLibrarySignals(null, null, true);
        }
    }
}
