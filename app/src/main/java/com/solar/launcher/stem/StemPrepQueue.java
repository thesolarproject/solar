package com.solar.launcher.stem;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * FIFO queue for stem preparation requests.
 *
 * Tracks are de-duplicated by absolute path so selecting a song repeatedly, or
 * moving from background preparation to the full-screen queue, cannot schedule
 * the same separation twice.
 */
public final class StemPrepQueue {
    private final Queue<File> pending = new ArrayDeque<File>();
    private final Set<String> paths = new HashSet<String>();

    public StemPrepQueue(List<File> initial) {
        if (initial == null) return;
        for (int i = 0; i < initial.size(); i++) enqueue(initial.get(i));
    }

    public boolean enqueue(File track) {
        if (track == null || !track.isFile()) return false;
        String path = track.getAbsolutePath();
        if (path == null || paths.contains(path)) return false;
        paths.add(path);
        pending.add(track);
        return true;
    }

    public File poll() {
        return pending.poll();
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public int size() {
        return pending.size();
    }
}
