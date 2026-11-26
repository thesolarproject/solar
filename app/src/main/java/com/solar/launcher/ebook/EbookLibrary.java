package com.solar.launcher.ebook;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 2026-11-26 — Local book library: Ebook/ on every healthy user volume.
 * Layman: everything in the Ebook folder shows up in the reader.
 * Tech: matches README's lowercase "ebook" too (vfat is case-insensitive on device,
 *   but Internal/eMMC is not, so both spellings are listed).
 */
public final class EbookLibrary {
    public static final String FOLDER = "Ebook";
    public static final String FOLDER_LOWER = "ebook";

    private static final String[] EXT = {".epub", ".pdf"};

    private EbookLibrary() {}

    /** Folders to scan, deduped, whether or not they exist yet. */
    public static List<File> getRoots() {
        List<File> out = new ArrayList<File>();
        for (File dir : com.solar.launcher.DeviceFeatures.getEbookRoots()) {
            addUnique(out, dir);
        }
        return out;
    }

    /** Folder new downloads land in — follows the Primary storage pref. */
    public static File getSaveRoot(Context ctx) {
        File base = ctx != null
                ? com.solar.launcher.DeviceFeatures.getNewMediaRoot(ctx)
                : com.solar.launcher.DeviceFeatures.getPrimaryStorageRoot();
        File root = new File(base, FOLDER);
        if (!root.exists()) root.mkdirs();
        return root;
    }

    /** All books under every root, sorted by title. */
    public static List<File> scan() {
        List<File> out = new ArrayList<File>();
        for (File root : getRoots()) {
            collect(root, out, 0);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return title(a).compareToIgnoreCase(title(b));
            }
        });
        return out;
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (dir == null || depth > 3 || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
            } else if (isBook(f)) {
                if (!containsSamePath(out, f)) out.add(f);
            }
        }
    }

    private static boolean containsSamePath(List<File> out, File f) {
        String p = f.getAbsolutePath();
        for (File o : out) {
            if (o.getAbsolutePath().equals(p)) return true;
        }
        return false;
    }

    private static void addUnique(List<File> out, File dir) {
        String p = dir.getAbsolutePath();
        for (File o : out) {
            if (o.getAbsolutePath().equals(p)) return;
        }
        out.add(dir);
    }

    public static boolean isBook(File f) {
        return f != null && f.isFile() && f.length() > 0 && isBookName(f.getName());
    }

    public static boolean isBookName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        for (String ext : EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    public static boolean isPdf(File f) {
        return f != null && f.getName().toLowerCase(Locale.US).endsWith(".pdf");
    }

    /** Display title: filename without extension, underscores as spaces. */
    public static String title(File f) {
        if (f == null) return "";
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.replace('_', ' ').trim();
    }

    /** "EPUB · 2.4 MB" */
    public static String subtitle(File f) {
        if (f == null) return "";
        String format = isPdf(f) ? "PDF" : "EPUB";
        return format + " · " + formatSize(f.length());
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Filename for a downloaded book; keeps the extension the server gave. */
    public static File downloadTarget(Context ctx, String title, String author, String ext) {
        String safe = sanitize(title);
        if (author != null && author.trim().length() > 0) {
            safe = safe + " - " + sanitize(author);
        }
        if (safe.length() == 0) safe = "book";
        if (safe.length() > 80) safe = safe.substring(0, 80).trim();
        String suffix = ext != null && ext.length() > 0 ? "." + ext.toLowerCase(Locale.US) : ".epub";
        File dir = getSaveRoot(ctx);
        File out = new File(dir, safe + suffix);
        int n = 2;
        while (out.exists()) {
            out = new File(dir, safe + " (" + n + ")" + suffix);
            n++;
        }
        return out;
    }

    static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('/', '-').replace('\\', '-').replace(':', '-')
                .replace('*', '-').replace('?', '-').replace('"', '-')
                .replace('<', '-').replace('>', '-').replace('|', '-')
                .trim();
    }

    /** ponytail: filename + folder listing sanity only. */
    public static void selfCheck() {
        if (!isBookName("a.EPUB")) throw new AssertionError("epub");
        if (!isBookName("b.pdf")) throw new AssertionError("pdf");
        if (isBookName("c.mp3")) throw new AssertionError("not a book");
        if (isBookName(null)) throw new AssertionError("null");
        if (!"My Book".equals(title(new File("/x/My_Book.epub")))) {
            throw new AssertionError("title");
        }
        if (!"a-b".equals(sanitize("a/b"))) throw new AssertionError("sanitize");
        if (!"2.0 KB".equals(formatSize(2048))) throw new AssertionError("size");
    }
}
