package com.solar.launcher.photos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** ponytail: single-thread decode queue — upgrade to LruCache if gallery scroll stutters */
public final class PhotoThumbnailLoader {
    private static final Executor DECODE = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PhotoThumbnailLoader() {}

    /** Decode off UI thread; tags {@code target} with path so recycled rows skip stale bitmaps. */
    public static void load(final ImageView target, final File file, final int maxSidePx) {
        if (target == null || file == null || maxSidePx <= 0) return;
        final String path = file.getAbsolutePath();
        target.setTag(path);
        DECODE.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decodeMaxSide(path, maxSidePx);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        Object tag = target.getTag();
                        if (tag != null && path.equals(tag)) {
                            target.setImageBitmap(bmp);
                        } else if (bmp != null) {
                            bmp.recycle();
                        }
                    }
                });
            }
        });
    }

    static Bitmap decodeMaxSide(String path, int maxSidePx) {
        if (path == null || maxSidePx <= 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSidePx) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            return null;
        }
    }
}
