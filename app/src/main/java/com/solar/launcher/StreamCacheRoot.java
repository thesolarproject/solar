package com.solar.launcher;

import android.content.Context;

import java.io.File;

/** Reach/Deezer/podcast stream temps — prefer app cache, fall back to SD when internal is tight. */
public final class StreamCacheRoot {
    /** ponytail: 32 MiB free on internal — upgrade to StatFs if devices report wrong */
    private static final long MIN_INTERNAL_FREE_BYTES = 32L * 1024L * 1024L;

    private StreamCacheRoot() {}

    public static File resolve(Context ctx) {
        if (ctx == null) return new File("/storage/sdcard0");
        File internal = ctx.getCacheDir();
        if (hasSpace(internal, MIN_INTERNAL_FREE_BYTES)) return internal;
        File sd = new File("/storage/sdcard0/Android/data/" + ctx.getPackageName() + "/cache");
        if (!sd.isDirectory()) sd.mkdirs();
        if (sd.isDirectory() && hasSpace(sd, MIN_INTERNAL_FREE_BYTES)) return sd;
        return internal != null ? internal : sd;
    }

    static boolean hasSpace(File dir, long minFree) {
        if (dir == null) return false;
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        return dir.getUsableSpace() >= minFree;
    }
}
