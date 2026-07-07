package com.solar.launcher.photos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import com.solar.launcher.flow.ArtworkDecodeCoordinator;

import java.io.File;

/** ponytail: single-thread decode queue — upgrade to LruCache if gallery scroll stutters */
public final class PhotoThumbnailLoader {
    private PhotoThumbnailLoader() {}

    /** Decode off UI thread; tags {@code target} with path so recycled rows skip stale bitmaps. */
    public static void load(final ImageView target, final File file, final int maxSidePx) {
        if (target == null || file == null || maxSidePx <= 0) return;
        ArtworkDecodeCoordinator.loadPhoto(target, file, maxSidePx);
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
