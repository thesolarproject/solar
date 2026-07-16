package com.solar.launcher.soulseek;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ponytail: purge Reach + podcast stream temps on low-storage hardware. */
public final class StreamTempCache {
    public static final String PODCAST_DIR = "podcast";

    private StreamTempCache() {}

    public static List<File> reachKeepList(List<File> musicQueue, File growing, File queuePartial) {
        List<File> keep = new ArrayList<File>();
        if (musicQueue != null) keep.addAll(musicQueue);
        if (growing != null && growing.isFile()) keep.add(growing);
        if (queuePartial != null && queuePartial.isFile()) keep.add(queuePartial);
        return keep;
    }

    public static void purgeReach(File appCacheRoot, List<File> musicQueue, File growing, File queuePartial) {
        ReachCache.purgeUnreferenced(appCacheRoot, reachKeepList(musicQueue, growing, queuePartial));
    }

    /** Drop orphaned podcast .part / pod_* temps; keep active growing/final files. */
    public static void purgePodcastStream(File appCacheRoot, File growing, File growingFinal) {
        File dir = new File(appCacheRoot, PODCAST_DIR);
        File[] files = dir.listFiles();
        if (files == null) return;
        Set<String> keep = new HashSet<String>();
        if (growing != null) keep.add(growing.getAbsolutePath());
        if (growingFinal != null) keep.add(growingFinal.getAbsolutePath());
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (!name.endsWith(".part") && !name.startsWith("pod_")) continue;
            if (!keep.contains(f.getAbsolutePath())) f.delete();
        }
    }
}
