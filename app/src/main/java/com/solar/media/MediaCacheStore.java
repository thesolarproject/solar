package com.solar.media;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.solar.launcher.db.SolarDatabase;
import com.solar.launcher.db.SolarDbHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-30: Generic SQLite cache for media-server artist indexes.
 * <p>
 * Consolidates the former NavidromeCacheStore / JellyfinCacheStore / PlexCacheStore
 * triplicate (identical table structure, CRUD, and singleton patterns) into a single
 * parameterized base.  Subclasses only supply a table name, factory, and their own
 * server-specific {@code parseArtistsJson} static method.
 *
 * @param <T> the concrete artist row type (must extend {@link CachedArtist})
 */
public abstract class MediaCacheStore<T extends CachedArtist> extends SolarDbHelper {

    protected MediaCacheStore(Context ctx, String dbName, int version) {
        super(ctx.getApplicationContext(), dbName, version, true);
    }

    /** Subclass provides the SQLite table name (e.g. "navidrome_artists"). */
    protected abstract String tableName();

    /** Subclass provides a fresh artist instance (e.g. {@code new MediaArtist()}). */
    protected abstract T newInst();

    // ── DB lifecycle ──────────────────────────────────────────────

    @Override
    public void onCreate(SolarDatabase db) {
        db.execSQL("CREATE TABLE " + tableName() + " ("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT NOT NULL,"
                + "album_count INTEGER NOT NULL DEFAULT 0,"
                + "cover_art TEXT,"
                + "index_letter TEXT)");
    }

    @Override
    public void onUpgrade(SolarDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + tableName());
        onCreate(db);
    }

    // ── CRUD ──────────────────────────────────────────────────────

    public List<T> loadArtists() {
        List<T> out = new ArrayList<T>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(tableName(), null, null, null, null, null,
                    "name COLLATE NOCASE ASC");
            while (c.moveToNext()) {
                T a = newInst();
                a.id = c.getString(0);
                a.name = c.getString(1);
                a.albumCount = c.getInt(2);
                a.coverArtId = c.getString(3);
                a.indexLetter = c.getString(4);
                out.add(a);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public void saveArtists(List<T> artists) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(tableName(), null, null);
            if (artists != null) {
                for (T a : artists) {
                    if (a == null) continue;
                    db.execSQL("INSERT INTO " + tableName()
                                    + " (id,name,album_count,cover_art,index_letter)"
                                    + " VALUES (?,?,?,?,?)",
                            new Object[]{a.id, a.name, a.albumCount,
                                    a.coverArtId != null ? a.coverArtId : "",
                                    a.indexLetter != null ? a.indexLetter : "#"});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void clearArtists() {
        getWritableDatabase().delete(tableName(), null, null);
    }
}
