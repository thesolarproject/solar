package com.solar.launcher.db.android;

import android.database.Cursor;

import com.solar.launcher.db.SolarCursor;

/** Android {@link Cursor} wrapper implementing the Solar cursor contract. */
public final class AndroidSolarCursor implements SolarCursor {

    private final Cursor cursor;

    public AndroidSolarCursor(Cursor cursor) {
        this.cursor = cursor;
    }

    @Override
    public boolean moveToFirst() {
        return cursor.moveToFirst();
    }

    @Override
    public boolean moveToNext() {
        return cursor.moveToNext();
    }

    @Override
    public long getLong(int columnIndex) {
        return cursor.getLong(columnIndex);
    }

    @Override
    public int getInt(int columnIndex) {
        return cursor.getInt(columnIndex);
    }

    @Override
    public String getString(int columnIndex) {
        return cursor.getString(columnIndex);
    }

    @Override
    public boolean isNull(int columnIndex) {
        return cursor.isNull(columnIndex);
    }

    @Override
    public int getColumnIndex(String columnName) {
        return cursor.getColumnIndex(columnName);
    }

    @Override
    public void close() {
        cursor.close();
    }

    @Override
    public boolean isClosed() {
        return cursor.isClosed();
    }
}
