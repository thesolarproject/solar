package com.solar.launcher.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;

import com.solar.launcher.db.android.AndroidSolarDatabase;

/**
 * Base helper for Solar SQLite caches. Enables WAL so reads can run in parallel
 * via Android's connection pool, and applies sane pragmas for durability vs speed.
 *
 * <p>Subclasses get a modernized SQLite setup without changing call sites.
 * The helper intentionally stays compatible with {@code minSdk 17}; a future
 * libSQL engine can be swapped in behind {@link SolarDatabase} pilots.</p>
 */
public abstract class SolarDbHelper extends SQLiteOpenHelper {

    private final boolean walEnabled;

    protected SolarDbHelper(Context context, String name, int version) {
        this(context, name, version, true);
    }

    protected SolarDbHelper(Context context, String name, int version, boolean walEnabled) {
        super(context.getApplicationContext(), name, null, version);
        this.walEnabled = walEnabled;
        // setWriteAheadLoggingEnabled internally calls enableWriteAheadLogging() which on
        // Android 4.x executes PRAGMA journal_mode=WAL through a path that Android rejects
        // for value-returning statements (SQLiteException code 0). Gate it to API 18+ where
        // the implementation is reliable; API 17 sets WAL via pragma() in onOpen() instead.
        if (walEnabled && Build.VERSION.SDK_INT >= 18) {
            setWriteAheadLoggingEnabled(true);
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!walEnabled) return;
        // Use rawQuery+moveToFirst+close for all PRAGMA calls. On Android 4.x, execSQL()
        // routes through executeForChangedRowCount() which rejects any statement returning
        // a result row (journal_mode, journal_size_limit, wal_autocheckpoint all do) with
        // SQLiteException code 0. rawQuery accepts any statement type; moveToFirst() forces
        // execution since rawQuery is lazy.
        if (Build.VERSION.SDK_INT < 18) {
            // setWriteAheadLoggingEnabled not called in constructor on API 17; set WAL here.
            pragma(db, "PRAGMA journal_mode=WAL");
        }
        pragma(db, "PRAGMA synchronous=NORMAL");
        pragma(db, "PRAGMA journal_size_limit=1048576");
        pragma(db, "PRAGMA wal_autocheckpoint=100");
    }

    private static void pragma(SQLiteDatabase db, String sql) {
        Cursor c = db.rawQuery(sql, null);
        try { c.moveToFirst(); } finally { c.close(); }
    }

    /** Borrow a readable database handle wrapped for portability. */
    public SolarDatabase openReadable() {
        return new AndroidSolarDatabase(getReadableDatabase());
    }

    /** Borrow a writable database handle wrapped for portability. */
    public SolarDatabase openWritable() {
        return new AndroidSolarDatabase(getWritableDatabase());
    }

    @Override
    public final void onCreate(SQLiteDatabase db) {
        onCreate(new AndroidSolarDatabase(db));
    }

    /** Create tables using the portable {@link SolarDatabase} API. */
    public abstract void onCreate(SolarDatabase db);

    @Override
    public final void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(new AndroidSolarDatabase(db), oldVersion, newVersion);
    }

    /** Upgrade tables using the portable {@link SolarDatabase} API. */
    public abstract void onUpgrade(SolarDatabase db, int oldVersion, int newVersion);
}
