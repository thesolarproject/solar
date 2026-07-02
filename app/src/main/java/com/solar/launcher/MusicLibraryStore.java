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
    private static final int DB_VERSION = 3;

    private static MusicLibraryStore instance;
    private boolean legacyTrackNumbersMigrated;

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

        Track(String path, long mtime, long size, String title, String artist, String album,
                String genre, String albumArtist, String durationMs, int trackNumber) {
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
        }

        /** Duration in whole seconds for Soulseek share attributes; 0 when unknown. */
        public int durationSec() {
            if (durationMs == null || durationMs.trim().isEmpty()) return 0;
            try {
                int ms = Integer.parseInt(durationMs.trim());
                return ms > 0 ? ms / 1000 : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
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
                + "track_number INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_tracks_mtime ON tracks(mtime)");
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

    /** True when cached row matches current file stat — skip MediaMetadataRetriever. */
    public boolean isFresh(File file) {
        migrateLegacyZeroTrackNumbers();
        if (file == null || !file.isFile()) return false;
        Track t = get(file.getAbsolutePath());
        if (t == null) return false;
        return t.mtime == file.lastModified() && t.size == file.length();
    }

    /** v1/v2 rows used 0 for unknown track — treat as -1 so isFresh does not force full re-ID3. */
    private void migrateLegacyZeroTrackNumbers() {
        if (legacyTrackNumbersMigrated) return;
        legacyTrackNumbersMigrated = true;
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE tracks SET track_number = -1 WHERE track_number = 0");
    }

    public void upsert(File file, String title, String artist, String album,
            String genre, String albumArtist, String durationMs, int trackNumber) {
        if (file == null || !file.isFile()) return;
        if (trackNumber == 0) trackNumber = -1;
        SQLiteDatabase db = getWritableDatabase();
        SQLiteStatement st = db.compileStatement(
                "INSERT OR REPLACE INTO tracks"
                        + " (path,mtime,size,title,artist,album,genre,album_artist,duration_ms,track_number)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)");
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
        st.executeInsert();
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

    /** Parse duration_ms column without full row projection. */
    static int durationSecFromMs(String durationMs) {
        if (durationMs == null || durationMs.trim().isEmpty()) return 0;
        try {
            int ms = Integer.parseInt(durationMs.trim());
            return ms > 0 ? ms / 1000 : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
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
                trackNumber);
    }
}
