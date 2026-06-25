package com.solar.launcher;

import com.solar.launcher.deezer.DeezerResult;
import com.solar.launcher.deezer.DeezerSearch;
import com.solar.launcher.soulseek.SoulseekClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Merge and dedupe helpers for unified Get Music search results. */
public final class GetMusicSearch {
    private static final int MIN_CONTAINER_TRACKS = 2;

    private GetMusicSearch() {}

    public static List<MusicSearchEntry> mergeDeezerFirst(List<DeezerResult> deezer,
            List<SoulseekClient.Result> reach) {
        List<MusicSearchEntry> out = new ArrayList<MusicSearchEntry>();
        Set<String> deezerKeys = new HashSet<String>();
        if (deezer != null) {
            for (DeezerResult r : deezer) {
                if (r == null) continue;
                MusicSearchEntry e = MusicSearchEntry.deezer(r);
                out.add(e);
                String k = e.dedupeKey();
                if (!k.isEmpty()) deezerKeys.add(k);
            }
        }
        if (reach != null) {
            for (SoulseekClient.Result r : reach) {
                if (r == null) continue;
                MusicSearchEntry e = MusicSearchEntry.reach(r);
                String k = e.dedupeKey();
                if (!k.isEmpty() && deezerKeys.contains(k)) continue;
                out.add(e);
            }
        }
        return out;
    }

    /**
     * Build top-level rows with artists, albums, folders, and loose tracks round-robin
     * interleaved so one result type does not dominate the list.
     */
    public static List<MusicSearchEntry> organizeWithContainers(
            List<DeezerSearch.DeezerArtist> artists, List<MusicSearchEntry> flat) {
        if (flat == null) flat = new ArrayList<MusicSearchEntry>();
        List<MusicSearchEntry> deezerTracks = new ArrayList<MusicSearchEntry>();
        List<MusicSearchEntry> reachTracks = new ArrayList<MusicSearchEntry>();
        for (MusicSearchEntry e : flat) {
            if (e == null || e.isContainer()) continue;
            if (e.source == MusicSearchEntry.Source.DEEZER) deezerTracks.add(e);
            else if (e.source == MusicSearchEntry.Source.REACH) reachTracks.add(e);
        }

        List<MusicSearchEntry> artistRows = new ArrayList<MusicSearchEntry>();
        if (artists != null) {
            for (DeezerSearch.DeezerArtist a : artists) {
                if (a == null || a.id <= 0 || a.name.isEmpty()) continue;
                artistRows.add(MusicSearchEntry.deezerArtist(a.id, a.name));
            }
        }

        List<MusicSearchEntry> albumRows = groupDeezerAlbums(deezerTracks);
        List<MusicSearchEntry> folderRows = groupReachFolders(reachTracks);
        Collections.sort(albumRows, containerLabelOrder());
        Collections.sort(folderRows, containerLabelOrder());

        List<MusicSearchEntry> containers = new ArrayList<MusicSearchEntry>();
        containers.addAll(albumRows);
        containers.addAll(folderRows);

        Set<String> deezerKeys = deezerDedupeKeysFromEntries(deezerTracks);
        List<MusicSearchEntry> trackRows = new ArrayList<MusicSearchEntry>();
        trackRows.addAll(collectUngroupedDeezerTracks(deezerTracks));
        for (MusicSearchEntry e : reachTracks) {
            if (GetMusicSearch.reachMatchesDeezer(e.reach, deezerKeys)) continue;
            if (!isReachInFolderContainer(e, containers)) trackRows.add(e);
        }

        List<List<MusicSearchEntry>> buckets = new ArrayList<List<MusicSearchEntry>>();
        buckets.add(artistRows);
        buckets.add(albumRows);
        buckets.add(folderRows);
        buckets.add(trackRows);
        return interleaveRoundRobin(buckets);
    }

    /** Organize Reach-only search hits into interleaved folder and track rows. */
    public static List<MusicSearchEntry> organizeReachResults(List<SoulseekClient.Result> reach) {
        List<MusicSearchEntry> flat = new ArrayList<MusicSearchEntry>();
        if (reach != null) {
            for (SoulseekClient.Result r : reach) {
                if (r != null) flat.add(MusicSearchEntry.reach(r));
            }
        }
        return organizeWithContainers(null, flat);
    }

