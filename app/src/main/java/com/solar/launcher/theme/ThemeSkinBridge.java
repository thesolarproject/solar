package com.solar.launcher.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import com.solar.launcher.A5NavigationMode;
import com.solar.launcher.A5PortraitChrome;
import com.solar.launcher.DeviceFeatures;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * 2026-07-05 — Publishes theme skin sidecars for Xposed ActivitySkin / SystemUiSkin.
 * Layman: copies wallpaper, row highlight art, and colors so Settings and other stock apps
 * match the user's Solar theme. Reversal: delete sidecars or turn off the Device pref.
 */
public final class ThemeSkinBridge {

  /** SharedPreferences key — same store as {@link ThemeManager} / MainActivity settings. */
  public static final String PREF_PAINT_SYSTEM_APPS = "paint_system_apps_theme";

  /** JSON contract version read by {@code ThemeSkinSidecar} in SolarThemeFont. */
  public static final int SIDECAR_VERSION = 1;

  public static final String SIDECAR_JSON = "theme-skin.json";
  public static final String SIDECAR_WALLPAPER = "theme-skin-wallpaper.png";
  public static final String SIDECAR_SELECTION = "theme-skin-selection.png";

  /** Y1/Y2 viewport — pre-scale at publish so hooked apps decode one small bitmap. */
  public static final int SKIN_WALLPAPER_W = 480;
  public static final int SKIN_WALLPAPER_H = 360;
  /** Row selection strip width — height follows theme art aspect. */
  public static final int SKIN_SELECTION_W = 480;
  public static final int SKIN_SELECTION_H = 48;

  /**
   * 2026-07-11 / 2026-07-14 — Effective skin size.
   * A5: portrait 240×320 / landscape 320×240. Y1/Y2 portrait experiment: live tall buffer.
   * Was: non-A5 always 480×360. Reversal: drop usePortraitChrome branch.
   */
  public static int skinWallpaperW(Context ctx) {
    if (ctx != null && DeviceFeatures.isA5()) {
      return A5NavigationMode.isPortrait(ctx)
              ? A5NavigationMode.portraitWidthPx() : A5NavigationMode.landscapeWidthPx();
    }
    // Y1/Y2 upright experiment — publish tall skin so hooked apps match.
    if (ctx != null && A5PortraitChrome.usePortraitChrome(ctx)) {
      return Math.max(1, ctx.getResources().getDisplayMetrics().widthPixels);
    }
    return SKIN_WALLPAPER_W;
  }

  public static int skinWallpaperH(Context ctx) {
    if (ctx != null && DeviceFeatures.isA5()) {
      return A5NavigationMode.isPortrait(ctx)
              ? A5NavigationMode.portraitHeightPx() : A5NavigationMode.landscapeHeightPx();
    }
    if (ctx != null && A5PortraitChrome.usePortraitChrome(ctx)) {
      return Math.max(1, ctx.getResources().getDisplayMetrics().heightPixels);
    }
    return SKIN_WALLPAPER_H;
  }

  private ThemeSkinBridge() {}

  /** User pref — default on; off deletes skin PNGs and writes enabled:false JSON. */
  public static boolean isEnabled(Context ctx) {
    if (ctx == null) return true;
    SharedPreferences prefs = ctx.getApplicationContext()
        .getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
    return prefs.getBoolean(PREF_PAINT_SYSTEM_APPS, true);
  }

