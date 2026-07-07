package com.solar.launcher.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.solar.launcher.AlbumCoverPipeline;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Exact carousel-thumb JPEG cache — Rockbox .pfraw at display size, not 240px rescaled per frame.
 * ponytail: {@link #DEFAULT_THUMB_PX} matches Y1 {@link CoverFlowLayout#thumbSizePx} upper clamp.
 */
public final class FlowThumbCache {

    public static final int DEFAULT_THUMB_PX = 144;
    public static final int JPEG_QUALITY = 88;
    private static final ThreadLocal<BitmapFactory.Options> DECODE_OPTIONS =
            new ThreadLocal<BitmapFactory.Options>() {
                @Override protected BitmapFactory.Options initialValue() {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    return options;
                }
            };

    private FlowThumbCache() {}

    public static File cacheDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "Solar_FlowThumb");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File fileForKey(File dir, String coverKey, int thumbPx) {
        if (dir == null || coverKey == null || coverKey.isEmpty()) return null;
        return new File(dir, safeName(coverKey) + "_" + thumbPx + ".jpg");
    }

    private static String safeName(String key) {
        String s = key.replace('/', '_').replace('\\', '_');
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    public static boolean has(File dir, String coverKey, int thumbPx) {
        File f = fileForKey(dir, coverKey, thumbPx);
        return f != null && f.isFile() && f.length() > 0
                && !AlbumArtCache.isLikelyPlaceholderFile(f);
    }

    public static void delete(File dir, String coverKey, int thumbPx) {
        File f = fileForKey(dir, coverKey, thumbPx);
        if (f != null && f.isFile()) f.delete();
    }

    /** Decode at native file size — file is already thumbPx square. */
    public static Bitmap get(File dir, String coverKey, int thumbPx) {
        if (ArtworkThreads.isMainThread()) return null;
        File f = fileForKey(dir, coverKey, thumbPx);
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

    public static void put(File dir, String coverKey, Bitmap bitmap, int thumbPx) {
        if (bitmap == null || bitmap.isRecycled() || coverKey == null) return;
        File f = fileForKey(dir, coverKey, thumbPx);
        if (f == null) return;
        Bitmap scaled = bitmap;
        if (bitmap.getWidth() != thumbPx || bitmap.getHeight() != thumbPx) {
            scaled = AlbumCoverPipeline.scaleForFlow(bitmap, thumbPx, thumbPx);
            if (scaled != bitmap && !bitmap.isRecycled()) bitmap.recycle();
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
        } catch (Exception ignored) {
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored2) {}
        }
        if (scaled != bitmap && !scaled.isRecycled()) scaled.recycle();
    }
}
