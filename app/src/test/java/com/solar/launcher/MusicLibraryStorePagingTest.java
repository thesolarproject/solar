package com.solar.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.solar.launcher.library.LibraryMemoryBudget;

/**
 * 2026-07-20 — JVM-safe checks for SEGMENTED LIMIT/OFFSET helpers (no Android DB).
 * Full SQLite paging covered via count/range math + LibrarySegmentCache integration.
 */
public class MusicLibraryStorePagingTest {

    @Test
    public void segmentBlockMathMatchesStorePaging() {
        com.solar.launcher.library.LibrarySegmentCache<String> cache =
                new com.solar.launcher.library.LibrarySegmentCache<String>(4, 3);
        assertEquals(0, cache.blockIndexFor(0));
        assertEquals(0, cache.blockIndexFor(3));
        assertEquals(1, cache.blockIndexFor(4));
        List<String> page0 = new ArrayList<String>();
        page0.add("a");
        page0.add("b");
        page0.add("c");
        page0.add("d");
        cache.putBlock(0, page0);
        assertEquals("a", cache.get(0));
        assertEquals("d", cache.get(3));
        assertTrue(cache.get(4) == null);
    }

    @Test
    public void residentCopyFactorRaisesEstimate() {
        long one = com.solar.launcher.library.LibraryMemoryBudget.estimateFullBytes(1);
        assertEquals(
                (long) com.solar.launcher.library.LibraryMemoryBudget.BYTES_PER_SONG_ITEM
                        * com.solar.launcher.library.LibraryMemoryBudget.RESIDENT_COPY_FACTOR,
                one);
    }

    /** 2026-07-20 — OFFSET/LIMIT clamps used by loadTracksByArtist/Album. */
    @Test
    public void pageOffsetLimitNormalize() {
        assertEquals(0, MusicLibraryStore.normalizePageOffset(-3));
        assertEquals(12, MusicLibraryStore.normalizePageOffset(12));
        assertEquals(0, MusicLibraryStore.normalizePageLimit(0));
        assertEquals(0, MusicLibraryStore.normalizePageLimit(-1));
        assertEquals(64, MusicLibraryStore.normalizePageLimit(64));
        assertEquals(64, MusicLibraryStore.DEFAULT_PAGE_SIZE);
    }

