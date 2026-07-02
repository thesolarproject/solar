package com.solar.launcher.db;

/**
 * Cursor abstraction matching the small surface actually used by Solar stores.
 * Keeps the pilot store ({@link com.solar.launcher.radio.fm.FmPresetStore}) portable
 * so a libSQL-backed engine can be plugged in later.
 */
public interface SolarCursor {

    boolean moveToFirst();

    boolean moveToNext();

    long getLong(int columnIndex);

    int getInt(int columnIndex);

    String getString(int columnIndex);

    boolean isNull(int columnIndex);

    int getColumnIndex(String columnName);

    void close();

    boolean isClosed();
}
