package com.solar.launcher.radio.net;

import android.content.Context;

import com.solar.launcher.db.SolarCursor;
import com.solar.launcher.db.SolarDatabase;
import com.solar.launcher.db.SolarDbHelper;

import java.util.ArrayList;
import java.util.List;

/** Saved internet radio stations keyed by Radio Browser stationuuid. */
public final class InternetRadioFavorites extends SolarDbHelper {
  private static final String DB = "internet_radio_favorites.db";
  private static final int VERSION = 1;

  private static InternetRadioFavorites instance;

  public static final class Favorite {
    public final long id;
    public final String stationuuid;
    public final String name;
    public final String url;
    public final String countrycode;

    public Favorite(long id, String stationuuid, String name, String url, String countrycode) {
      this.id = id;
      this.stationuuid = stationuuid;
      this.name = name;
      this.url = url;
      this.countrycode = countrycode;
    }
  }

  private InternetRadioFavorites(Context ctx) {
    super(ctx.getApplicationContext(), DB, VERSION);
  }

  public static synchronized InternetRadioFavorites getInstance(Context ctx) {
    if (instance == null) {
      instance = new InternetRadioFavorites(ctx.getApplicationContext());
    }
    return instance;
  }

  static void resetForTest() {
    if (instance != null) {
      instance.close();
      instance = null;
    }
  }

  @Override
  public void onCreate(SolarDatabase db) {
    db.execSQL(
        "CREATE TABLE favorites ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "stationuuid TEXT NOT NULL UNIQUE,"
            + "name TEXT NOT NULL,"
            + "url TEXT NOT NULL,"
            + "countrycode TEXT NOT NULL DEFAULT '',"
            + "added_at INTEGER NOT NULL DEFAULT 0)");
  }

  @Override
  public void onUpgrade(SolarDatabase db, int oldVersion, int newVersion) {
    db.execSQL("DROP TABLE IF EXISTS favorites");
    onCreate(db);
  }

  public List<Favorite> listAll() {
    SolarDatabase db = openReadable();
    SolarCursor c =
        db.query("favorites",
            new String[] {"id", "stationuuid", "name", "url", "countrycode"},
            null, null, "added_at DESC");
    List<Favorite> out = new ArrayList<Favorite>();
    try {
      while (c.moveToNext()) {
        out.add(
            new Favorite(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                c.isNull(4) ? "" : c.getString(4)));
      }
    } finally {
      c.close();
    }
    return out;
  }

  public synchronized long add(RadioBrowserClient.Station station) {
    if (station == null) return -1L;
    return add(station.stationuuid, station.name, station.urlResolved, station.countrycode);
  }

  public synchronized long add(String stationuuid, String name, String url, String countrycode) {
    if (stationuuid == null || stationuuid.trim().isEmpty()) return -1L;
    SolarDatabase db = openWritable();
    db.execSQL(
        "INSERT OR REPLACE INTO favorites (id, stationuuid, name, url, countrycode, added_at) VALUES ("
            + "(SELECT id FROM favorites WHERE stationuuid=?), ?, ?, ?, ?, ?)",
        new Object[] {
          stationuuid.trim(),
          stationuuid.trim(),
          name != null ? name : "",
          url != null ? url : "",
          countrycode != null ? countrycode : "",
          System.currentTimeMillis()
        });
    SolarCursor c =
        db.rawQuery("SELECT id FROM favorites WHERE stationuuid=?", new String[] {stationuuid.trim()});
    try {
      return c.moveToFirst() ? c.getLong(0) : -1L;
    } finally {
      c.close();
    }
  }

  public synchronized boolean remove(String stationuuid) {
    if (stationuuid == null || stationuuid.trim().isEmpty()) return false;
    return openWritable().delete("favorites", "stationuuid=?", new String[] {
          stationuuid.trim()
        })
        > 0;
  }

  public boolean isFavorite(String stationuuid) {
    if (stationuuid == null || stationuuid.trim().isEmpty()) return false;
    SolarCursor c =
        openReadable()
            .rawQuery("SELECT 1 FROM favorites WHERE stationuuid=? LIMIT 1",
                new String[] {stationuuid.trim()});
    try {
      return c.moveToFirst();
    } finally {
      c.close();
    }
  }
}
