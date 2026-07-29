package com.solar.launcher;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import com.solar.launcher.db.SolarDatabase;
import com.solar.launcher.db.SolarDbHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQLite cache for local Music library metadata — avoids re-reading ID3 on every scan.
 * ponytail: keyed by path + mtime + size; stale rows purged after each walk.
 */
public class MusicLibraryStore extends SolarDbHelper {
    private static final String DB_NAME = "music_library.db";
    /** 2026-07-20 — v5 adds artist/album browse indexes for SEGMENTED page queries. */
    private static final int DB_VERSION = 5;

    /**
     * 2026-07-20 — Default LIMIT for SEGMENTED artist/album song drills.
     * Layman: how many songs one scroll chunk loads from the DB.
     */
    public static final int DEFAULT_PAGE_SIZE = 64;

    // 2026-07-20 — SQL shapes for SEGMENTED drills (unit-tested; store methods execute these).
    // Reversal: delete + fall back to loadAll + in-memory filter in MainActivity.
    static final String SQL_LOAD_BY_ARTIST =
            "SELECT * FROM tracks WHERE (artist = ? COLLATE NOCASE OR album_artist = ? COLLATE NOCASE)"
                    + " ORDER BY album COLLATE NOCASE ASC, track_number ASC, path ASC"
                    + " LIMIT ? OFFSET ?";
    static final String SQL_COUNT_BY_ARTIST =
            "SELECT COUNT(*) FROM tracks WHERE (artist = ? COLLATE NOCASE OR album_artist = ? COLLATE NOCASE)";
    static final String SQL_LOAD_BY_ALBUM =
            "SELECT * FROM tracks WHERE album = ? COLLATE NOCASE"
                    + " ORDER BY track_number ASC, path ASC LIMIT ? OFFSET ?";
    static final String SQL_COUNT_BY_ALBUM =
            "SELECT COUNT(*) FROM tracks WHERE album = ? COLLATE NOCASE";
    static final String SQL_LOAD_BY_ARTIST_ALBUM =
            "SELECT * FROM tracks WHERE (artist = ? COLLATE NOCASE OR album_artist = ? COLLATE NOCASE)"
                    + " AND album = ? COLLATE NOCASE"
                    + " ORDER BY track_number ASC, path ASC LIMIT ? OFFSET ?";
    static final String SQL_COUNT_BY_ARTIST_ALBUM =
            "SELECT COUNT(*) FROM tracks WHERE (artist = ? COLLATE NOCASE OR album_artist = ? COLLATE NOCASE)"
                    + " AND album = ? COLLATE NOCASE";
    static final String SQL_DISTINCT_ARTISTS =
            "SELECT DISTINCT artist FROM tracks WHERE TRIM(artist) != ''"
                    + " AND artist != 'Unknown Artist' COLLATE NOCASE"
                    + " ORDER BY artist COLLATE NOCASE ASC";
    static final String SQL_DISTINCT_ALBUMS =
            "SELECT DISTINCT album FROM tracks WHERE TRIM(album) != ''"
                    + " AND album != 'Unknown Album' COLLATE NOCASE"
                    + " ORDER BY album COLLATE NOCASE ASC";
    /**
     * 2026-07-20 — Albums with 2+ tracks (Flow “multi-track albums only” under SEGMENTED shells).
     * Layman: skip one-song “albums” when that setting is on, without loading every song into RAM.
     * Technical: GROUP BY album HAVING COUNT(*)&gt;1. Reversal: use {@link #SQL_DISTINCT_ALBUMS} only.
     */
    static final String SQL_DISTINCT_ALBUMS_MULTI_TRACK =
            "SELECT album FROM tracks WHERE TRIM(album) != ''"
                    + " AND album != 'Unknown Album' COLLATE NOCASE"
                    + " GROUP BY album COLLATE NOCASE HAVING COUNT(*) > 1"
                    + " ORDER BY album COLLATE NOCASE ASC";
    // 2026-07-20 — Genre/year Tier-0 for SEGMENTED menus (same DISTINCT pattern as artists/albums).
    // Was: Genre/Year empty under SEGMENTED (needed SongRow walk). Reversal: drop these SQL constants.
    static final String SQL_DISTINCT_GENRES =
            "SELECT DISTINCT genre FROM tracks WHERE TRIM(genre) != ''"
                    + " AND genre != 'Unknown Genre' COLLATE NOCASE"
                    + " ORDER BY genre COLLATE NOCASE ASC";
    /** Bounded local-only Discover signals, ordered by representation in the library. */
    static final String SQL_TOP_DISCOVER_ARTISTS =
            "SELECT artist, COUNT(*) AS track_count FROM tracks"
                    + " WHERE TRIM(artist) != ''"
                    + " AND artist != 'Unknown Artist' COLLATE NOCASE"
                    + " GROUP BY artist COLLATE NOCASE"
                    + " ORDER BY track_count DESC, artist COLLATE NOCASE ASC LIMIT ?";
    static final String SQL_TOP_DISCOVER_GENRES =
            "SELECT genre, COUNT(*) AS track_count FROM tracks"
                    + " WHERE TRIM(genre) != ''"
                    + " AND genre != 'Unknown Genre' COLLATE NOCASE"
                    + " GROUP BY genre COLLATE NOCASE"
                    + " ORDER BY track_count DESC, genre COLLATE NOCASE ASC LIMIT ?";
    static final String SQL_DISTINCT_YEARS =
            "SELECT DISTINCT year FROM tracks WHERE year > 0 ORDER BY year ASC";
    // 2026-07-20 — Genre/year song drills under SEGMENTED (page, don’t loadAll).
    // Was: GENRE/YEAR collect empty in Approach 3. Reversal: drop; walk customLibrary only.
    static final String SQL_LOAD_BY_GENRE =
            "SELECT * FROM tracks WHERE genre = ? COLLATE NOCASE"
                    + " ORDER BY album COLLATE NOCASE ASC, track_number ASC, path ASC"
                    + " LIMIT ? OFFSET ?";
    static final String SQL_COUNT_BY_GENRE =
            "SELECT COUNT(*) FROM tracks WHERE genre = ? COLLATE NOCASE";
    static final String SQL_LOAD_BY_YEAR =
            "SELECT * FROM tracks WHERE year = ?"
                    + " ORDER BY album COLLATE NOCASE ASC, track_number ASC, path ASC"
                    + " LIMIT ? OFFSET ?";
    static final String SQL_COUNT_BY_YEAR =
            "SELECT COUNT(*) FROM tracks WHERE year = ?";
    /**
     * 2026-07-20 — Hearted songs via JOIN (SEGMENTED Favorites when customLibrary is empty).
     * Layman: list favorites from the DB without walking a full in-RAM library.
     * Was: Favorites walked customLibrary only → empty on large libs. Reversal: drop JOIN SQL.
     */
    static final String SQL_LOAD_FAVORITES =
            "SELECT t.* FROM tracks t INNER JOIN favorite_paths f ON f.path = t.path"
                    + " ORDER BY t.title COLLATE NOCASE ASC";
    static final String SQL_COUNT_FAVORITES =
            "SELECT COUNT(*) FROM tracks t INNER JOIN favorite_paths f ON f.path = t.path";
    /**
     * 2026-07-20 — Path+size only for Has Stems invert index + Soulseek share scan (SEGMENTED empty customLibrary).
     * Layman: ask the DB for file paths and sizes without loading full song rows into RAM.
     * Was: walk customLibrary SongItems. Reversal: drop; File-list index only.
     */
    static final String SQL_PATH_SIZES = "SELECT path, size FROM tracks";
    /**
     * 2026-07-20 — Recently Added pages by mtime (SEGMENTED; idx_tracks_mtime).
     * Layman: newest songs first, one scroll chunk at a time.
     * Was: date-sort full customLibrary (empty under SEGMENTED). Reversal: that RAM sort.
     */
    static final String SQL_LOAD_BY_MTIME_DESC =
            "SELECT * FROM tracks ORDER BY mtime DESC, path ASC LIMIT ? OFFSET ?";

