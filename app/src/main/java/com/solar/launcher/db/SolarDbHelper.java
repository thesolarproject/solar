package com.solar.launcher.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

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
        if (walEnabled) {
            setWriteAheadLoggingEnabled(true);
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!walEnabled) return;
        // WAL already allows concurrent readers; NORMAL sync is durable enough
        // for a local media cache while keeping writes from fsync-blocking.
        db.execSQL("PRAGMA synchronous=NORMAL");
        // Keep WAL files from growing without bound on the Y1's limited storage.
        db.execSQL("PRAGMA journal_size_limit=1048576");
        // Checkpoint after every 100 pages — balances read latency vs write throughput.
        db.execSQL("PRAGMA wal_autocheckpoint=100");
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
