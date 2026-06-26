package com.solar.launcher.video;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Local video files on MicroSD: /storage/sdcard0/Videos and browsable parents. */
public final class VideoLibrary {
    public static final File DEVICE_ROOT = new File("/storage/sdcard0");
    public static final File ROOT = new File("/storage/sdcard0/Videos");

    private static final String[] VIDEO_EXT = {
            ".mp4", ".mkv", ".avi", ".mov", ".flv", ".webm", ".ts", ".m4v"
    };

    /** ponytail: unit tests only — null restores live scan root. */
    static File rootOverride = null;
    /** ponytail: unit tests only — null restores DEVICE_ROOT. */
    static File deviceRootOverride = null;

    private VideoLibrary() {}

    static File scanRoot() {
        return rootOverride != null ? rootOverride : ROOT;
    }

    static File deviceRoot() {
        return deviceRootOverride != null ? deviceRootOverride : DEVICE_ROOT;
    }

    public static boolean isVideoFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String ext : VIDEO_EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** True when {@code dir} is device root or a folder under it. */
    public static boolean isBrowsablePath(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        String path = dir.getAbsolutePath();
        String root = deviceRoot().getAbsolutePath();
        return path.equals(root) || path.startsWith(root + File.separator);
    }

    /** Parent folder when still browsable; null at device root top. */
    public static File browseParent(File dir) {
        if (dir == null) return null;
        File parent = dir.getParentFile();
        if (parent != null && isBrowsablePath(parent)) return parent;
        return null;
    }

    /** All video files under {@link #ROOT}, recursive, sorted by path. */
    public static List<File> scanAll() {
        List<File> out = new ArrayList<File>();
        File root = scanRoot();
        if (!root.isDirectory()) return out;
        collectVideos(root, out);
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
            }
        });
        return out;
    }

    /** Video files in one folder (non-recursive), sorted by name. */
    public static List<File> listInFolder(File dir) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.length() > 0 && isVideoFileName(f.getName())) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    /** Immediate child folders under root that contain at least one video file. */
    public static List<File> listFoldersWithVideos() {
        return listChildFoldersWithVideos(scanRoot());
    }

    /** Immediate child folders under {@code parent} that contain any video file. */
    public static List<File> listChildFoldersWithVideos(File parent) {
        List<File> out = new ArrayList<File>();
        if (parent == null || !parent.isDirectory()) return out;
        File[] dirs = parent.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            if (!listInFolder(d).isEmpty() || hasVideosRecursive(d)) out.add(d);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    private static void collectVideos(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isFile() && f.length() > 0 && isVideoFileName(f.getName())) {
                out.add(f);
            } else if (f.isDirectory()) {
                collectVideos(f, out);
            }
        }
    }

    private static boolean hasVideosRecursive(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File f : children) {
            if (f.isFile() && f.length() > 0 && isVideoFileName(f.getName())) return true;
            if (f.isDirectory() && hasVideosRecursive(f)) return true;
        }
        return false;
    }

    static void selfCheck() {
        if (!isVideoFileName("clip.MP4")) throw new AssertionError("mp4");
        if (!isVideoFileName("movie.mkv")) throw new AssertionError("mkv");
        if (isVideoFileName("notes.txt")) throw new AssertionError("txt");
        if (isVideoFileName(null)) throw new AssertionError("null");
    }
}