    private static MusicLibraryStore instance;
    private boolean legacyTrackNumbersMigrated;
    /** 2026-07-06: DB sentinel — year read, tag has no release year (distinct from legacy 0). */
    static final int YEAR_UNKNOWN_SCANNED = -1;

    /** Batch upsert input — avoids per-row method-call overhead during scans. */
    public static final class Upsert {
        public final File file;
        public final String title;
        public final String artist;
        public final String album;
        public final String genre;
        public final String albumArtist;
        public final String durationMs;
        public final int trackNumber;
        public final int year;

        public Upsert(File file, String title, String artist, String album,
                String genre, String albumArtist, String durationMs, int trackNumber) {
            this(file, title, artist, album, genre, albumArtist, durationMs, trackNumber, 0);
        }

        public Upsert(File file, String title, String artist, String album,
                String genre, String albumArtist, String durationMs, int trackNumber, int year) {
            this.file = file;
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.genre = genre != null ? genre : "";
            this.albumArtist = albumArtist != null ? albumArtist : "";
            this.durationMs = durationMs != null ? durationMs : "";
            this.trackNumber = trackNumber;
            this.year = year;
        }
    }

    /** Cached row — maps to {@link MainActivity}'s SongItem fields. */
    public static final class Track {
        public final String path;
        public final long mtime;
        public final long size;
        public final String title;
        public final String artist;
        public final String album;
        public final String genre;
        public final String albumArtist;
        public final String durationMs;
        public final int trackNumber;
        public final int year;

        Track(String path, long mtime, long size, String title, String artist, String album,
                String genre, String albumArtist, String durationMs, int trackNumber) {
            this(path, mtime, size, title, artist, album, genre, albumArtist, durationMs, trackNumber, 0);
        }

        Track(String path, long mtime, long size, String title, String artist, String album,
                String genre, String albumArtist, String durationMs, int trackNumber, int year) {
            this.path = path;
            this.mtime = mtime;
            this.size = size;
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.genre = genre != null ? genre : "";
            this.albumArtist = albumArtist != null ? albumArtist : "";
            this.durationMs = durationMs != null ? durationMs : "";
            this.trackNumber = trackNumber;
            this.year = year;
        }

        /** Duration in whole seconds for Soulseek share attributes; 0 when unknown. */
        public int durationSec() {
            return parseDurationMs(durationMs) / 1000;
        }
    }

