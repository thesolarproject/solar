package com.solar.launcher.db;

/**
 * Database handle abstraction. Mirrors the subset of {@link android.database.sqlite.SQLiteDatabase}
 * actually used by Solar stores, creating an insertion point for a future libSQL engine without
 * rewriting every call site.
 *
 * <p>Instances are short-lived handles borrowed from a {@link SolarDbHelper}; closing them
 * does not close the underlying database connection.</p>
 */
public interface SolarDatabase {

    /** Convenience query with column projection and sort order. */
    SolarCursor query(String table, String[] columns, String selection,
            String[] selectionArgs, String sortOrder);

    /** Raw SQL query. */
    SolarCursor rawQuery(String sql, String[] selectionArgs);

    /** Execute a raw SQL statement. */
    void execSQL(String sql);

    /** Execute a raw SQL statement with bound arguments. */
    void execSQL(String sql, Object[] bindArgs);

    /** Delete rows matching the where clause. */
    int delete(String table, String whereClause, String[] whereArgs);

    /** Begin a transaction. */
    void beginTransaction();

    /** Mark the current transaction successful. */
    void setTransactionSuccessful();

    /** End the current transaction. */
    void endTransaction();

    /** True if currently inside a transaction. */
    boolean inTransaction();
}
