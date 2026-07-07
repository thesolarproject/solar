package com.solar.launcher.flow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;

/** Local artist image cache under Solar_Artists/ (Deezer prefetch phase 2). */
public final class ArtistImageCache {

    private ArtistImageCache() {}

    public static File artistsDir(File coversRoot) {
        File dir = new File(coversRoot.getParentFile(), "Solar_Artists");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File fileForKey(File coversRoot, String artistMatchKey) {
        if (artistMatchKey == null || artistMatchKey.isEmpty()) return null;
        return new File(artistsDir(coversRoot), safeName(artistMatchKey) + ".jpg");
    }

    public static Bitmap get(File coversRoot, String artistMatchKey) {
        if (ArtworkThreads.isMainThread()) return null;
        File f = fileForKey(coversRoot, artistMatchKey);
        if (f == null || !f.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void save(File coversRoot, String artistMatchKey, Bitmap bitmap) {
        if (bitmap == null) return;
        File f = fileForKey(coversRoot, artistMatchKey);
        if (f == null) return;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos);
        } catch (Exception ignored) {
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
        }
    }

    private static String safeName(String key) {
        return key.replace('/', '_').replace('\\', '_');
    }
}