    private MusicLibraryStore(Context ctx) {
        this(ctx, true);
    }

    private MusicLibraryStore(Context ctx, boolean walEnabled) {
        super(ctx.getApplicationContext(), DB_NAME, DB_VERSION, walEnabled);
    }

    public static synchronized MusicLibraryStore getInstance(Context ctx) {
        if (instance == null) {
            instance = new MusicLibraryStore(ctx.getApplicationContext());
        }
        return instance;
    }

    static void resetInstanceForTest() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    /** In-memory DB for unit tests. */
    public static MusicLibraryStore openForTest(Context ctx) {
        resetInstanceForTest();
        final SQLiteDatabase[] mem = new SQLiteDatabase[1];
        instance = new MusicLibraryStore(ctx.getApplicationContext(), false) {
            @Override
            public synchronized SQLiteDatabase getWritableDatabase() {
                if (mem[0] == null) {
                    mem[0] = SQLiteDatabase.create(null);
                    onCreate(mem[0]);
                }
                return mem[0];
            }

            @Override
            public synchronized SQLiteDatabase getReadableDatabase() {
                return getWritableDatabase();
            }
        };
        return instance;
    }

    @Override
    public void onCreate(SolarDatabase db) {
        db.execSQL("CREATE TABLE tracks ("
                + "path TEXT PRIMARY KEY,"
                + "mtime INTEGER NOT NULL DEFAULT 0,"
                + "size INTEGER NOT NULL DEFAULT 0,"
                + "title TEXT NOT NULL DEFAULT '',"
                + "artist TEXT NOT NULL DEFAULT '',"
                + "album TEXT NOT NULL DEFAULT '',"
                + "genre TEXT NOT NULL DEFAULT '',"
                + "album_artist TEXT NOT NULL DEFAULT '',"
                + "duration_ms TEXT NOT NULL DEFAULT '',"
                + "track_number INTEGER NOT NULL DEFAULT 0,"
                + "year INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_tracks_mtime ON tracks(mtime)");
        ensureBrowseIndexes(db);
        db.execSQL("CREATE TABLE favorite_paths (path TEXT PRIMARY KEY)");
        db.execSQL("CREATE TABLE audiobook_bookmarks ("
                + "path TEXT PRIMARY KEY,"
                + "position_ms INTEGER NOT NULL DEFAULT 0,"
                + "chapter_index INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SolarDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN track_number INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS tracks");
            onCreate(db);
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE tracks ADD COLUMN year INTEGER NOT NULL DEFAULT 0");
            } catch (Exception ignored) {}
            db.execSQL("CREATE TABLE IF NOT EXISTS favorite_paths (path TEXT PRIMARY KEY)");
            db.execSQL("CREATE TABLE IF NOT EXISTS audiobook_bookmarks ("
                    + "path TEXT PRIMARY KEY,"
                    + "position_ms INTEGER NOT NULL DEFAULT 0,"
                    + "chapter_index INTEGER NOT NULL DEFAULT 0,"
                    + "updated_at INTEGER NOT NULL DEFAULT 0)");
        }
        // 2026-07-20 — Artist/album indexes for SEGMENTED page drills (no full customLibrary).
        if (oldVersion < 5) {
            ensureBrowseIndexes(db);
        }
    }

    /**
     * 2026-07-20 — Indexes so artist/album page queries avoid full table scans.
     * Layman: label the shelves so finding one artist’s songs is fast.
     * Reversal: DROP INDEX idx_tracks_artist / album / album_artist.
     */
    static void ensureBrowseIndexes(SolarDatabase db) {
        if (db == null) return;
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tracks_artist ON tracks(artist COLLATE NOCASE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tracks_album ON tracks(album COLLATE NOCASE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tracks_album_artist ON tracks(album_artist COLLATE NOCASE)");
    }

    /** All cached tracks (may include files removed from disk until next purge). */
    public List<Track> loadAll() {
        List<Track> out = new ArrayList<Track>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query("tracks", null, null, null, null, null, "path ASC");
            while (c.moveToNext()) {
                out.add(rowToTrack(c));
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * 2026-07-20 — Row count for SEGMENTED browse (no full materialization).
     * Layman: how many songs are in the library DB without loading them all.
     */
    public int countTracks() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM tracks", null);
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    /** Absolute paths only, used to preserve indexed rows when one storage root is unreadable. */
    public Set<String> loadPaths() {
        Set<String> out = new HashSet<String>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query("tracks", new String[] { "path" },
                    null, null, null, null, null);
            while (c.moveToNext()) {
                String path = c.getString(0);
                if (path != null && path.length() > 0) out.add(path);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * 2026-07-20 — Page of tracks for SEGMENTED ListView (LIMIT/OFFSET, path order).
     * Layman: load one chunk of the big list so scrolling does not need every song in RAM.
     * Technical: same columns as {@link #loadAll}; empty list when offset past end.
     * Reversal: always {@link #loadAll()}.
     */
    public List<Track> loadRange(int offset, int limit) {
        List<Track> out = new ArrayList<Track>();
        if (limit <= 0) return out;
        if (offset < 0) offset = 0;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT * FROM tracks ORDER BY path ASC LIMIT ? OFFSET ?",
                    new String[] { String.valueOf(limit), String.valueOf(offset) });
            while (c.moveToNext()) {
                out.add(rowToTrack(c));
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * 2026-07-20 — Recently Added page (newest mtime first) for SEGMENTED.
     * Layman: load one shelf of newly added songs without sorting the whole library in RAM.
     * Technical: {@link #SQL_LOAD_BY_MTIME_DESC}; uses idx_tracks_mtime.
     * Reversal: loadAll + in-memory date sort.
     */
    public List<Track> loadRangeByMtimeDesc(int offset, int limit) {
        List<Track> out = new ArrayList<Track>();
        limit = normalizePageLimit(limit);
        if (limit <= 0) return out;
        offset = normalizePageOffset(offset);
        return queryTracks(SQL_LOAD_BY_MTIME_DESC, new String[] {
                String.valueOf(limit), String.valueOf(offset) });
    }

    /**
     * 2026-07-20 — Absolute path → byte size for Has Stems marker matching.
     * Layman: light list of “where is the song / how big” for stem folder lookup.
     * Technical: {@link #SQL_PATH_SIZES}; skips null/empty paths and non-positive sizes.
     * Reversal: walk customLibrary File.length().
     */
    public Map<String, Long> loadPathSizes() {
        Map<String, Long> out = new HashMap<String, Long>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(SQL_PATH_SIZES, null);
            while (c.moveToNext()) {
                String path = c.getString(0);
                long size = c.getLong(1);
                if (path == null || path.length() == 0 || size <= 0L) continue;
                out.put(path, Long.valueOf(size));
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * 2026-07-20 — Clamp OFFSET for SEGMENTED page helpers (JVM-safe).
     * Layman: never ask the DB for a negative starting row.
     */
    static int normalizePageOffset(int offset) {
        return offset < 0 ? 0 : offset;
    }

    /**
     * 2026-07-20 — Clamp LIMIT; &lt;=0 means empty page (caller skips query).
     * Layman: how many songs this chunk may load; zero means “load nothing.”
     */
    static int normalizePageLimit(int limit) {
        return limit <= 0 ? 0 : limit;
    }

    /**
     * 2026-07-20 — Page of songs for one artist (artist or album_artist tag).
     * Layman: open an artist and load only one scroll-window of their songs from the DB.
     * Technical: exact COLLATE NOCASE; multi-artist “feat.” splits still need MainActivity filter later.
     * Reversal: loadAll + ArtistParser.containsArtist in-memory.
     */
    public List<Track> loadTracksByArtist(String artist, int offset, int limit) {
        List<Track> out = new ArrayList<Track>();
        if (artist == null || artist.trim().isEmpty()) return out;
        limit = normalizePageLimit(limit);
        if (limit <= 0) return out;
        offset = normalizePageOffset(offset);
        String a = artist.trim();
        return queryTracks(SQL_LOAD_BY_ARTIST, new String[] {
                a, a, String.valueOf(limit), String.valueOf(offset) });
    }

    /** How many songs credit this artist (for SEGMENTED adapter count). 2026-07-20 */
    public int countTracksByArtist(String artist) {
        if (artist == null || artist.trim().isEmpty()) return 0;
        String a = artist.trim();
        return queryCount(SQL_COUNT_BY_ARTIST, new String[] { a, a });
    }

    /**
     * 2026-07-20 — Page of songs for one album title.
     * Layman: open an album and load a chunk of its tracks without keeping the whole library in RAM.
     * Reversal: loadAll + AlbumNames.equals filter.
     */
    public List<Track> loadTracksByAlbum(String album, int offset, int limit) {
        List<Track> out = new ArrayList<Track>();
        if (album == null || album.trim().isEmpty()) return out;
        limit = normalizePageLimit(limit);
        if (limit <= 0) return out;
        offset = normalizePageOffset(offset);
        String al = album.trim();
        return queryTracks(SQL_LOAD_BY_ALBUM, new String[] {
                al, String.valueOf(limit), String.valueOf(offset) });
    }

    /** Song count for one album title (SEGMENTED adapter). 2026-07-20 */
    public int countTracksByAlbum(String album) {
        if (album == null || album.trim().isEmpty()) return 0;
        return queryCount(SQL_COUNT_BY_ALBUM, new String[] { album.trim() });
    }

    /**
     * 2026-07-20 — Artist + album drill page (ARTIST_ALBUM virtual list).
     * Layman: songs from this album credited to this artist, one page at a time.
     * Reversal: loadAll + containsArtist + AlbumNames.equals.
     */
    public List<Track> loadTracksByArtistAlbum(String artist, String album, int offset, int limit) {
        List<Track> out = new ArrayList<Track>();
        if (artist == null || artist.trim().isEmpty()) return out;
        if (album == null || album.trim().isEmpty()) return out;
        limit = normalizePageLimit(limit);
        if (limit <= 0) return out;
        offset = normalizePageOffset(offset);
        String a = artist.trim();
        String al = album.trim();
        return queryTracks(SQL_LOAD_BY_ARTIST_ALBUM, new String[] {
                a, a, al, String.valueOf(limit), String.valueOf(offset) });
    }

    /** Count for artist+album drill. 2026-07-20 */
    public int countTracksByArtistAlbum(String artist, String album) {
        if (artist == null || artist.trim().isEmpty()) return 0;
        if (album == null || album.trim().isEmpty()) return 0;
        String a = artist.trim();
        String al = album.trim();
        return queryCount(SQL_COUNT_BY_ARTIST_ALBUM, new String[] { a, a, al });
    }

    /**
     * 2026-07-20 — DISTINCT artist names for Tier-0 {@link com.solar.launcher.library.LibraryRamCache}.
     * Layman: list every artist name from the DB without loading every song object.
     * Reversal: walk loadAll / NavRow rebuild.
     */
    public List<String> listDistinctArtists() {
        return queryDistinctNames(SQL_DISTINCT_ARTISTS);
    }

    /**
     * 2026-07-20 — DISTINCT album titles for Tier-0 RAM index (no SongItem walk).
     * Layman: album name list straight from SQLite.
     * Reversal: walk loadAll / NavRow rebuild.
     */
    public List<String> listDistinctAlbums() {
        return queryDistinctNames(SQL_DISTINCT_ALBUMS);
    }

    /**
     * 2026-07-20 — Album titles with more than one track (Flow multi-track filter under SEGMENTED).
     * Layman: only albums that are real multi-song discs, not single files tagged as albums.
     * Reversal: call {@link #listDistinctAlbums()} and ignore the setting under shells.
     */
    public List<String> listDistinctAlbumsMultiTrack() {
        return queryDistinctNames(SQL_DISTINCT_ALBUMS_MULTI_TRACK);
    }

    /**
     * 2026-07-20 — DISTINCT genre names for Tier-0 (SEGMENTED Genre menu).
     * Layman: list every genre label from the DB without loading every song.
     * Reversal: walk customLibrary / LibraryCategoryIndex SongRow rebuild.
     */
    public List<String> listDistinctGenres() {
        return queryDistinctNames(SQL_DISTINCT_GENRES);
    }

    /** Most represented artist tags for local Discover ranking; never leaves the device. */
    public List<String> listTopArtistsForDiscover(int limit) {
        return queryBoundedNames(SQL_TOP_DISCOVER_ARTISTS, limit);
    }

    /** Most represented genre tags for local Discover ranking; never leaves the device. */
    public List<String> listTopGenresForDiscover(int limit) {
        return queryBoundedNames(SQL_TOP_DISCOVER_GENRES, limit);
    }

    /**
     * 2026-07-20 — DISTINCT release years as decimal strings for Tier-0 Year menu.
     * Layman: list years that songs actually have tagged, without a full library walk.
     * Technical: year&gt;0 only (skips −1 unknown / 0 legacy); ORDER BY year ASC.
     * Reversal: walk SongRow year fields in LibraryCategoryIndex.rebuild.
     */
    public List<String> listDistinctYears() {
        return queryDistinctNames(SQL_DISTINCT_YEARS);
    }

    /**
     * 2026-07-20 — Page of songs for one genre (SEGMENTED Genre drill / playlist collect).
     * Layman: open a genre and load one scroll chunk of its songs from the DB.
     * Reversal: loadAll + genre string equals filter.
     */
    public List<Track> loadTracksByGenre(String genre, int offset, int limit) {
        List<Track> out = new ArrayList<Track>();
        if (genre == null || genre.trim().isEmpty()) return out;
        limit = normalizePageLimit(limit);
        if (limit <= 0) return out;
        offset = normalizePageOffset(offset);
        return queryTracks(SQL_LOAD_BY_GENRE, new String[] {
                genre.trim(), String.valueOf(limit), String.valueOf(offset) });
    }

    /** Song count for one genre (SEGMENTED adapter). 2026-07-20 */
    public int countTracksByGenre(String genre) {
        if (genre == null || genre.trim().isEmpty()) return 0;
        return queryCount(SQL_COUNT_BY_GENRE, new String[] { genre.trim() });
    }

    /**
     * 2026-07-20 — Page of songs for one release year (SEGMENTED Year drill).
     * Layman: open a year and load a chunk of tagged songs without the whole library in RAM.
     * Technical: year bind as integer string; year&gt;0 rows only match.
     * Reversal: loadAll + year string equals.
     */
    public List<Track> loadTracksByYear(String yearLabel, int offset, int limit) {
        List<Track> out = new ArrayList<Track>();
        if (yearLabel == null || yearLabel.trim().isEmpty()) return out;
        limit = normalizePageLimit(limit);
        if (limit <= 0) return out;
        offset = normalizePageOffset(offset);
        int year;
        try {
            year = Integer.parseInt(yearLabel.trim());
        } catch (NumberFormatException e) {
            return out;
        }
        if (year <= 0) return out;
        return queryTracks(SQL_LOAD_BY_YEAR, new String[] {
                String.valueOf(year), String.valueOf(limit), String.valueOf(offset) });
    }

    /** Song count for one year (SEGMENTED adapter). 2026-07-20 */
    public int countTracksByYear(String yearLabel) {
        if (yearLabel == null || yearLabel.trim().isEmpty()) return 0;
        int year;
        try {
            year = Integer.parseInt(yearLabel.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
        if (year <= 0) return 0;
        return queryCount(SQL_COUNT_BY_YEAR, new String[] { String.valueOf(year) });
    }

    /** Run a SELECT * page query into Track rows. 2026-07-20 */
    private List<Track> queryTracks(String sql, String[] args) {
        List<Track> out = new ArrayList<Track>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(sql, args);
            while (c.moveToNext()) {
                out.add(rowToTrack(c));
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Run a COUNT(*) helper. 2026-07-20 */
    private int queryCount(String sql, String[] args) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(sql, args);
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    /** Run DISTINCT name SELECT into a list. 2026-07-20 */
    private List<String> queryDistinctNames(String sql) {
        List<String> out = new ArrayList<String>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(sql, null);
            while (c.moveToNext()) {
                String name = c.getString(0);
                if (name != null && !name.trim().isEmpty()) out.add(name.trim());
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private List<String> queryBoundedNames(String sql, int requestedLimit) {
        List<String> out = new ArrayList<String>();
        int limit = Math.max(1, Math.min(50, requestedLimit));
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(sql, new String[] { String.valueOf(limit) });
            while (c.moveToNext()) {
                String name = c.getString(0);
                if (name != null && !name.trim().isEmpty()) out.add(name.trim());
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Lookup by absolute path; null when not cached. */
    public Track get(String path) {
        if (path == null) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query("tracks", null, "path=?", new String[] { path }, null, null, null);
            if (c.moveToFirst()) return rowToTrack(c);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /**
     * Cached track only when it matches the current file stat.
     * Single DB lookup instead of {@link #isFresh(File)} + {@link #get(String)}.
     */
    public Track getFresh(File file) {
        if (file == null || !file.isFile()) return null;
        Track t = get(file.getAbsolutePath());
        if (t == null) return null;
        // 2026-07-06: year=0 means pre-v4 row — force one ID3 re-read to backfill year.
        if (t.year == 0) return null;
        return t.mtime == file.lastModified() && t.size == file.length() ? t : null;
    }

    /**
     * 2026-07-05 — Batch freshness lookup for library scan partition (one query per chunk).
     * Returns map path → fresh Track; missing or stale paths omitted.
     */
    public java.util.HashMap<String, Track> getFreshBatch(java.util.List<File> files) {
        java.util.HashMap<String, Track> out = new java.util.HashMap<String, Track>();
        if (files == null || files.isEmpty()) return out;
        migrateLegacyZeroTrackNumbers();
        migrateLegacyZeroYears();
        final int chunk = 400;
        for (int start = 0; start < files.size(); start += chunk) {
            int end = Math.min(files.size(), start + chunk);
            java.util.ArrayList<String> paths = new java.util.ArrayList<String>(end - start);
            java.util.ArrayList<File> chunkFiles = new java.util.ArrayList<File>(end - start);
            for (int i = start; i < end; i++) {
                File f = files.get(i);
                if (f == null || !f.isFile()) continue;
                paths.add(f.getAbsolutePath());
                chunkFiles.add(f);
            }
            if (paths.isEmpty()) continue;
            StringBuilder sql = new StringBuilder("path IN (");
            for (int i = 0; i < paths.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
            }
            sql.append(')');
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = null;
            try {
                c = db.query("tracks", null, sql.toString(),
                        paths.toArray(new String[paths.size()]), null, null, null);
                while (c.moveToNext()) {
                    Track t = rowToTrack(c);
                    File f = new File(t.path);
                    // 2026-07-06: year=0 = legacy cache before year column — re-tag once.
                    if (f.isFile() && t.year != 0
                            && t.mtime == f.lastModified() && t.size == f.length()) {
                        out.put(t.path, t);
                    }
                }
            } finally {
                if (c != null) c.close();
            }
        }
        return out;
    }

    /** True when cached row matches current file stat — skip MediaMetadataRetriever. */
    public boolean isFresh(File file) {
        migrateLegacyZeroTrackNumbers();
        migrateLegacyZeroYears();
        if (file == null || !file.isFile()) return false;
        Track t = get(file.getAbsolutePath());
        if (t == null) return false;
        // 2026-07-06: year=0 rows need one metadata pass after DB v4 upgrade.
        if (t.year == 0) return false;
        return t.mtime == file.lastModified() && t.size == file.length();
    }

    /** v1/v2 rows used 0 for unknown track — treat as -1 so isFresh does not force full re-ID3. */
    private void migrateLegacyZeroTrackNumbers() {
        if (legacyTrackNumbersMigrated) return;
        legacyTrackNumbersMigrated = true;
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE tracks SET track_number = -1 WHERE track_number = 0");
    }

    private static volatile boolean legacyYearsMigrated;

    /**
     * One-shot: year=0 legacy rows → −1 so isFresh / getFreshBatch stop forcing full walks.
     * Was: year==0 always stale. Reversal: skip this UPDATE.
     * 2026-07-19
     */
    public void migrateLegacyZeroYears() {
        if (legacyYearsMigrated) return;
        legacyYearsMigrated = true;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("UPDATE tracks SET year = " + YEAR_UNKNOWN_SCANNED + " WHERE year = 0");
        } catch (Exception ignored) {}
    }

    /** Remove one track row after user delete — no full-card scan. 2026-07-19 */
    public void deletePath(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("tracks", "path=?", new String[] { path });
        } catch (Exception ignored) {}
    }

    public void upsert(File file, String title, String artist, String album,
            String genre, String albumArtist, String durationMs, int trackNumber) {
        upsert(file, title, artist, album, genre, albumArtist, durationMs, trackNumber, 0);
    }

    public void upsert(File file, String title, String artist, String album,
            String genre, String albumArtist, String durationMs, int trackNumber, int year) {
        if (file == null || !file.isFile()) return;
        if (trackNumber == 0) trackNumber = -1;
        // 2026-07-19 — year 0 after ID3 means unknown, not "legacy needs re-tag".
        // Was: stored 0 → isFresh forever false → full library re-scan every launch.
        // Reversal: allow year=0 persist (breaks fast-path for tagless tracks).
        if (year == 0) year = -1;
        SQLiteDatabase db = getWritableDatabase();
        SQLiteStatement st = db.compileStatement(
                "INSERT OR REPLACE INTO tracks"
                        + " (path,mtime,size,title,artist,album,genre,album_artist,duration_ms,track_number,year)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)");
        try {
            bindUpsert(st, file, title, artist, album, genre, albumArtist, durationMs, trackNumber, year);
            st.executeInsert();
        } finally {
            st.close();
        }
    }

    /**
     * Batch upsert inside a single transaction — much faster than one transaction per row
     * when importing thousands of tracks during a library scan.
     */
    public void upsertBatch(List<Upsert> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        SQLiteStatement st = db.compileStatement(
                "INSERT OR REPLACE INTO tracks"
                        + " (path,mtime,size,title,artist,album,genre,album_artist,duration_ms,track_number,year)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)");
        try {
            for (Upsert t : tracks) {
                bindUpsert(st, t.file, t.title, t.artist, t.album, t.genre,
                        t.albumArtist, t.durationMs, t.trackNumber, t.year);
                st.executeInsert();
                st.clearBindings();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            st.close();
        }
    }

    private static void bindUpsert(SQLiteStatement st, File file, String title, String artist,
            String album, String genre, String albumArtist, String durationMs, int trackNumber, int year) {
        if (trackNumber == 0) trackNumber = -1;
        st.bindString(1, file.getAbsolutePath());
        st.bindLong(2, file.lastModified());
        st.bindLong(3, file.length());
        st.bindString(4, title != null ? title : "");
        st.bindString(5, artist != null ? artist : "");
        st.bindString(6, album != null ? album : "");
        st.bindString(7, genre != null ? genre : "");
        st.bindString(8, albumArtist != null ? albumArtist : "");
        st.bindString(9, durationMs != null ? durationMs : "");
        st.bindLong(10, trackNumber);
        // 2026-07-06: -1 = year read but absent; 0 reserved for legacy not-yet-backfilled rows.
        st.bindLong(11, year > 0 ? year : YEAR_UNKNOWN_SCANNED);
    }

    /** Wipe all cached track rows — Settings reset / cache clear. */
    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("tracks", null, null);
    }

    /** Remove DB rows whose paths were not seen in the latest filesystem walk. */
    public void deleteExcept(Set<String> keepPaths) {
        if (keepPaths == null) return;
        if (keepPaths.isEmpty()) {
            clearAll();
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor c = null;
        try {
            c = db.query("tracks", new String[] { "path" }, null, null, null, null, null);
            List<String> stale = new ArrayList<String>();
            while (c.moveToNext()) {
                String p = c.getString(0);
                if (!keepPaths.contains(p)) stale.add(p);
            }
            c.close();
            c = null;
            // Batch stale paths into a single DELETE per chunk (SQLite max host params is 999).
            final int chunk = 500;
            for (int i = 0; i < stale.size(); i += chunk) {
                int end = Math.min(i + chunk, stale.size());
                String[] args = stale.subList(i, end).toArray(new String[0]);
                db.delete("tracks", "path IN (" + placeholders(args.length) + ")", args);
            }
            db.setTransactionSuccessful();
        } finally {
            if (c != null) c.close();
            db.endTransaction();
        }
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    /** path → durationSec for Soulseek share scan (avoids second MMR pass). */
    public Map<String, Integer> durationSecByPath() {
        Map<String, Integer> out = new HashMap<String, Integer>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query("tracks", new String[] { "path", "duration_ms" }, null, null, null, null, null);
            final int pathCol = c.getColumnIndex("path");
            final int durCol = c.getColumnIndex("duration_ms");
            while (c.moveToNext()) {
                String path = c.getString(pathCol);
                int sec = durationSecFromMs(c.getString(durCol));
                if (sec > 0) {
                    out.put(normPath(path), sec);
                }
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * 2026-07-20 — Parse duration_ms text to milliseconds; 0 when missing/bad.
     * Layman: turn the stored length string into a number of milliseconds.
     */
    public static int parseDurationMs(String durationMs) {
        if (durationMs == null || durationMs.trim().isEmpty()) return 0;
        try {
            int ms = Integer.parseInt(durationMs.trim());
            return ms > 0 ? ms : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parse duration_ms column to whole seconds without full row projection. */
    static int durationSecFromMs(String durationMs) {
        return parseDurationMs(durationMs) / 1000;
    }

    /**
     * 2026-07-20 — mm:ss (or h:mm:ss) for song-row subtitles; empty when unknown.
     * Layman: show how long the track is next to artist/album.
     * Reversal: return "" always (no length on rows).
     */
    public static String formatDurationMmSs(String durationMs) {
        int ms = parseDurationMs(durationMs);
        if (ms <= 0) return "";
        int totalSec = ms / 1000;
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    /**
     * 2026-07-20 — Shortest→longest compare; unknown (0) sorts after known lengths.
     * Layman: put short songs first so similar-length mashup picks sit together.
     */
    public static int compareDurationAscending(String a, String b) {
        int da = parseDurationMs(a);
        int db = parseDurationMs(b);
        if (da <= 0 && db <= 0) return 0;
        if (da <= 0) return 1;
        if (db <= 0) return -1;
        return Integer.compare(da, db);
    }

    static String normPath(String path) {
        if (path == null) return "";
        return path.toLowerCase(Locale.US);
    }

    public static HashSet<String> newKeepSet() {
        return new HashSet<String>();
    }

    private static Track rowToTrack(Cursor c) {
        int trackNumber = 0;
        int trackNumberIndex = c.getColumnIndex("track_number");
        if (trackNumberIndex != -1) {
            trackNumber = c.getInt(trackNumberIndex);
        }
        int year = 0;
        int yearIndex = c.getColumnIndex("year");
        if (yearIndex != -1) year = c.getInt(yearIndex);
        return new Track(
                c.getString(c.getColumnIndex("path")),
                c.getLong(c.getColumnIndex("mtime")),
                c.getLong(c.getColumnIndex("size")),
                c.getString(c.getColumnIndex("title")),
                c.getString(c.getColumnIndex("artist")),
                c.getString(c.getColumnIndex("album")),
                c.getString(c.getColumnIndex("genre")),
                c.getString(c.getColumnIndex("album_artist")),
                c.getString(c.getColumnIndex("duration_ms")),
                trackNumber,
                year);
    }

    // --- Favorites (JJ parity) ---

    /**
     * 2026-07-20 — Hearted tracks joined to library rows (title-sorted).
     * Layman: open Favorites and get real song rows even when the big library is not in RAM.
     * Technical: INNER JOIN favorite_paths ↔ tracks. Reversal: walk customLibrary + favoritePaths set.
     */
    public List<Track> loadFavoriteTracks() {
        return queryTracks(SQL_LOAD_FAVORITES, null);
    }

    /** How many hearted songs still exist in the library table. 2026-07-20 */
    public int countFavoriteTracks() {
        return queryCount(SQL_COUNT_FAVORITES, null);
    }

    /** All favorited track paths. */
    public Set<String> loadFavoritePaths() {
        Set<String> out = new HashSet<String>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query("favorite_paths", new String[] { "path" }, null, null, null, null, null);
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public boolean isFavorite(String path) {
        if (path == null) return false;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query("favorite_paths", new String[] { "path" }, "path=?",
                    new String[] { path }, null, null, null);
            return c.moveToFirst();
        } finally {
            if (c != null) c.close();
        }
    }

    public void setFavorite(String path, boolean on) {
        if (path == null) return;
        SQLiteDatabase db = getWritableDatabase();
        if (on) {
            db.execSQL("INSERT OR REPLACE INTO favorite_paths (path) VALUES (?)",
                    new Object[] { path });
        } else {
            db.delete("favorite_paths", "path=?", new String[] { path });
        }
    }

    /** Remove favorites whose files no longer exist. */
    public void pruneFavorites(Set<String> existingPaths) {
        Set<String> favs = loadFavoritePaths();
        if (favs.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        for (String p : favs) {
            if (existingPaths == null || !existingPaths.contains(p)) {
                db.delete("favorite_paths", "path=?", new String[] { p });
            }
        }
    }

    // --- Audiobook bookmarks ---

    public static final class AudiobookBookmark {
        public final String path;
        public final int positionMs;
        public final int chapterIndex;

        public AudiobookBookmark(String path, int positionMs, int chapterIndex) {
            this.path = path;
            this.positionMs = positionMs;
            this.chapterIndex = chapterIndex;
        }
    }

    public void saveAudiobookBookmark(String path, int positionMs, int chapterIndex) {
        if (path == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR REPLACE INTO audiobook_bookmarks"
                        + " (path,position_ms,chapter_index,updated_at) VALUES (?,?,?,?)",
                new Object[] { path, positionMs, chapterIndex, System.currentTimeMillis() });
    }

    public AudiobookBookmark loadAudiobookBookmark(String path) {
        if (path == null) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query("audiobook_bookmarks", null, "path=?", new String[] { path },
                    null, null, null);
            if (c.moveToFirst()) {
                return new AudiobookBookmark(path,
                        c.getInt(c.getColumnIndex("position_ms")),
                        c.getInt(c.getColumnIndex("chapter_index")));
            }
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public Map<String, AudiobookBookmark> loadAllAudiobookBookmarks() {
        Map<String, AudiobookBookmark> out = new HashMap<String, AudiobookBookmark>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query("audiobook_bookmarks", null, null, null, null, null, null);
            while (c.moveToNext()) {
                String path = c.getString(c.getColumnIndex("path"));
                out.put(path, new AudiobookBookmark(path,
                        c.getInt(c.getColumnIndex("position_ms")),
                        c.getInt(c.getColumnIndex("chapter_index"))));
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }
}
