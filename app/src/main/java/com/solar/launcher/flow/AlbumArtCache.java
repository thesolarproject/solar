package com.solar.launcher.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.solar.launcher.AlbumCoverPipeline;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 240×240 JPEG album art on internal storage — built during library scan for fast Flow.
 * ponytail: internal filesDir survives USB mass-storage mode; SD paths may vanish.
 */
public final class AlbumArtCache {

    public static final int THUMB_PX = 240;
    public static final int JPEG_QUALITY = 85;
    private static final ThreadLocal<BitmapFactory.Options> DECODE_OPTIONS =
            new ThreadLocal<BitmapFactory.Options>() {
                @Override protected BitmapFactory.Options initialValue() {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    return options;
                }
            };

    private AlbumArtCache() {}

    public static File cacheDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "Solar_AlbumArt");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Carousel thumb decode size for current viewport. */
    public static int carouselThumbPx(float viewW, float viewH) {
        return CoverFlowLayout.metricsForViewport(viewW, viewH).thumbSizePx();
    }

    public static File fileForKey(File dir, String albumMatchKey) {
        if (dir == null || albumMatchKey == null || albumMatchKey.isEmpty()) return null;
        return new File(dir, safeName(albumMatchKey) + ".jpg");
    }

    public static boolean has(File dir, String albumMatchKey) {
        File f = fileForKey(dir, albumMatchKey);
        return f != null && f.isFile() && f.length() > 0 && !isLikelyPlaceholderFile(f);
    }

    /** Tiny grey ♪ tiles — treat as miss so track embed can replace stale cache. */
    public static boolean isLikelyPlaceholderFile(File f) {
        return f != null && f.isFile() && f.length() > 0L && f.length() < 2200L;
    }

    public static void delete(File dir, String albumMatchKey) {
        File f = fileForKey(dir, albumMatchKey);
        if (f != null && f.isFile()) f.delete();
    }

    public static Bitmap get(File dir, String albumMatchKey) {
        if (ArtworkThreads.isMainThread()) return null;
        File f = fileForKey(dir, albumMatchKey);
        if (f == null || !f.isFile()) return null;
        try {
            BitmapFactory.Options opts = DECODE_OPTIONS.get();
            opts.inBitmap = null;
            opts.inSampleSize = 1;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void put(File dir, String albumMatchKey, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        File f = fileForKey(dir, albumMatchKey);
        if (f == null) return;
        Bitmap scaled = bitmap;
        if (bitmap.getWidth() != THUMB_PX || bitmap.getHeight() != THUMB_PX) {
            scaled = AlbumCoverPipeline.scaleForFlow(bitmap, THUMB_PX, THUMB_PX);
            if (scaled != bitmap && !bitmap.isRecycled()) bitmap.recycle();
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
        } catch (Exception ignored) {
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            if (scaled != bitmap && scaled != null && !scaled.isRecycled()) scaled.recycle();
        }
    }

    private static String safeName(String key) {
        String s = key.replace('/', '_').replace('\\', '_');
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }
}