    /** Organize Deezer-only search hits into interleaved artist, album, and track rows. */
    public static List<MusicSearchEntry> organizeDeezerResults(
            List<DeezerSearch.DeezerArtist> artists, List<DeezerResult> tracks) {
        List<MusicSearchEntry> flat = new ArrayList<MusicSearchEntry>();
        if (tracks != null) {
            for (DeezerResult r : tracks) {
                if (r != null) flat.add(MusicSearchEntry.deezer(r));
            }
        }
        return organizeWithContainers(artists, flat);
    }

    /** Round-robin merge: one row from each non-empty bucket per pass (A,B,C,D,A,B,…). */
    static List<MusicSearchEntry> interleaveRoundRobin(List<List<MusicSearchEntry>> buckets) {
        List<MusicSearchEntry> out = new ArrayList<MusicSearchEntry>();
        if (buckets == null || buckets.isEmpty()) return out;
        int n = buckets.size();
        int[] idx = new int[n];
        boolean more = true;
        while (more) {
            more = false;
            for (int i = 0; i < n; i++) {
                List<MusicSearchEntry> bucket = buckets.get(i);
                if (bucket == null || idx[i] >= bucket.size()) continue;
                out.add(bucket.get(idx[i]++));
                more = true;
            }
        }
        return out;
    }

    private static Comparator<MusicSearchEntry> containerLabelOrder() {
        return new Comparator<MusicSearchEntry>() {
            @Override
            public int compare(MusicSearchEntry a, MusicSearchEntry b) {
                return a.containerLabel.compareToIgnoreCase(b.containerLabel);
            }
        };
    }

    /** Backward-compatible wrapper when no artist search results. */
    public static List<MusicSearchEntry> organizeWithContainers(List<MusicSearchEntry> flat) {
        return organizeWithContainers(null, flat);
    }

    private static boolean isReachInFolderContainer(MusicSearchEntry track,
            List<MusicSearchEntry> containers) {
        if (track == null || track.reach == null) return false;
        String folder = reachParentFolder(track.reach);
        if (folder.isEmpty()) return false;
        for (MusicSearchEntry c : containers) {
            if (c.kind == MusicSearchEntry.RowKind.REACH_FOLDER
                    && folder.equalsIgnoreCase(c.containerLabel)) {
                return true;
            }
        }
        return false;
    }

    private static List<MusicSearchEntry> collectUngroupedDeezerTracks(List<MusicSearchEntry> tracks) {
        Map<String, List<MusicSearchEntry>> byAlbum = new LinkedHashMap<String, List<MusicSearchEntry>>();
        List<MusicSearchEntry> singles = new ArrayList<MusicSearchEntry>();
        for (MusicSearchEntry e : tracks) {
            if (e.deezer == null) continue;
            String album = e.deezer.album != null ? e.deezer.album.trim() : "";
            if (album.isEmpty()) {
                singles.add(e);
                continue;
            }
            String key = deezerAlbumKey(e.deezer);
            List<MusicSearchEntry> bucket = byAlbum.get(key);
            if (bucket == null) {
                bucket = new ArrayList<MusicSearchEntry>();
                byAlbum.put(key, bucket);
            }
            bucket.add(e);
        }
        List<MusicSearchEntry> out = new ArrayList<MusicSearchEntry>();
        for (List<MusicSearchEntry> bucket : byAlbum.values()) {
            if (bucket.size() < MIN_CONTAINER_TRACKS) out.addAll(bucket);
        }
        out.addAll(singles);
        return out;
    }

    private static Set<String> deezerDedupeKeysFromEntries(List<MusicSearchEntry> tracks) {
        Set<String> keys = new HashSet<String>();
        for (MusicSearchEntry e : tracks) {
            if (e.deezer == null) continue;
            String k = e.dedupeKey();
            if (!k.isEmpty()) keys.add(k);
        }
        return keys;
    }

    private static List<MusicSearchEntry> groupDeezerAlbums(List<MusicSearchEntry> tracks) {
        Map<String, List<MusicSearchEntry>> byAlbum = new LinkedHashMap<String, List<MusicSearchEntry>>();
        for (MusicSearchEntry e : tracks) {
            if (e.deezer == null) continue;
            String album = e.deezer.album != null ? e.deezer.album.trim() : "";
            if (album.isEmpty()) continue;
            String key = deezerAlbumKey(e.deezer);
            List<MusicSearchEntry> bucket = byAlbum.get(key);
            if (bucket == null) {
                bucket = new ArrayList<MusicSearchEntry>();
                byAlbum.put(key, bucket);
            }
            bucket.add(e);
        }
        List<MusicSearchEntry> out = new ArrayList<MusicSearchEntry>();
        for (Map.Entry<String, List<MusicSearchEntry>> en : byAlbum.entrySet()) {
            List<MusicSearchEntry> bucket = en.getValue();
            if (bucket.size() >= MIN_CONTAINER_TRACKS) {
                String label = formatDeezerAlbumLabel(bucket.get(0).deezer);
                out.add(MusicSearchEntry.deezerAlbum(label, new ArrayList<MusicSearchEntry>(bucket)));
            }
        }
        return out;
    }

