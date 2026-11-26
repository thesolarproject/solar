package com.solar.launcher.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 2026-07-18 — Tier-0 RAM indexes for library browse (artists/albums/genres/years/counts) keyed by scan gen.
 * Layman: after a scan we keep sorted name lists in memory so opening Artists doesn’t re-walk storage.
 * Technical: rebuild once per libraryScanGen from compact path/artist/album triples; no full SongItem.
 * Reversal: delete; rebuild categories by scanning customLibrary each open.
 */
public final class LibraryRamCache {

    /** Compact row for index build — avoids holding full SongItem in the index path. */
    public static final class NavRow {
        public final String path;
        public final String artist;
        public final String album;

        public NavRow(String path, String artist, String album) {
            this.path = path != null ? path : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
        }
    }

    private int libraryGen = -1;
    private LibraryMemoryBudget.Mode mode = LibraryMemoryBudget.Mode.FULL_RESIDENT;
    private List<String> artists = Collections.emptyList();
    private List<String> albums = Collections.emptyList();
    // 2026-07-20 — Genre/year Tier-0 (SEGMENTED menus); was empty until FULL SongRow walk.
    private List<String> genres = Collections.emptyList();
    private List<String> years = Collections.emptyList();
    private int trackCount;
    private final LibrarySegmentCache<NavRow> segments = new LibrarySegmentCache<NavRow>();

    public LibraryRamCache() {}

    public synchronized void invalidate() {
        libraryGen = -1;
        artists = Collections.emptyList();
        albums = Collections.emptyList();
        genres = Collections.emptyList();
        years = Collections.emptyList();
        trackCount = 0;
        segments.clear();
        mode = LibraryMemoryBudget.Mode.FULL_RESIDENT;
    }

    /**
     * Keep existing Tier-0 indexes valid after an external scan-generation bump.
     * Layman: leaving/re-entering the browser cancels stale async work, but the
     * artist/album/genre/year shelves we already built are still good.
     * Reversal: delete and let callers rebuild after teardown.
     */
    public synchronized void rebindGeneration(int gen) {
        if (libraryGen >= 0) {
            libraryGen = gen;
        }
    }

