package com.solar.launcher.radio.fm;

import android.content.Context;

import com.solar.launcher.db.SolarCursor;
import com.solar.launcher.db.SolarDatabase;
import com.solar.launcher.db.SolarDbHelper;

import java.util.ArrayList;
import java.util.List;

/** Saved FM stations — freq in kHz + optional label. */
public final class FmPresetStore extends SolarDbHelper {
  private static final String DB = "fm_presets.db";
  private static final int VERSION = 1;

  private static FmPresetStore instance;

  public static final class Preset {
    public final long id;
    public final int freqKhz;
    public final String label;

    public Preset(long id, int freqKhz, String label) {
      this.id = id;
      this.freqKhz = freqKhz;
      this.label = label != null ? label : "";
    }
  }

  private FmPresetStore(Context ctx) {
    super(ctx.getApplicationContext(), DB, VERSION);
  }

  public static synchronized FmPresetStore getInstance(Context ctx) {
    if (instance == null) {
      instance = new FmPresetStore(ctx.getApplicationContext());
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
        "CREATE TABLE presets ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "freq_khz INTEGER NOT NULL,"
            + "label TEXT NOT NULL DEFAULT '',"
            + "UNIQUE(freq_khz))");
  }

  @Override
  public void onUpgrade(SolarDatabase db, int oldVersion, int newVersion) {
    db.execSQL("DROP TABLE IF EXISTS presets");
    onCreate(db);
  }

  public List<Preset> listAll() {
    SolarDatabase db = openReadable();
    SolarCursor c =
        db.query("presets", new String[] {"id", "freq_khz", "label"}, null, null,
            "freq_khz ASC");
    List<Preset> out = new ArrayList<Preset>();
    try {
      while (c.moveToNext()) {
        out.add(
            new Preset(c.getLong(0), c.getInt(1), c.isNull(2) ? "" : c.getString(2)));
      }
    } finally {
      c.close();
    }
    return out;
  }

  public synchronized long upsert(int freqKhz, String label) {
    SolarDatabase db = openWritable();
    db.execSQL(
        "INSERT OR REPLACE INTO presets (id, freq_khz, label) VALUES ("
            + "(SELECT id FROM presets WHERE freq_khz=?), ?, ?)",
        new Object[] {freqKhz, freqKhz, label != null ? label : ""});
    SolarCursor c = db.rawQuery("SELECT id FROM presets WHERE freq_khz=?", new String[] {
      Integer.toString(freqKhz)
    });
    try {
      return c.moveToFirst() ? c.getLong(0) : -1L;
    } finally {
      c.close();
    }
  }

  public synchronized boolean delete(int freqKhz) {
    return openWritable().delete("presets", "freq_khz=?", new String[] {
          Integer.toString(freqKhz)
        })
        > 0;
  }

  public Preset findByFreq(int freqKhz) {
    SolarDatabase db = openReadable();
    SolarCursor c =
        db.query("presets", new String[] {"id", "freq_khz", "label"}, "freq_khz=?",
            new String[] {Integer.toString(freqKhz)}, null);
    try {
      if (!c.moveToFirst()) return null;
      return new Preset(c.getLong(0), c.getInt(1), c.isNull(2) ? "" : c.getString(2));
    } finally {
      c.close();
    }
  }
}
