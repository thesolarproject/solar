package com.solar.launcher.library;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

/**
 * Merges an incremental filesystem scan into Solar's resident library without rebuilding it.
 *
 * <p>The scanner supplies every path still present on disk plus metadata rows for files that are
 * new or changed. This helper removes vanished rows, replaces changed rows in place, and appends
 * genuinely new rows. It is deliberately Android-free so the state transition stays unit
 * testable.</p>
 */
public final class LibraryIncrementalReconciler {

    public interface PathKey<T> {
        String pathOf(T item);
    }

    public static final class Result {
        public final int added;
        public final int replaced;
        public final int removed;

        Result(int added, int replaced, int removed) {
            this.added = added;
            this.replaced = replaced;
            this.removed = removed;
        }

        public boolean changed() {
            return added > 0 || replaced > 0 || removed > 0;
        }
    }

    private LibraryIncrementalReconciler() {}

    public static <T> Result merge(List<T> resident, Set<String> seenPaths,
            List<T> newOrChanged, PathKey<T> pathKey) {
        if (resident == null) throw new IllegalArgumentException("resident");
        if (seenPaths == null) throw new IllegalArgumentException("seenPaths");
        if (pathKey == null) throw new IllegalArgumentException("pathKey");

        LinkedHashMap<String, T> replacements = new LinkedHashMap<String, T>();
        if (newOrChanged != null) {
            for (T item : newOrChanged) {
                String path = cleanPath(pathKey.pathOf(item));
                if (path.length() > 0 && seenPaths.contains(path)) {
                    replacements.put(path, item);
                }
            }
        }

        int removed = 0;
        int replaced = 0;
        ListIterator<T> iterator = resident.listIterator();
        while (iterator.hasNext()) {
            T current = iterator.next();
            String path = cleanPath(pathKey.pathOf(current));
            if (path.length() == 0 || !seenPaths.contains(path)) {
                iterator.remove();
                removed++;
                continue;
            }
            T replacement = replacements.remove(path);
            if (replacement != null) {
                iterator.set(replacement);
                replaced++;
            }
        }

        int added = 0;
        for (Map.Entry<String, T> entry : replacements.entrySet()) {
            if (!seenPaths.contains(entry.getKey())) continue;
            resident.add(entry.getValue());
            added++;
        }
        return new Result(added, replaced, removed);
    }

    private static String cleanPath(String value) {
        return value != null ? value.trim() : "";
    }
}
