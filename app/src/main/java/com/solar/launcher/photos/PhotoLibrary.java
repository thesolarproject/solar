package com.solar.launcher.photos;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** On-device photo roots: Pictures, DCIM on MicroSD and Environment paths. */
public final class PhotoLibrary {
    private static final File SDCARD_PICTURES = new File("/storage/sdcard0/Pictures");
    private static final File SDCARD_DCIM = new File("/storage/sdcard0/DCIM");

    private static final String[] IMAGE_EXT = {".jpg", ".jpeg", ".png"};

    private PhotoLibrary() {}

    /** Existing browse roots, deduped by absolute path. */
    public static List<File> listFolders() {
        Set<String> seen = new LinkedHashSet<String>();
        List<File> out = new ArrayList<File>();
        addRootIfNew(out, seen, SDCARD_PICTURES);
        addRootIfNew(out, seen, SDCARD_DCIM);
        // ponytail: Environment throws on JVM unit tests — sdcard paths cover Y1 hardware
        addEnvRootIfNew(out, seen, Environment.DIRECTORY_PICTURES);
        addEnvRootIfNew(out, seen, Environment.DIRECTORY_DCIM);
        return out;
    }

    /** Image files in {@code folder}, newest first; empty when folder missing. */
    public static List<File> listImagesInFolder(File folder) {
        List<File> out = new ArrayList<File>();
        if (folder == null || !folder.isDirectory()) return out;
        File[] files = folder.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.length() > 0 && isImageFile(f.getName())) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long da = a.lastModified();
                long db = b.lastModified();
                return da == db ? 0 : (da > db ? -1 : 1);
            }
        });
        return out;
    }

    public static boolean isImageFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String ext : IMAGE_EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static void addRootIfNew(List<File> out, Set<String> seen, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        String path = dir.getAbsolutePath();
        if (seen.add(path)) out.add(dir);
    }

    private static void addEnvRootIfNew(List<File> out, Set<String> seen, String type) {
        try {
            addRootIfNew(out, seen, Environment.getExternalStoragePublicDirectory(type));
        } catch (Throwable ignored) {
        }
    }

    /** ponytail: extension + folder listing sanity only */
    public static void selfCheck() {
        if (!isImageFile("snap.JPG")) throw new AssertionError("jpg");
        if (!isImageFile("x.jpeg")) throw new AssertionError("jpeg");
        if (!isImageFile("a.png")) throw new AssertionError("png");
        if (isImageFile("readme.txt")) throw new AssertionError("not image");
        if (isImageFile(null)) throw new AssertionError("null name");
        List<File> roots = listFolders();
        if (roots == null) throw new AssertionError("roots null");
        File tmp = new File(System.getProperty("java.io.tmpdir"), "solar_photo_lib");
        if (!tmp.isDirectory() && !tmp.mkdirs()) throw new AssertionError("tmpdir");
        File img = new File(tmp, "probe.jpg");
        try {
            if (!img.createNewFile() && !img.isFile()) throw new AssertionError("touch");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(img);
            fos.write(1);
            fos.close();
            List<File> listed = listImagesInFolder(tmp);
            if (listed.size() != 1 || !listed.get(0).equals(img)) throw new AssertionError("list");
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            img.delete();
            tmp.delete();
        }
    }
}