    private static List<MusicSearchEntry> groupReachFolders(List<MusicSearchEntry> tracks) {
        Map<String, List<MusicSearchEntry>> byFolder = new LinkedHashMap<String, List<MusicSearchEntry>>();
        for (MusicSearchEntry e : tracks) {
            if (e.reach == null) continue;
            String folder = reachParentFolder(e.reach);
            if (folder.isEmpty()) continue;
            List<MusicSearchEntry> bucket = byFolder.get(folder);
            if (bucket == null) {
                bucket = new ArrayList<MusicSearchEntry>();
                byFolder.put(folder, bucket);
            }
            bucket.add(e);
        }
        List<MusicSearchEntry> out = new ArrayList<MusicSearchEntry>();
        for (Map.Entry<String, List<MusicSearchEntry>> en : byFolder.entrySet()) {
            List<MusicSearchEntry> bucket = en.getValue();
            if (bucket.size() >= MIN_CONTAINER_TRACKS) {
                out.add(MusicSearchEntry.reachFolder(en.getKey(), new ArrayList<MusicSearchEntry>(bucket)));
            }
        }
        return out;
    }

    public static String deezerAlbumKey(DeezerResult r) {
        if (r == null) return "";
        String artist = r.artist != null ? r.artist.trim().toLowerCase(Locale.US) : "";
        String album = r.album != null ? r.album.trim().toLowerCase(Locale.US) : "";
        if (r.albumId > 0) return "id:" + r.albumId;
        return artist + "\u0000" + album;
    }

    public static String formatDeezerAlbumLabel(DeezerResult r) {
        if (r == null) return "";
        String album = r.album != null ? r.album.trim() : "";
        String artist = r.artist != null ? r.artist.trim() : "";
        if (!artist.isEmpty() && !album.isEmpty()) return artist + " · " + album;
        return !album.isEmpty() ? album : artist;
    }

    public static String formatDeezerReleaseLabel(String recordType, String title) {
        String type = recordType != null ? recordType.trim().toLowerCase(Locale.US) : "album";
        if ("single".equals(type)) return "Single · " + title;
        if ("ep".equals(type)) return "EP · " + title;
        return "Album · " + title;
    }

    /** Parent folder path from a Reach filename, or empty when at share root. */
    public static String reachParentFolder(SoulseekClient.Result r) {
        if (r == null || r.filename == null) return "";
        String path = r.filename.replace('/', '\\');
        int slash = path.lastIndexOf('\\');
        if (slash <= 0) return "";
        return path.substring(0, slash).trim();
    }

    public static boolean reachMatchesDeezer(SoulseekClient.Result reach, Set<String> deezerKeys) {
        if (reach == null || deezerKeys == null || deezerKeys.isEmpty()) return false;
        String k = MusicSearchEntry.reach(reach).dedupeKey();
        return !k.isEmpty() && deezerKeys.contains(k);
    }

    public static Set<String> deezerDedupeKeys(List<DeezerResult> deezer) {
        Set<String> keys = new HashSet<String>();
        if (deezer == null) return keys;
        for (DeezerResult r : deezer) {
            if (r == null) continue;
            String k = MusicSearchEntry.deezer(r).dedupeKey();
            if (!k.isEmpty()) keys.add(k);
        }
        return keys;
    }

    public static String formatDeezerSubtitle(DeezerResult r) {
        if (r == null) return "";
        String artist = r.artist != null ? r.artist.trim() : "";
        String album = r.album != null ? r.album.trim() : "";
        if (!artist.isEmpty() && !album.isEmpty()) return artist + " · " + album;
        if (!artist.isEmpty()) return artist;
        if (!album.isEmpty()) return album;
        return "";
    }

    public static String formatReachTitle(SoulseekClient.Result r) {
        if (r == null) return "";
        return r.title();
    }

    public static String formatReachSubtitle(String fromUserLabel) {
        return fromUserLabel != null ? fromUserLabel : "";
    }

    public static String reachResultKey(SoulseekClient.Result r) {
        if (r == null) return "";
        return (r.username + "\u0000" + r.filename).toLowerCase(Locale.US);
    }
}
