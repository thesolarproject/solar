package com.solar.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Artist/album browse rules driven by {@link LibraryBrowsePrefs}. */
public final class ArtistBrowsePolicy {

    public static final class Track {
        public final String artist;
        public final String album;
        public final String albumArtist;
        public final long lastModified;

        public Track(String artist, String album, String albumArtist, long lastModified) {
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.albumArtist = albumArtist != null ? albumArtist.trim() : "";
            this.lastModified = lastModified;
        }
    }

    private ArtistBrowsePolicy() {}

    public static List<String> collectArtists(List<Track> library, LibraryBrowsePrefs prefs) {
        if (library == null || library.isEmpty()) return Collections.emptyList();
        Map<String, String> displayByKey = new HashMap<String, String>();
        Map<String, Integer> trackCounts = new HashMap<String, Integer>();
        Map<String, Long> recentByKey = new HashMap<String, Long>();
        for (Track song : library) {
            List<String> names = creditedArtists(song.artist, prefs);
            for (String raw : names) {
                String display = displayArtist(raw, prefs);
                if (display.isEmpty() || isUnknownArtist(display)) continue;
                String key = ArtistNames.matchKey(display);
                if (!displayByKey.containsKey(key)) {
                    displayByKey.put(key, display);
                } else {
                    displayByKey.put(key, ArtistNames.preferCanonical(displayByKey.get(key), display));
                }
                trackCounts.put(key, trackCounts.containsKey(key) ? trackCounts.get(key) + 1 : 1);
                long lm = song.lastModified;
                Long prev = recentByKey.get(key);
                if (prev == null || lm > prev) recentByKey.put(key, lm);
            }
        }
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> e : displayByKey.entrySet()) {
            String key = e.getKey();
            String name = e.getValue();
            if (!passesArtistFilter(name, library, prefs, trackCounts.get(key))) continue;
            out.add(name);
        }
        sortArtistNames(out, prefs, trackCounts, recentByKey);
        return out;
    }

    public static boolean shouldSkipAlbumPicker(String artist, LibraryBrowsePrefs prefs, List<Track> library) {
        if (artist == null || artist.trim().isEmpty()) return false;
        int mode = prefs != null ? prefs.guestBrowseMode() : LibraryBrowsePrefs.GUEST_BROWSE_AUTO;
        if (mode == LibraryBrowsePrefs.GUEST_BROWSE_ALWAYS_SONGS) return true;
        if (mode == LibraryBrowsePrefs.GUEST_BROWSE_ALWAYS_ALBUMS) return false;
        return !hasOwnAlbum(artist, library, prefs);
    }

    public static boolean hasOwnAlbum(String artist, List<Track> library, LibraryBrowsePrefs prefs) {
        if (artist == null || artist.trim().isEmpty() || library == null) return false;
        String normalized = displayArtist(artist, prefs);
        Set<String> seenAlbums = new HashSet<String>();
        for (Track song : library) {
            if (!ArtistParser.containsArtist(song.artist, normalized)) continue;
            String albumKey = AlbumNames.matchKey(song.album);
            if (albumKey.isEmpty() || !seenAlbums.add(albumKey)) continue;
            if (albumOwnerForBrowse(song.album, normalized, library, prefs) == null) {
                return true;
            }
        }
        return false;
    }

    /** Album owner when browsing as a guest credit; null when browsed artist owns the album. */
    public static String albumOwnerForBrowse(String album, String browsedArtist,
            List<Track> library, LibraryBrowsePrefs prefs) {
        if (album == null || browsedArtist == null || library == null) return null;
        Map<String, Integer> counts = new HashMap<String, Integer>();
        Map<String, String> displayByKey = new HashMap<String, String>();
        String browsedKey = ArtistNames.matchKey(displayArtist(browsedArtist, prefs));
        for (Track song : library) {
            if (!AlbumNames.equals(album, song.album)) continue;
            if (!ArtistParser.containsArtist(song.artist, browsedArtist)) continue;
            String owner = trackOwner(song, prefs);
            if (owner.isEmpty() || isUnknownArtist(owner)) continue;
            String key = ArtistNames.matchKey(owner);
            counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
            if (!displayByKey.containsKey(key)) displayByKey.put(key, owner);
        }
        if (counts.isEmpty()) return null;
        String bestKey = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestKey = e.getKey();
            }
        }
        if (bestKey == null || bestKey.equals(browsedKey)) return null;
        Integer browsedCount = counts.get(browsedKey);
        if (browsedCount != null && browsedCount >= bestCount) return null;
        return displayByKey.get(bestKey);
    }

    public static String albumBrowseSubtitle(String album, String browsedArtist,
            List<Track> library, LibraryBrowsePrefs prefs) {
        if (prefs == null || !prefs.albumOwnerSubtitles()) return "";
        String owner = albumOwnerForBrowse(album, browsedArtist, library, prefs);
        if (owner == null || owner.trim().isEmpty()) return "";
        if (ArtistNames.equals(owner, browsedArtist)) return "";
        return owner;
    }

    public static boolean isGuestOnlyArtistBrowse(String artist, String queryType,
            List<Track> library, LibraryBrowsePrefs prefs) {
        if (!"ARTIST".equals(queryType) || artist == null || artist.trim().isEmpty()) return false;
        if (prefs == null) return !hasOwnAlbum(artist, library, null);
        int mode = prefs.guestBrowseMode();
        if (mode == LibraryBrowsePrefs.GUEST_BROWSE_ALWAYS_SONGS) return true;
        if (mode == LibraryBrowsePrefs.GUEST_BROWSE_ALWAYS_ALBUMS) return false;
        return !hasOwnAlbum(artist, library, prefs);
    }

    public static String guestSongSubtitleOwner(String album, String browsedArtist,
            List<Track> library, LibraryBrowsePrefs prefs) {
        if (prefs == null || !prefs.guestSongSubtitles()) return "";
        return albumOwnerForBrowse(album, browsedArtist, library, prefs);
    }

    private static List<String> creditedArtists(String artistField, LibraryBrowsePrefs prefs) {
        if (prefs != null && !prefs.splitCredits()) {
            List<String> one = new ArrayList<String>();
            String primary = ArtistParser.primaryArtist(artistField);
            if (primary != null && !primary.trim().isEmpty()) one.add(primary.trim());
            else if (artistField != null && !artistField.trim().isEmpty()) one.add(artistField.trim());
            return one;
        }
        List<String> parts = ArtistParser.splitArtists(artistField);
        if (parts.isEmpty() && artistField != null && !artistField.trim().isEmpty()) {
            parts = Collections.singletonList(artistField.trim());
        }
        return parts;
    }

    private static String displayArtist(String raw, LibraryBrowsePrefs prefs) {
        if (raw == null) return "";
        return raw.trim();
    }

    private static String trackOwner(Track song, LibraryBrowsePrefs prefs) {
        if (song.albumArtist != null && !song.albumArtist.isEmpty()
                && !isUnknownArtist(song.albumArtist)) {
            return displayArtist(song.albumArtist, prefs);
        }
        return displayArtist(ArtistParser.primaryArtist(song.artist), prefs);
    }

    private static boolean passesArtistFilter(String artist, List<Track> library,
            LibraryBrowsePrefs prefs, Integer trackCount) {
        if (prefs == null) return true;
        int filter = prefs.artistFilter();
        if (filter == LibraryBrowsePrefs.FILTER_ALL) return true;
        if (filter == LibraryBrowsePrefs.FILTER_MIN_TWO_TRACKS) {
            return trackCount != null && trackCount >= 2;
        }
        boolean owns = hasOwnAlbum(artist, library, prefs);
        if (filter == LibraryBrowsePrefs.FILTER_OWNERS_ONLY) return owns;
        if (filter == LibraryBrowsePrefs.FILTER_HIDE_GUEST_ONLY) return owns;
        return true;
    }

    private static void sortArtistNames(List<String> names, LibraryBrowsePrefs prefs,
            Map<String, Integer> trackCounts, Map<String, Long> recentByKey) {
        final int sort = prefs != null ? prefs.artistSort() : LibraryBrowsePrefs.ARTIST_SORT_NAME;
        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                if (sort == LibraryBrowsePrefs.ARTIST_SORT_TRACK_COUNT) {
                    int ca = countFor(a, trackCounts);
                    int cb = countFor(b, trackCounts);
                    if (ca != cb) return cb - ca;
                } else if (sort == LibraryBrowsePrefs.ARTIST_SORT_RECENT) {
                    long ra = recentFor(a, recentByKey);
                    long rb = recentFor(b, recentByKey);
                    if (ra != rb) return ra > rb ? -1 : 1;
                }
                return a.compareToIgnoreCase(b);
            }
        });
    }

    private static int countFor(String name, Map<String, Integer> trackCounts) {
        Integer c = trackCounts.get(ArtistNames.matchKey(name));
        return c != null ? c : 0;
    }

    private static long recentFor(String name, Map<String, Long> recentByKey) {
        Long r = recentByKey.get(ArtistNames.matchKey(name));
        return r != null ? r : 0L;
    }

    private static boolean isUnknownArtist(String name) {
        return "Unknown Artist".equalsIgnoreCase(name)
                || "Various Artists".equalsIgnoreCase(name);
    }
}