    /**
     * 2026-07-20 — Artist/album/distinct SQL shapes stay LIMIT/OFFSET + COLLATE NOCASE.
     * Layman: prove the page queries ask SQLite for one chunk, not every song.
     */
    @Test
    public void artistAlbumPageSqlShapes() {
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_ARTIST.contains("LIMIT ? OFFSET ?"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_ARTIST.contains("album_artist"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_ALBUM.contains("album = ? COLLATE NOCASE"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_ARTIST_ALBUM.contains("LIMIT ? OFFSET ?"));
        assertTrue(MusicLibraryStore.SQL_COUNT_BY_ARTIST.startsWith("SELECT COUNT(*)"));
        assertTrue(MusicLibraryStore.SQL_COUNT_BY_ALBUM.startsWith("SELECT COUNT(*)"));
        assertTrue(MusicLibraryStore.SQL_DISTINCT_ARTISTS.contains("DISTINCT artist"));
        assertTrue(MusicLibraryStore.SQL_DISTINCT_ARTISTS.contains("Unknown Artist"));
        assertTrue(MusicLibraryStore.SQL_DISTINCT_ALBUMS.contains("DISTINCT album"));
        // 2026-07-20 — Multi-track shells filter (GROUP BY HAVING COUNT>1).
        assertTrue(MusicLibraryStore.SQL_DISTINCT_ALBUMS_MULTI_TRACK.contains("HAVING COUNT(*) > 1"));
        assertTrue(MusicLibraryStore.SQL_DISTINCT_ALBUMS_MULTI_TRACK.contains("GROUP BY album"));
        // 2026-07-20 — Favorites JOIN for SEGMENTED empty customLibrary.
        assertTrue(MusicLibraryStore.SQL_LOAD_FAVORITES.contains("INNER JOIN favorite_paths"));
        assertTrue(MusicLibraryStore.SQL_COUNT_FAVORITES.startsWith("SELECT COUNT(*)"));
        // 2026-07-20 — Has Stems path→size + Recently Added mtime pages.
        assertTrue(MusicLibraryStore.SQL_PATH_SIZES.contains("path"));
        assertTrue(MusicLibraryStore.SQL_PATH_SIZES.contains("size"));
        assertFalse(MusicLibraryStore.SQL_PATH_SIZES.contains("SELECT *"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_MTIME_DESC.contains("ORDER BY mtime DESC"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_MTIME_DESC.contains("LIMIT ? OFFSET ?"));
        // 2026-07-20 — Genre/year DISTINCT shapes for SEGMENTED Tier-0 menus.
        assertTrue(MusicLibraryStore.SQL_DISTINCT_GENRES.contains("DISTINCT genre"));
        assertTrue(MusicLibraryStore.SQL_DISTINCT_GENRES.contains("Unknown Genre"));
        assertTrue(MusicLibraryStore.SQL_TOP_DISCOVER_ARTISTS.contains("COUNT(*)"));
        assertTrue(MusicLibraryStore.SQL_TOP_DISCOVER_ARTISTS.contains("LIMIT ?"));
        assertTrue(MusicLibraryStore.SQL_TOP_DISCOVER_GENRES.contains("COUNT(*)"));
        assertTrue(MusicLibraryStore.SQL_TOP_DISCOVER_GENRES.contains("LIMIT ?"));
        assertTrue(MusicLibraryStore.SQL_DISTINCT_YEARS.contains("DISTINCT year"));
        assertTrue(MusicLibraryStore.SQL_DISTINCT_YEARS.contains("year > 0"));
        assertFalse(MusicLibraryStore.SQL_LOAD_BY_ARTIST.contains("SELECT * FROM tracks ORDER BY path"));
    }

    /** 2026-07-20 — Bind-arg counts match ? placeholders (prevents runtime bind mismatch). */
    @Test
    public void pageSqlPlaceholderCounts() {
        assertEquals(4, countPlaceholders(MusicLibraryStore.SQL_LOAD_BY_ARTIST));
        assertEquals(2, countPlaceholders(MusicLibraryStore.SQL_COUNT_BY_ARTIST));
        assertEquals(3, countPlaceholders(MusicLibraryStore.SQL_LOAD_BY_ALBUM));
        assertEquals(1, countPlaceholders(MusicLibraryStore.SQL_COUNT_BY_ALBUM));
        assertEquals(5, countPlaceholders(MusicLibraryStore.SQL_LOAD_BY_ARTIST_ALBUM));
        assertEquals(3, countPlaceholders(MusicLibraryStore.SQL_COUNT_BY_ARTIST_ALBUM));
        assertEquals(0, countPlaceholders(MusicLibraryStore.SQL_DISTINCT_ARTISTS));
        assertEquals(0, countPlaceholders(MusicLibraryStore.SQL_DISTINCT_ALBUMS));
        assertEquals(1, countPlaceholders(MusicLibraryStore.SQL_TOP_DISCOVER_ARTISTS));
        assertEquals(1, countPlaceholders(MusicLibraryStore.SQL_TOP_DISCOVER_GENRES));
        assertEquals(0, countPlaceholders(MusicLibraryStore.SQL_PATH_SIZES));
        assertEquals(2, countPlaceholders(MusicLibraryStore.SQL_LOAD_BY_MTIME_DESC));
    }

    /**
     * 2026-07-20 — Contract: SEGMENTED hydrate must not require loadAll.
     * Layman: when the library is big enough to page, we only need a count + name lists.
     * Technical: chooseMode(300+) → SEGMENTED; callers use countTracks + listDistinct*.
     */
    @Test
    public void segmentedModeImpliesNoFullResidentLoadAll() {
        assertEquals(LibraryMemoryBudget.Mode.SEGMENTED,
                LibraryMemoryBudget.chooseMode(300, 48L * 1024 * 1024, 64L * 1024 * 1024));
        assertTrue(MusicLibraryStore.SQL_DISTINCT_ARTISTS.contains("DISTINCT"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_ARTIST.contains("LIMIT ? OFFSET ?"));
        assertTrue(MusicLibraryStore.SQL_DISTINCT_GENRES.contains("DISTINCT genre"));
        assertTrue(MusicLibraryStore.SQL_DISTINCT_YEARS.contains("year > 0"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_GENRE.contains("LIMIT ? OFFSET ?"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_YEAR.contains("LIMIT ? OFFSET ?"));
        assertEquals(3, countPlaceholders(MusicLibraryStore.SQL_LOAD_BY_GENRE));
        assertEquals(3, countPlaceholders(MusicLibraryStore.SQL_LOAD_BY_YEAR));
    }

    /**
     * 2026-07-20 — Soulseek share scan under SEGMENTED reuses path→size SQL (not customLibrary snap).
     * Layman: sharing still lists every library song when RAM holds only pages.
     * Technical: MainActivity.runSoulseekShareScanIfNeeded → loadPathSizes → musicFilesFromPathSizes.
     * Was: customLibrary snap only → empty/missing shares. Reversal: that snap (+ single-root walk).
     */
    @Test
    public void soulseekShareScanSegmentedUsesPathSizesSql() {
        assertTrue(MusicLibraryStore.SQL_PATH_SIZES.contains("path"));
        assertTrue(MusicLibraryStore.SQL_PATH_SIZES.contains("size"));
        assertFalse(MusicLibraryStore.SQL_PATH_SIZES.contains("SELECT *"));
        assertEquals(LibraryMemoryBudget.Mode.SEGMENTED,
                LibraryMemoryBudget.chooseMode(300, 48L * 1024 * 1024, 64L * 1024 * 1024));
    }

    /**
     * 2026-07-20 — Album delete under SEGMENTED must page the same album SQL as playlist collect.
     * Layman: deleting an album still finds every song even when the in-RAM list is empty.
     * Technical: MainActivity.confirmDeleteAlbumFromDevice → collectTracksForQuery → these shapes.
     * Was: customLibrary walk → empty doomed set. Reversal: that walk (no SQL collect).
     */
    @Test
    public void albumDeleteSegmentedUsesAlbumPageSql() {
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_ALBUM.contains("album = ? COLLATE NOCASE"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_ALBUM.contains("LIMIT ? OFFSET ?"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_ARTIST_ALBUM.contains("AND album = ? COLLATE NOCASE"));
        assertTrue(MusicLibraryStore.SQL_LOAD_BY_ARTIST_ALBUM.contains("LIMIT ? OFFSET ?"));
        assertEquals(3, countPlaceholders(MusicLibraryStore.SQL_LOAD_BY_ALBUM));
        assertEquals(5, countPlaceholders(MusicLibraryStore.SQL_LOAD_BY_ARTIST_ALBUM));
    }

    /**
     * 2026-07-20 — Flow shells from Tier-0 titles (SEGMENTED empty SongRows).
     * Layman: album names alone still make a carousel list.
     */
    @Test
    public void flowShellsFromAlbumTitles() {
        java.util.ArrayList<String> titles = new java.util.ArrayList<String>();
        titles.add("Zebra");
        titles.add("Alpha");
        titles.add("Unknown Album");
        java.util.List<com.solar.launcher.flow.FlowItem> shells =
                LibraryAlbumRack.buildShellsFromTitles(titles);
        assertEquals(2, shells.size());
        assertEquals("Alpha", shells.get(0).title);
        assertTrue(shells.get(0).tracks.isEmpty());
    }

    /** Count `?` bind markers in a SQL string. 2026-07-20 */
    private static int countPlaceholders(String sql) {
        int n = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') n++;
        }
        return n;
    }
}