    /**
     * Rebuild Tier-0 indexes when scan generation changes.
     * Layman: remember artist/album name lists for this library generation.
     * Technical: walks NavRow triples; prefers {@link #rebuildFromDistinct} when SQL DISTINCT is available.
     */
    public synchronized void rebuild(int gen, List<NavRow> rows) {
        if (rows == null) rows = Collections.emptyList();
        if (gen == libraryGen && trackCount == rows.size() && !artists.isEmpty()) {
            return;
        }
        libraryGen = gen;
        trackCount = rows.size();
        mode = LibraryMemoryBudget.chooseMode(trackCount);
        Set<String> artistSet = new HashSet<String>();
        Set<String> albumSet = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            NavRow r = rows.get(i);
            if (r == null) continue;
            String ar = r.artist.trim();
            if (isIndexableArtist(ar)) artistSet.add(ar);
            String al = r.album.trim();
            if (isIndexableAlbum(al)) albumSet.add(al);
        }
        applyDistinctSets(artistSet, albumSet);
        // NavRow has no genre/year — leave those empty until DISTINCT or CategoryIndex fill.
        genres = Collections.emptyList();
        years = Collections.emptyList();
        segments.clear();
        // Warm first segment for SEGMENTED opens (visible window near top).
        if (mode == LibraryMemoryBudget.Mode.SEGMENTED && !rows.isEmpty()) {
            int bs = segments.blockSize();
            int end = Math.min(bs, rows.size());
            segments.putBlock(0, rows.subList(0, end));
        }
    }

    /**
     * 2026-07-20 — Tier-0 from SQL DISTINCT artist/album (no SongItem / full NavRow walk).
     * Layman: after a scan, load name lists from the DB index without pulling every song into RAM.
     * Technical: hydrate path when SEGMENTED — store.listDistinct* + countTracks, then this.
     * Reversal: call {@link #rebuild(int, List)} with NavRows from loadAll/loadRange.
     */
    public synchronized void rebuildFromDistinct(int gen, int tracks,
            List<String> artistNames, List<String> albumNames) {
        rebuildFromDistinct(gen, tracks, artistNames, albumNames, null, null);
    }

    /**
     * 2026-07-20 — Tier-0 from SQL DISTINCT including genre/year (SEGMENTED Genre/Year menus).
     * Layman: also remember genre and year shelves so those menus are not blank on big libraries.
     * Technical: same as artist/album DISTINCT; empty genre/year lists stay empty (not null).
     * Reversal: {@link #rebuildFromDistinct(int, int, List, List)} without genre/year args.
     */
    public synchronized void rebuildFromDistinct(int gen, int tracks,
            List<String> artistNames, List<String> albumNames,
            List<String> genreNames, List<String> yearNames) {
        libraryGen = gen;
        trackCount = tracks < 0 ? 0 : tracks;
        mode = LibraryMemoryBudget.chooseMode(trackCount);
        Set<String> artistSet = new HashSet<String>();
        Set<String> albumSet = new HashSet<String>();
        if (artistNames != null) {
            for (int i = 0; i < artistNames.size(); i++) {
                String ar = artistNames.get(i);
                if (ar == null) continue;
                ar = ar.trim();
                if (isIndexableArtist(ar)) artistSet.add(ar);
            }
        }
        if (albumNames != null) {
            for (int i = 0; i < albumNames.size(); i++) {
                String al = albumNames.get(i);
                if (al == null) continue;
                al = al.trim();
                if (isIndexableAlbum(al)) albumSet.add(al);
            }
        }
        applyDistinctSets(artistSet, albumSet);
        applyGenreYearLists(genreNames, yearNames);
        segments.clear();
    }

    /** Sort and freeze artist/album name lists for browse. 2026-07-20 */
    private void applyDistinctSets(Set<String> artistSet, Set<String> albumSet) {
        List<String> aOut = new ArrayList<String>(artistSet);
        Collections.sort(aOut, String.CASE_INSENSITIVE_ORDER);
        artists = Collections.unmodifiableList(aOut);
        List<String> alOut = new ArrayList<String>(albumSet);
        Collections.sort(alOut, String.CASE_INSENSITIVE_ORDER);
        albums = Collections.unmodifiableList(alOut);
    }

    /**
     * 2026-07-20 — Sort and freeze genre/year Tier-0 lists.
     * Layman: put genre and year names in order so the menus look like Artists/Albums.
     */
    private void applyGenreYearLists(List<String> genreNames, List<String> yearNames) {
        Set<String> genreSet = new HashSet<String>();
        if (genreNames != null) {
            for (int i = 0; i < genreNames.size(); i++) {
                String g = genreNames.get(i);
                if (g == null) continue;
                g = g.trim();
                if (isIndexableGenre(g)) genreSet.add(g);
            }
        }
        List<String> gOut = new ArrayList<String>(genreSet);
        Collections.sort(gOut, String.CASE_INSENSITIVE_ORDER);
        genres = Collections.unmodifiableList(gOut);

        Set<String> yearSet = new HashSet<String>();
        if (yearNames != null) {
            for (int i = 0; i < yearNames.size(); i++) {
                String y = yearNames.get(i);
                if (y == null) continue;
                y = y.trim();
                if (isIndexableYear(y)) yearSet.add(y);
            }
        }
        List<String> yOut = new ArrayList<String>(yearSet);
        Collections.sort(yOut, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.compareToIgnoreCase(b);
            }
        });
        years = Collections.unmodifiableList(yOut);
    }

    /** Skip blank / placeholder artist labels in Tier-0. 2026-07-20 */
    static boolean isIndexableArtist(String name) {
        return name != null && !name.isEmpty() && !"Unknown Artist".equalsIgnoreCase(name);
    }

    /** Skip blank / placeholder album labels in Tier-0. 2026-07-20 */
    static boolean isIndexableAlbum(String name) {
        return name != null && !name.isEmpty() && !"Unknown Album".equalsIgnoreCase(name);
    }

    /** Skip blank / placeholder genre labels in Tier-0. 2026-07-20 */
    static boolean isIndexableGenre(String name) {
        return name != null && !name.isEmpty() && !"Unknown Genre".equalsIgnoreCase(name);
    }

    /** Skip blank / non-positive year labels in Tier-0. 2026-07-20 */
    static boolean isIndexableYear(String label) {
        if (label == null || label.isEmpty()) return false;
        try {
            return Integer.parseInt(label.trim()) > 0;
        } catch (NumberFormatException e) {
            return !label.trim().isEmpty();
        }
    }

    public synchronized int generation() {
        return libraryGen;
    }

    public synchronized LibraryMemoryBudget.Mode mode() {
        return mode;
    }

    public synchronized int trackCount() {
        return trackCount;
    }

    public synchronized List<String> artists(int gen) {
        return libraryGen == gen ? artists : Collections.<String>emptyList();
    }

    public synchronized List<String> albums(int gen) {
        return libraryGen == gen ? albums : Collections.<String>emptyList();
    }

    /** Genre names for this scan gen (SEGMENTED Tier-0). 2026-07-20 */
    public synchronized List<String> genres(int gen) {
        return libraryGen == gen ? genres : Collections.<String>emptyList();
    }

    /** Year labels for this scan gen (SEGMENTED Tier-0). 2026-07-20 */
    public synchronized List<String> years(int gen) {
        return libraryGen == gen ? years : Collections.<String>emptyList();
    }

    public synchronized LibrarySegmentCache<NavRow> segments() {
        return segments;
    }

    /**
     * 2026-07-20 — Shrink SEGMENTED NavRow pages under MemoryRelease (Tier-0 names stay).
     * Layman: drop cached song-shelf chunks; artist/album/genre/year name lists remain.
     * Reversal: no-op; only clear() on invalidate.
     */
    public synchronized void shrinkSegments(int keepBlocks) {
        segments.trimTo(keepBlocks);
    }

    /** True when Tier-0 artists list is warm for this gen. */
    public synchronized boolean hasArtists(int gen) {
        return libraryGen == gen && !artists.isEmpty();
    }

    static void selfCheck() {
        LibraryRamCache c = new LibraryRamCache();
        List<NavRow> rows = new ArrayList<NavRow>();
        rows.add(new NavRow("/a", "Zebra", "Z-Album"));
        rows.add(new NavRow("/b", "Alpha", "A-Album"));
        c.rebuild(1, rows);
        if (!"Alpha".equals(c.artists(1).get(0))) throw new AssertionError("sort");
        if (c.albums(1).size() != 2) throw new AssertionError("albums");
        if (c.trackCount() != 2) throw new AssertionError("count");
        // 2026-07-20 — DISTINCT path must match NavRow rebuild without walking songs.
        LibraryRamCache d = new LibraryRamCache();
        List<String> arts = new ArrayList<String>();
        arts.add("Zebra");
        arts.add("Alpha");
        arts.add("Unknown Artist");
        List<String> albs = new ArrayList<String>();
        albs.add("Z-Album");
        albs.add("A-Album");
        List<String> gens = new ArrayList<String>();
        gens.add("Rock");
        gens.add("Jazz");
        gens.add("Unknown Genre");
        List<String> yrs = new ArrayList<String>();
        yrs.add("1999");
        yrs.add("2001");
        yrs.add("0");
        d.rebuildFromDistinct(2, 400, arts, albs, gens, yrs);
        if (!"Alpha".equals(d.artists(2).get(0))) throw new AssertionError("distinct sort");
        if (d.artists(2).size() != 2) throw new AssertionError("unknown filtered");
        if (d.trackCount() != 400) throw new AssertionError("distinct count");
        if (d.mode() != LibraryMemoryBudget.Mode.SEGMENTED) {
            throw new AssertionError("400 tracks should segment");
        }
        if (d.genres(2).size() != 2) throw new AssertionError("genres filtered");
        if (!"Jazz".equals(d.genres(2).get(0))) throw new AssertionError("genre sort");
        if (d.years(2).size() != 2) throw new AssertionError("years filtered");
        if (!isIndexableArtist("Alpha") || isIndexableArtist("Unknown Artist")) {
            throw new AssertionError("artist filter");
        }
        if (!isIndexableAlbum("A") || isIndexableAlbum("Unknown Album")) {
            throw new AssertionError("album filter");
        }
        if (!isIndexableGenre("Rock") || isIndexableGenre("Unknown Genre")) {
            throw new AssertionError("genre filter");
        }
        if (!isIndexableYear("1999") || isIndexableYear("0")) {
            throw new AssertionError("year filter");
        }
        // 2026-11-26 — invalidate() drops the binding and the budget mode; a later
        // rebindGeneration() must NOT fake a bound generation, so readers rehydrate
        // instead of opening blank shelves (MainActivity.ensureLibraryIndexesBound).
        LibraryRamCache e = new LibraryRamCache();
        e.rebuildFromDistinct(3, 400, arts, albs, gens, yrs);
        e.invalidate();
        e.rebindGeneration(4);
        if (e.generation() != -1) throw new AssertionError("invalidate keeps binding");
        if (!e.artists(4).isEmpty()) throw new AssertionError("rebind after invalidate");
        if (e.mode() != LibraryMemoryBudget.Mode.FULL_RESIDENT) {
            throw new AssertionError("invalidate resets mode");
        }
    }
}
