package com.solar.launcher.db.android;

import android.database.sqlite.SQLiteDatabase;

import com.solar.launcher.db.SolarCursor;
import com.solar.launcher.db.SolarDatabase;

/** Android {@link SQLiteDatabase} wrapper implementing the Solar database contract. */
public final class AndroidSolarDatabase implements SolarDatabase {

    private final SQLiteDatabase db;

    public AndroidSolarDatabase(SQLiteDatabase db) {
        this.db = db;
    }

    @Override
    public SolarCursor query(String table, String[] columns, String selection,
            String[] selectionArgs, String sortOrder) {
        return new AndroidSolarCursor(db.query(table, columns, selection,
                selectionArgs, null, null, sortOrder));
    }

    @Override
    public SolarCursor rawQuery(String sql, String[] selectionArgs) {
        return new AndroidSolarCursor(db.rawQuery(sql, selectionArgs));
    }

    @Override
    public void execSQL(String sql) {
        db.execSQL(sql);
    }

    @Override
    public void execSQL(String sql, Object[] bindArgs) {
        db.execSQL(sql, bindArgs);
    }

    @Override
    public int delete(String table, String whereClause, String[] whereArgs) {
        return db.delete(table, whereClause, whereArgs);
    }

    @Override
    public void beginTransaction() {
        db.beginTransaction();
    }

    @Override
    public void setTransactionSuccessful() {
        db.setTransactionSuccessful();
    }

    @Override
    public void endTransaction() {
        db.endTransaction();
    }

    @Override
    public boolean inTransaction() {
        return db.inTransaction();
    }
}
