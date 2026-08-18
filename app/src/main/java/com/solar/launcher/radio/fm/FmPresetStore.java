package com.solar.launcher.radio.fm;

import android.content.Context;

import com.solar.launcher.PlayQueue;
import com.solar.launcher.radio.FmBandPlan;
import com.solar.launcher.db.SolarCursor;
import com.solar.launcher.db.SolarDatabase;
import com.solar.launcher.db.SolarDbHelper;

import java.util.ArrayList;
import java.util.List;

/** Saved FM stations — freq in kHz, label, user sort order (play-queue mirror). 2026-07-06 */
public final class FmPresetStore extends SolarDbHelper {
  private static final String DB = "fm_presets.db";
  private static final int VERSION = 2;

  private static FmPresetStore instance;

  public static final class Preset {
    public final long id;
    public final int freqKhz;
    public final String label;
    public final int sortOrder;

    public Preset(long id, int freqKhz, String label, int sortOrder) {
      this.id = id;
      this.freqKhz = freqKhz;
      this.label = label != null ? label : "";
      this.sortOrder = sortOrder;
    }

    public Preset(long id, int freqKhz, String label) {
      this(id, freqKhz, label, 0);
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
            + "sort_order INTEGER NOT NULL DEFAULT 0,"
            + "UNIQUE(freq_khz))");
  }

  @Override
  public void onUpgrade(SolarDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE presets ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0");
      SolarCursor c = db.rawQuery("SELECT id FROM presets ORDER BY freq_khz ASC", null);
      db.beginTransaction();
      try {
        int order = 0;
        while (c.moveToNext()) {
          db.execSQL(
              "UPDATE presets SET sort_order=? WHERE id=?",
              new Object[] {order++, c.getLong(0)});
        }
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
        c.close();
      }
      return;
    }
    db.execSQL("DROP TABLE IF EXISTS presets");
    onCreate(db);
  }

  public List<Preset> listAll() {
    SolarDatabase db = openReadable();
    SolarCursor c =
        db.query(
            "presets",
            new String[] {"id", "freq_khz", "label", "sort_order"},
            null,
            null,
            "sort_order ASC, freq_khz ASC");
    List<Preset> out = new ArrayList<Preset>();
    try {
      while (c.moveToNext()) {
        out.add(
            new Preset(
                c.getLong(0),
                c.getInt(1),
                c.isNull(2) ? "" : c.getString(2),
                c.getInt(3)));
      }
    } finally {
      c.close();
    }
    return out;
  }

  /** Map saved stations to unified play-queue rows (FM_STATION). */
  public List<PlayQueue.QueueItem> toQueueItems() {
    List<Preset> presets = listAll();
    List<PlayQueue.QueueItem> out = new ArrayList<PlayQueue.QueueItem>(presets.size());
    for (Preset p : presets) {
      String label = p.label;
      if (label == null || label.isEmpty()) {
        label = FmBandPlan.khzToFraction(p.freqKhz, FmBandPlan.fromRegionCode("US"));
      }
      out.add(PlayQueue.QueueItem.fmStation(p.freqKhz, label));
    }
    return out;
  }

  public synchronized long upsert(int freqKhz, String label) {
    SolarDatabase db = openWritable();
    Preset existing = findByFreq(freqKhz);
    if (existing != null) {
      db.execSQL(
          "UPDATE presets SET label=? WHERE freq_khz=?",
          new Object[] {label != null ? label : "", freqKhz});
      return existing.id;
    }
    int nextOrder = maxSortOrder(db) + 1;
    db.execSQL(
        "INSERT INTO presets (freq_khz, label, sort_order) VALUES (?, ?, ?)",
        new Object[] {freqKhz, label != null ? label : "", nextOrder});
    SolarCursor c =
        db.rawQuery("SELECT id FROM presets WHERE freq_khz=?", new String[] {
          Integer.toString(freqKhz)
        });
    try {
      return c.moveToFirst() ? c.getLong(0) : -1L;
    } finally {
      c.close();
    }
  }

  public synchronized boolean delete(int freqKhz) {
    SolarDatabase db = openWritable();
    boolean ok =
        db.delete("presets", "freq_khz=?", new String[] {Integer.toString(freqKhz)}) > 0;
    if (ok) normalizeSortOrder(db);
    return ok;
  }

  /** 2026-07-06 — Scan replaces list; sort_order follows scan discovery order. */
  public synchronized void replaceAll(List<Preset> presets) {
    SolarDatabase db = openWritable();
    db.beginTransaction();
    try {
      db.execSQL("DELETE FROM presets");
      if (presets != null) {
        int order = 0;
        for (Preset p : presets) {
          db.execSQL(
              "INSERT INTO presets (freq_khz, label, sort_order) VALUES (?, ?, ?)",
              new Object[] {p.freqKhz, p.label != null ? p.label : "", order++});
        }
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  /** Reorder list indices — mirrors play-queue move. */
  public synchronized void reorder(int fromIndex, int toIndex) {
    List<Preset> presets = listAll();
    if (fromIndex < 0 || toIndex < 0 || fromIndex >= presets.size() || toIndex >= presets.size()) {
      return;
    }
    if (fromIndex == toIndex) return;
    Preset moved = presets.remove(fromIndex);
    presets.add(toIndex, moved);
    SolarDatabase db = openWritable();
    db.beginTransaction();
    try {
      for (int i = 0; i < presets.size(); i++) {
        db.execSQL(
            "UPDATE presets SET sort_order=? WHERE id=?",
            new Object[] {i, presets.get(i).id});
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public synchronized boolean containsFreq(int freqKhz) {
    return findByFreq(freqKhz) != null;
  }

  public Preset findByFreq(int freqKhz) {
    SolarDatabase db = openReadable();
    SolarCursor c =
        db.query(
            "presets",
            new String[] {"id", "freq_khz", "label", "sort_order"},
            "freq_khz=?",
            new String[] {Integer.toString(freqKhz)},
            null);
    try {
      if (!c.moveToFirst()) return null;
      return new Preset(
          c.getLong(0), c.getInt(1), c.isNull(2) ? "" : c.getString(2), c.getInt(3));
    } finally {
      c.close();
    }
  }

  private static int maxSortOrder(SolarDatabase db) {
    SolarCursor c = db.rawQuery("SELECT MAX(sort_order) FROM presets", null);
    try {
      return c.moveToFirst() && !c.isNull(0) ? c.getInt(0) : -1;
    } finally {
      c.close();
    }
  }

  private static void normalizeSortOrder(SolarDatabase db) {
    SolarCursor c = db.rawQuery("SELECT id FROM presets ORDER BY sort_order ASC, freq_khz ASC", null);
    db.beginTransaction();
    try {
      int order = 0;
      while (c.moveToNext()) {
        db.execSQL("UPDATE presets SET sort_order=? WHERE id=?", new Object[] {order++, c.getLong(0)});
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
      c.close();
    }
  }
}
