package com.solar.launcher.flow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.ArrayDeque;

/** Small exact-size RGB_565 pool for API-17-compatible inBitmap reuse. */
public final class ArtworkBitmapPool {
    private static final int MAX_BITMAPS = 4;
    private static final ArrayDeque<Bitmap> POOL = new ArrayDeque<Bitmap>(MAX_BITMAPS);

    private ArtworkBitmapPool() {}

    public static Lease decodeExact(File file, int width, int height) {
        if (file == null || !file.isFile() || width <= 0 || height <= 0) return null;
        Bitmap reusable = acquire(width, height);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inMutable = true;
        options.inBitmap = reusable;
        Bitmap decoded;
        try {
            decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (IllegalArgumentException incompatible) {
            discard(reusable);
            options.inBitmap = null;
            decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (RuntimeException failure) {
            releaseBitmap(reusable);
            return null;
        }
        if (decoded == null) {
            releaseBitmap(reusable);
            return null;
        }
        if (reusable != null && decoded != reusable) releaseBitmap(reusable);
        return new Lease(decoded, width, height, true);
    }

    private static synchronized Bitmap acquire(int width, int height) {
        java.util.Iterator<Bitmap> it = POOL.iterator();
        while (it.hasNext()) {
            Bitmap bitmap = it.next();
            if (bitmap == null || bitmap.isRecycled()) {
                it.remove();
            } else if (bitmap.getWidth() == width && bitmap.getHeight() == height
                    && bitmap.getConfig() == Bitmap.Config.RGB_565 && bitmap.isMutable()) {
                it.remove();
                return bitmap;
            }
        }
        return null;
    }

    private static synchronized void releaseBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        if (!bitmap.isMutable() || bitmap.getConfig() != Bitmap.Config.RGB_565
                || POOL.size() >= MAX_BITMAPS) {
            bitmap.recycle();
            return;
        }
        bitmap.eraseColor(0);
        POOL.addLast(bitmap);
    }

    private static void discard(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    public static final class Lease {
        private Bitmap bitmap;
        private final int width;
        private final int height;
        private final boolean pooled;

        Lease(Bitmap bitmap, int width, int height, boolean pooled) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
            this.pooled = pooled;
        }

        static Lease unpooled(Bitmap bitmap) {
            return bitmap != null ? new Lease(bitmap, bitmap.getWidth(), bitmap.getHeight(), false) : null;
        }

        public Bitmap bitmap() { return bitmap; }

        public void release() {
            Bitmap value = bitmap;
            bitmap = null;
            if (pooled && value != null && value.getWidth() == width && value.getHeight() == height) {
                releaseBitmap(value);
            } else {
                discard(value);
            }
        }
    }
}
