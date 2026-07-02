package com.solar.launcher;

import com.solar.launcher.flow.FlowCatalog;
import com.solar.launcher.flow.FlowCoverResolver;
import com.solar.launcher.flow.FlowItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared album rack for Library → Albums and Flow → Albums — one canonical order.
 */
public final class LibraryAlbumRack {

    private LibraryAlbumRack() {}

    /** Ordered Flow carousel / library album list. */
    public static List<FlowItem> build(List<FlowCatalog.SongRow> library, LibraryBrowsePrefs prefs,
            List<ArtistBrowsePolicy.Track> policyTracks, boolean multiTrackOnly) {
        if (library == null || library.isEmpty()) return Collections.emptyList();
        Map<String, String> albumByKey = new HashMap<String, String>();
        Map<String, List<File>> tracksByAlbumKey = new HashMap<String, List<File>>();
        Map<String, Map<String, Integer>> artistVotesByAlbumKey = new HashMap<String, Map<String, Integer>>();
        Map<String, Long> maxModifiedByAlbumKey = new HashMap<String, Long>();

        for (FlowCatalog.SongRow song : library) {
            if (song.album == null || song.album.trim().isEmpty()
                    || "Unknown Album".equalsIgnoreCase(song.album.trim())) continue;
            String album = song.album.trim();
            String albumKey = AlbumNames.matchKey(album);
            if (prefs != null && prefs.normalizeAlbumCase()) {
                registerAlbumVariant(albumByKey, album);
            } else {
                albumByKey.put(albumKey, album);
            }
            String rawArtist = song.artist != null ? song.artist.trim() : "";
            if (!rawArtist.isEmpty()) {
                Map<String, Integer> votes = artistVotesByAlbumKey.get(albumKey);
                if (votes == null) {
                    votes = new HashMap<String, Integer>();
                    artistVotesByAlbumKey.put(albumKey, votes);
                }
                Integer n = votes.get(rawArtist);
                votes.put(rawArtist, n != null ? n + 1 : 1);
            }
            Long prev = maxModifiedByAlbumKey.get(albumKey);
            if (prev == null || song.lastModified > prev) {
                maxModifiedByAlbumKey.put(albumKey, song.lastModified);
            }
        }

        for (FlowCatalog.SongRow song : library) {
            if (song.album == null || song.album.trim().isEmpty()
                    || "Unknown Album".equalsIgnoreCase(song.album.trim())) continue;
            String albumKey = AlbumNames.matchKey(song.album.trim());
            List<File> tracks = tracksByAlbumKey.get(albumKey);
            if (tracks == null) {
                tracks = new ArrayList<File>();
                tracksByAlbumKey.put(albumKey, tracks);
            }
            if (song.file != null && song.file.isFile()) tracks.add(song.file);
        }

        List<FlowItem> out = new ArrayList<FlowItem>();
        for (String albumKey : tracksByAlbumKey.keySet()) {
            List<File> tracks = tracksByAlbumKey.get(albumKey);
            if (multiTrackOnly && (tracks == null || tracks.size() <= 1)) continue;
            String album = albumByKey.get(albumKey);
            String artist = FlowCatalog.primaryArtistFromVotes(
                    artistVotesByAlbumKey.get(albumKey), "");
            String itemKey = FlowCoverResolver.albumMatchKey(album, artist);
            String sub = ArtistBrowsePolicy.albumBrowseSubtitle(album, artist, policyTracks, prefs);
            if (sub == null || sub.isEmpty()) sub = artist;
            FlowItem item = FlowItem.album(album, sub, itemKey,
                    FlowCatalog.sortTracksForAlbum(tracks, prefs, library), "");
            out.add(item);
        }
        return orderAlbums(out, policyTracks, prefs, maxModifiedByAlbumKey, tracksByAlbumKey);
    }

    /** Album titles in rack order — library category list uses same permutation as Flow. */
    public static List<String> albumTitles(List<FlowCatalog.SongRow> library,
            LibraryBrowsePrefs prefs, List<ArtistBrowsePolicy.Track> policyTracks) {
        List<FlowItem> items = build(library, prefs, policyTracks, false);
        List<String> titles = new ArrayList<String>(items.size());
        for (FlowItem item : items) titles.add(item.title);
        return titles;
    }