  /** Persist toggle and republish sidecars on next {@link ThemeManager#cacheActiveTheme}. */
  public static void setEnabled(Context ctx, boolean enabled) {
    if (ctx == null) return;
    ctx.getApplicationContext()
        .getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_PAINT_SYSTEM_APPS, enabled)
        .commit();
  }

  /** Write skin JSON + optional PNG sidecars, or clear all when disabled / no theme. */
  public static void publish(Context ctx) {
    if (ctx == null) return;
    if (!isEnabled(ctx)) {
      writeStamp(ctx, null);
      clearSidecars(ctx);
      publishDisabledJson(ctx);
      return;
    }
    try {
      JSONObject json = buildSkinJson();
      // 2026-10-03 — Same inputs → same PNGs. Was: re-encode wallpaper + selection PNG on every
      // Application.onCreate (main + :overlay), most of Solar's cold-start main-thread time on Y1.
      String key = publishKey(ctx, json);
      if (key != null && key.equals(readStamp(ctx)) && sidecarsPresent(ctx, json)) return;
      SidecarPublishHelper.publishBytes(ctx, SIDECAR_JSON, json.toString().getBytes("UTF-8"));
      publishWallpaperPng(ctx, json.optBoolean("hasWallpaper", false));
      publishSelectionPng(ctx, json.optBoolean("hasSelectionBitmap", false));
      writeStamp(ctx, key);
    } catch (Throwable ignored) {
      writeStamp(ctx, null);
      clearSidecars(ctx);
    }
  }

  private static final String STAMP_FILE = "theme-skin.stamp";

  /**
   * 2026-10-03 — Fingerprint of everything the sidecars are rendered from.
   * Layman: theme colours + the active theme's files (name/size) + Solar version.
   * Technical: skin JSON minus savedAt; theme dir walked two levels; null = always publish.
   * 2026-10-05 — Keyed by folderName, not folderPath, and no mtimes: boot publishes once from
   * the SD copy and again from the internal mirror (different paths and copy mtimes), so the
   * two keys alternated and the stamp never matched. Reversal: folderPath + lastModified.
   */
  static String publishKey(Context ctx, JSONObject json) {
    try {
      JSONObject j = new JSONObject(json.toString());
      j.remove("savedAt");
      StringBuilder sb = new StringBuilder(j.toString());
      sb.append('|').append(SIDECAR_VERSION);
      sb.append('|').append(ctx.getPackageManager()
          .getPackageInfo(ctx.getPackageName(), 0).versionCode);
      ThemeManager.ThemeEntry cur = ThemeManager.getCurrentTheme();
      sb.append('|').append(cur != null ? cur.folderName : "none");
      if (cur != null && cur.folderPath != null && !cur.folderPath.startsWith("asset://")) {
        appendTreeFingerprint(sb, new java.io.File(cur.folderPath), 2);
      }
      return sb.toString();
    } catch (Throwable t) {
      return null;
    }
  }

  private static void appendTreeFingerprint(StringBuilder sb, java.io.File dir, int depth) {
    String[] names = dir.list();
    if (names == null) return;
    java.util.Arrays.sort(names);
    for (String n : names) {
      java.io.File f = new java.io.File(dir, n);
      if (f.isDirectory()) {
        if (depth > 0) appendTreeFingerprint(sb, f, depth - 1);
      } else {
        sb.append('|').append(n).append(':').append(f.length());
      }
    }
  }

  /** Every existing sidecar dir still holds the files the JSON promises. */
  private static boolean sidecarsPresent(Context ctx, JSONObject json) {
    boolean wall = json.optBoolean("hasWallpaper", false);
    boolean sel = json.optBoolean("hasSelectionBitmap", false);
    for (java.io.File dir : SidecarPublishHelper.sidecarDirs(ctx)) {
      if (!dir.isDirectory()) continue;
      if (!new java.io.File(dir, SIDECAR_JSON).isFile()) return false;
      if (wall && !new java.io.File(dir, SIDECAR_WALLPAPER).isFile()) return false;
      if (sel && !new java.io.File(dir, SIDECAR_SELECTION).isFile()) return false;
    }
    return true;
  }

  private static String readStamp(Context ctx) {
    java.io.File f = new java.io.File(ctx.getFilesDir(), STAMP_FILE);
    if (!f.isFile()) return null;
    java.io.FileInputStream in = null;
    try {
      in = new java.io.FileInputStream(f);
      byte[] buf = new byte[(int) Math.min(f.length(), 1 << 20)];
      int off = 0;
      while (off < buf.length) {
        int r = in.read(buf, off, buf.length - off);
        if (r < 0) break;
        off += r;
      }
      return new String(buf, 0, off, "UTF-8");
    } catch (Exception e) {
      return null;
    } finally {
      if (in != null) {
        try { in.close(); } catch (Exception ignored) {}
      }
    }
  }

  /** null deletes the stamp so the next publish always renders. */
  private static void writeStamp(Context ctx, String key) {
    java.io.File f = new java.io.File(ctx.getFilesDir(), STAMP_FILE);
    if (key == null) {
      f.delete();
      return;
    }
    java.io.FileOutputStream out = null;
    try {
      out = new java.io.FileOutputStream(f);
      out.write(key.getBytes("UTF-8"));
    } catch (Exception e) {
      f.delete();
    } finally {
      if (out != null) {
        try { out.close(); } catch (Exception ignored) {}
      }
    }
  }

  /** Remove skin artifacts — stock apps revert on next process start. */
  public static void clearSidecars(Context ctx) {
    SidecarPublishHelper.deleteFromAllRoots(ctx, SIDECAR_JSON);
    SidecarPublishHelper.deleteFromAllRoots(ctx, SIDECAR_WALLPAPER);
    SidecarPublishHelper.deleteFromAllRoots(ctx, SIDECAR_SELECTION);
  }

  /** Keep JSON with enabled:false so Xposed stops painting without deleting font sidecar. */
  private static void publishDisabledJson(Context ctx) {
    try {
      JSONObject json = new JSONObject();
      json.put("version", SIDECAR_VERSION);
      json.put("enabled", false);
      json.put("savedAt", System.currentTimeMillis());
      SidecarPublishHelper.publishBytes(ctx, SIDECAR_JSON, json.toString().getBytes("UTF-8"));
    } catch (Throwable ignored) {
      SidecarPublishHelper.deleteFromAllRoots(ctx, SIDECAR_JSON);
    }
  }

  /** Build skin JSON from live ThemeManager colors — unit-tested field contract. */
  public static JSONObject buildSkinJson() throws Exception {
    JSONObject json = new JSONObject();
    json.put("version", SIDECAR_VERSION);
    json.put("enabled", true);
    json.put("backgroundColor", ThemeManager.getMenuPanelSolidColor());
    json.put("rowSelectionFillColor", ThemeManager.getRowSelectionFillColor() | 0xFF000000);
    json.put("selectedTextColor", ThemeManager.getItemTextColorSelected());
    json.put("textPrimary", ThemeManager.getTextColorPrimary());
    json.put("textMuted", ThemeManager.getTextColorSecondary());
    json.put("statusBarColor", ThemeManager.getStatusBarBackgroundColor() | 0xFF000000);
    json.put("statusBarTextColor", ThemeManager.getStatusBarTextColor());
    boolean hasWall = ThemeManager.hasThemeWallpaper();
    json.put("hasWallpaper", hasWall);
    json.put("hasSelectionBitmap", ThemeManager.usesThemedSelectionBitmap(false));
    json.put("savedAt", System.currentTimeMillis());
    return json;
  }

  private static void publishWallpaperPng(Context ctx, boolean expectWallpaper) {
    SidecarPublishHelper.deleteFromAllRoots(ctx, SIDECAR_WALLPAPER);
    if (!expectWallpaper) return;
    Bitmap src = ThemeManager.getWallpaper(true);
    if (src == null) src = ThemeManager.getWallpaper(false);
    if (src == null) return;
    Bitmap scaled = scaleWallpaperForSkin(src);
    byte[] png = bitmapToPng(scaled);
    if (scaled != src && !src.isRecycled()) src.recycle();
    if (scaled != src && !scaled.isRecycled()) scaled.recycle();
    if (png != null && png.length > 0) {
      SidecarPublishHelper.publishBytes(ctx, SIDECAR_WALLPAPER, png);
    }
  }

  private static void publishSelectionPng(Context ctx, boolean expectBitmap) {
    SidecarPublishHelper.deleteFromAllRoots(ctx, SIDECAR_SELECTION);
    if (!expectBitmap) return;
    Drawable row = ThemeManager.getItemRowBackgroundScaled(
        ctx.getResources(), true, SKIN_SELECTION_W, SKIN_SELECTION_H);
    Bitmap bmp = drawableToBitmap(row, SKIN_SELECTION_W, SKIN_SELECTION_H);
    if (bmp == null) return;
    byte[] png = bitmapToPng(bmp);
    if (!bmp.isRecycled()) bmp.recycle();
    if (png != null && png.length > 0) {
      SidecarPublishHelper.publishBytes(ctx, SIDECAR_SELECTION, png);
    }
  }

  /**
   * 2026-07-05 — Pure math for wallpaper crop — JVM-testable without Android bitmap natives.
   * Returns [scaledW, scaledH, cropX, cropY, cropW, cropH].
   */
  public static int[] wallpaperScaleGeometry(int srcW, int srcH) {
    return wallpaperScaleGeometry(srcW, srcH, SKIN_WALLPAPER_W, SKIN_WALLPAPER_H);
  }

  /** 2026-07-11 — Same crop math with explicit target (A5 240×320 / 320×240). */
  public static int[] wallpaperScaleGeometry(int srcW, int srcH, int targetW, int targetH) {
    if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) {
      return new int[]{0, 0, 0, 0, 0, 0};
    }
    float scale = Math.max(targetW / (float) srcW, targetH / (float) srcH);
    int scaledW = Math.max(1, (int) (srcW * scale));
    int scaledH = Math.max(1, (int) (srcH * scale));
    int x = Math.max(0, (scaledW - targetW) / 2);
    int y = Math.max(0, (scaledH - targetH) / 2);
    int cropW = Math.min(targetW, scaledW);
    int cropH = Math.min(targetH, scaledH);
    return new int[]{scaledW, scaledH, x, y, cropW, cropH};
  }

  /**
   * 2026-07-05 — Center-crop scale to 480×360 for MT6572 RAM budget.
   * Technical: max-axis scale then center crop; upgrade path is lower JPEG quality not larger dims.
   */
  public static Bitmap scaleWallpaperForSkin(Bitmap source) {
    if (source == null) return null;
    int srcW = source.getWidth();
    int srcH = source.getHeight();
    if (srcW <= 0 || srcH <= 0) return null;
    if (srcW == SKIN_WALLPAPER_W && srcH == SKIN_WALLPAPER_H) {
      return source.copy(source.getConfig(), false);
    }
    int[] geo = wallpaperScaleGeometry(srcW, srcH);
    int scaledW = geo[0];
    int scaledH = geo[1];
    Bitmap scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true);
    int x = geo[2];
    int y = geo[3];
    int cropW = geo[4];
    int cropH = geo[5];
    Bitmap cropped = Bitmap.createBitmap(scaled, x, y, cropW, cropH);
    if (scaled != source && !scaled.isRecycled()) scaled.recycle();
    if (cropped.getWidth() == SKIN_WALLPAPER_W && cropped.getHeight() == SKIN_WALLPAPER_H) {
      return cropped;
    }
    Bitmap exact = Bitmap.createScaledBitmap(cropped, SKIN_WALLPAPER_W, SKIN_WALLPAPER_H, true);
    if (cropped != exact && !cropped.isRecycled()) cropped.recycle();
    return exact;
  }

  /** Rasterize a theme row drawable to PNG bytes for the sidecar. */
  static Bitmap drawableToBitmap(Drawable drawable, int w, int h) {
    if (drawable == null || w <= 0 || h <= 0) return null;
    try {
      Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(bmp);
      if (drawable instanceof ColorDrawable) {
        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);
        return bmp;
      }
      if (drawable instanceof BitmapDrawable) {
        Bitmap inner = ((BitmapDrawable) drawable).getBitmap();
        if (inner != null) {
          return Bitmap.createScaledBitmap(inner, w, h, true);
        }
      }
      drawable.setBounds(0, 0, w, h);
      drawable.draw(canvas);
      return bmp;
    } catch (Throwable ignored) {
      return null;
    }
  }

  static byte[] bitmapToPng(Bitmap bmp) {
    if (bmp == null) return null;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      if (!bmp.compress(Bitmap.CompressFormat.PNG, 90, out)) return null;
      return out.toByteArray();
    } catch (Throwable ignored) {
      return null;
    }
  }

  /** Parse enabled flag for unit tests. */
  public static boolean readEnabled(String json) {
    try {
      return new JSONObject(json).optBoolean("enabled", false);
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** Parse background color for unit tests. */
  public static int readBackgroundColor(String json) {
    try {
      return new JSONObject(json).optInt("backgroundColor", 0);
    } catch (Throwable ignored) {
      return 0;
    }
  }
}
