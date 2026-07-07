package com.solar.launcher.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;

/** Scan-time 32px album backdrop cache; runtime performs decode only. */
public final class AlbumBackdropCache {
    public static final int SIZE_PX = 32;

    private AlbumBackdropCache() {}

    public static File cacheDir(Context context) {
        File dir = new File(context.getFilesDir(), "Solar_AlbumBackdrops");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File fileForKey(File dir, String key) {
        if (dir == null || key == null || key.isEmpty()) return null;
        String safe = key.replace('/', '_').replace('\\', '_');
        if (safe.length() > 120) safe = safe.substring(0, 120);
        return new File(dir, safe + "_backdrop.jpg");
    }

    public static boolean has(File dir, String key) {
        File file = fileForKey(dir, key);
        return file != null && file.isFile() && file.length() > 0L;
    }

    public static Bitmap get(File dir, String key) {
        if (ArtworkThreads.isMainThread()) return null;
        File file = fileForKey(dir, key);
        if (file == null || !file.isFile()) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    public static void put(File dir, String key, Bitmap source) {
        if (source == null || source.isRecycled() || has(dir, key)) return;
        File file = fileForKey(dir, key);
        if (file == null) return;
        Bitmap small = Bitmap.createScaledBitmap(source, SIZE_PX, SIZE_PX, true);
        Bitmap blurred = blur32(small);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            blurred.compress(Bitmap.CompressFormat.JPEG, 78, out);
        } catch (Exception ignored) {
            file.delete();
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) {}
            if (blurred != small && !blurred.isRecycled()) blurred.recycle();
            if (small != source && !small.isRecycled()) small.recycle();
        }
    }

    private static Bitmap blur32(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] input = new int[width * height];
        int[] temp = new int[input.length];
        source.getPixels(input, 0, width, 0, 0, width, height);
        boxPass(input, temp, width, height, true);
        boxPass(temp, input, width, height, false);
        boxPass(input, temp, width, height, true);
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        output.setPixels(temp, 0, width, 0, 0, width, height);
        return output;
    }

    private static void boxPass(int[] input, int[] output, int width, int height, boolean horizontal) {
        final int radius = 3;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = 0, r = 0, g = 0, b = 0, count = 0;
                for (int d = -radius; d <= radius; d++) {
                    int sx = horizontal ? Math.max(0, Math.min(width - 1, x + d)) : x;
                    int sy = horizontal ? y : Math.max(0, Math.min(height - 1, y + d));
                    int color = input[sy * width + sx];
                    a += color >>> 24;
                    r += color >> 16 & 0xff;
                    g += color >> 8 & 0xff;
                    b += color & 0xff;
                    count++;
                }
                output[y * width + x] = (a / count << 24) | (r / count << 16)
                        | (g / count << 8) | b / count;
            }
        }
    }
}