    static List<FlowItem> orderAlbums(List<FlowItem> items,
            List<ArtistBrowsePolicy.Track> policyTracks, LibraryBrowsePrefs prefs,
            Map<String, Long> maxModifiedByAlbumKey, Map<String, List<File>> tracksByAlbumKey) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        int sort = prefs != null ? prefs.albumRackSort() : LibraryBrowsePrefs.ALBUM_RACK_SORT_TITLE;
        List<FlowItem> filtered = filterByArtistPolicy(items, policyTracks, prefs);
        if (sort == LibraryBrowsePrefs.ALBUM_RACK_SORT_ARTIST_THEN_TITLE) {
            return orderByArtistPolicy(filtered, policyTracks, prefs);
        }
        if (sort == LibraryBrowsePrefs.ALBUM_RACK_SORT_RECENT) {
            Collections.sort(filtered, new Comparator<FlowItem>() {
                @Override
                public int compare(FlowItem a, FlowItem b) {
                    long ma = modifiedForItem(a, maxModifiedByAlbumKey);
                    long mb = modifiedForItem(b, maxModifiedByAlbumKey);
                    if (ma != mb) return ma > mb ? -1 : 1;
                    return a.title.compareToIgnoreCase(b.title);
                }
            });
            return filtered;
        }
        if (sort == LibraryBrowsePrefs.ALBUM_RACK_SORT_TRACK_COUNT) {
            Collections.sort(filtered, new Comparator<FlowItem>() {
                @Override
                public int compare(FlowItem a, FlowItem b) {
                    int ca = trackCount(a, tracksByAlbumKey);
                    int cb = trackCount(b, tracksByAlbumKey);
                    if (ca != cb) return ca > cb ? -1 : 1;
                    return a.title.compareToIgnoreCase(b.title);
                }
            });
            return filtered;
        }
        Collections.sort(filtered, new Comparator<FlowItem>() {
            @Override
            public int compare(FlowItem a, FlowItem b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return filtered;
    }

    private static List<FlowItem> filterByArtistPolicy(List<FlowItem> items,
            List<ArtistBrowsePolicy.Track> policyTracks, LibraryBrowsePrefs prefs) {
        List<String> artists = ArtistBrowsePolicy.collectArtists(policyTracks, prefs);
        final Set<String> allowed = new HashSet<String>();
        for (String name : artists) allowed.add(ArtistNames.matchKey(name));
        if (allowed.isEmpty()) return new ArrayList<FlowItem>(items);
        List<FlowItem> filtered = new ArrayList<FlowItem>();
        for (FlowItem item : items) {
            String aKey = artistKeyFromItem(item);
            if (aKey.isEmpty() || allowed.contains(aKey)) filtered.add(item);
        }
        return filtered;
    }

    /** Rack filter uses catalog matchKey artist — not subtitle hints for guest owners. */
    private static String artistKeyFromItem(FlowItem item) {
        if (item == null || item.matchKey == null) return "";
        int pipe = item.matchKey.indexOf('|');
        if (pipe > 0 && pipe + 1 < item.matchKey.length()) {
            return item.matchKey.substring(pipe + 1);
        }
        return ArtistNames.matchKey(item.subtitle != null ? item.subtitle : "");
    }

    /** Legacy Flow artist-rack ordering — available via album rack sort pref. */
    public static List<FlowItem> orderByArtistPolicy(List<FlowItem> items,
            List<ArtistBrowsePolicy.Track> policyTracks, LibraryBrowsePrefs prefs) {
        List<String> artists = ArtistBrowsePolicy.collectArtists(policyTracks, prefs);
        final Map<String, Integer> artistRank = new HashMap<String, Integer>();
        for (int i = 0; i < artists.size(); i++) {
            artistRank.put(ArtistNames.matchKey(artists.get(i)), i);
        }
        List<FlowItem> sorted = new ArrayList<FlowItem>(items);
        Collections.sort(sorted, new Comparator<FlowItem>() {
            @Override
            public int compare(FlowItem a, FlowItem b) {
                String ak = ArtistNames.matchKey(a.subtitle != null ? a.subtitle : "");
                String bk = ArtistNames.matchKey(b.subtitle != null ? b.subtitle : "");
                int ra = artistRank.containsKey(ak) ? artistRank.get(ak) : Integer.MAX_VALUE;
                int rb = artistRank.containsKey(bk) ? artistRank.get(bk) : Integer.MAX_VALUE;
                if (ra != rb) return ra < rb ? -1 : 1;
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return sorted;
    }

    private static long modifiedForItem(FlowItem item, Map<String, Long> maxModifiedByAlbumKey) {
        if (item == null || item.title == null) return 0L;
        Long v = maxModifiedByAlbumKey.get(AlbumNames.matchKey(item.title));
        return v != null ? v : 0L;
    }

    private static int trackCount(FlowItem item, Map<String, List<File>> tracksByAlbumKey) {
        if (item == null || item.title == null) return 0;
        List<File> t = tracksByAlbumKey.get(AlbumNames.matchKey(item.title));
        return t != null ? t.size() : (item.tracks != null ? item.tracks.size() : 0);
    }

    private static void registerAlbumVariant(Map<String, String> albumByKey, String raw) {
        String key = AlbumNames.matchKey(raw);
        String existing = albumByKey.get(key);
        if (existing == null) {
            albumByKey.put(key, raw);
        } else if (!existing.equals(raw)) {
            Map<String, Integer> counts = new HashMap<String, Integer>();
            counts.put(existing, 1);
            counts.put(raw, 1);
            albumByKey.put(key, AlbumNames.chooseCanonical(counts));
        }
    }
}
