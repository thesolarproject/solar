package com.solar.launcher.soulseek;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ponytail: Reach Play downloads — temp files under app cache/reach; purge when off-queue. */
public final class ReachCache {
    public static final String DIR_NAME = "reach";

    private ReachCache() {}

    public static File dir(File appCacheRoot) {
        File d = new File(appCacheRoot, DIR_NAME);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public static boolean isTempFile(File appCacheRoot, File f) {
        if (f == null || appCacheRoot == null) return false;
        String root = dir(appCacheRoot).getAbsolutePath();
        String path = f.getAbsolutePath();
        return path.equals(root) || path.startsWith(root + File.separator);
    }

    public static void purgeUnreferenced(File appCacheRoot, List<File> keep) {
        File cache = dir(appCacheRoot);
        File[] files = cache.listFiles();
        if (files == null) return;
        Set<String> keepPaths = new HashSet<String>();
        if (keep != null) {
            for (File f : keep) {
                if (f != null) keepPaths.add(f.getAbsolutePath());
            }
        }
        for (File f : files) {
            if (f.isFile() && !keepPaths.contains(f.getAbsolutePath())) {
                f.delete();
            }
        }
    }
}
