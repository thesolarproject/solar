package com.solar.launcher.flow;

import com.solar.launcher.PlayQueue;

import java.io.File;

/**
 * NP→Flow 3D flyer morph is only valid when now-playing maps to a local library album rack slot.
 * Streaming / temp Soulseek·Deezer·podcast playback uses the standard Flow crossfade instead.
 */
public final class NpToFlowMorphPolicy {

    private NpToFlowMorphPolicy() {}

    /**
     * @param indexedInLibrary true when the active track path is in {@code customLibrary}
     * @param streamTemp       Reach/Soulseek temp or Deezer cache path not yet saved to library
     * @param podcastFile      under podcast library root
     * @param matchKey         Flow album carousel key for the active queue track
     */
    public static boolean isCarouselMorphEligible(boolean musicActive, boolean podcastActive,
            File track, PlayQueue.QueueItem queueItem, boolean indexedInLibrary,
            boolean streamTemp, boolean podcastFile, String matchKey) {
        if (!musicActive || podcastActive) return false;
        if (track == null || !track.isFile()) return false;
        if (podcastFile) return false;
        if (matchKey == null || matchKey.trim().isEmpty()) return false;
        if (queueItem != null) {
            if (queueItem.kind == PlayQueue.ItemKind.PODCAST_EPISODE) return false;
            if (queueItem.kind == PlayQueue.ItemKind.DEEZER_STREAM
                    || queueItem.kind == PlayQueue.ItemKind.REACH_STREAM) {
                // Saved downloads register in the library index — unsaved streams crossfade only.
                return indexedInLibrary;
            }
        }
        if (streamTemp && !indexedInLibrary) return false;
        return indexedInLibrary;
    }
}
