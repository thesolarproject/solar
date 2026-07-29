package com.solar.launcher.youtube;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Small file cache for public YouTube metadata.
 *
 * Keys are hashed so search text is not exposed in filenames. Writes use a
 * same-directory temporary file plus rename, payloads are size-bounded, and
 * oldest entries are evicted once the cache exceeds its configured limit.
 */
public final class YouTubeMetadataCache {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final long DEFAULT_MAX_BYTES = 1024L * 1024L;
    private static final int MAX_ENTRY_BYTES = 512 * 1024;

    public static final class Hit {
        public final String payload;
        public final boolean stale;
        public final long ageMs;

        Hit(String payload, boolean stale, long ageMs) {
            this.payload = payload != null ? payload : "";
            this.stale = stale;
            this.ageMs = Math.max(0L, ageMs);
        }
    }

    private final File directory;
    private long maxBytes;

    public YouTubeMetadataCache(Context context) {
        this(new File(context.getApplicationContext().getCacheDir(),
                "youtube_metadata"), DEFAULT_MAX_BYTES);
    }

    YouTubeMetadataCache(File directory, long maxBytes) {
        if (directory == null) throw new IllegalArgumentException("directory");
        this.directory = directory;
        this.maxBytes = clampMaxBytes(maxBytes);
    }

    public synchronized Hit get(String key, long freshForMs, long keepForMs) {
        File file = fileFor(key);
        if (!file.isFile()) return null;
        long age = Math.max(0L, System.currentTimeMillis() - file.lastModified());
        if (age > Math.max(freshForMs, keepForMs) || file.length() <= 0L
                || file.length() > MAX_ENTRY_BYTES) {
            file.delete();
            return null;
        }
        String payload = read(file);
        if (payload.length() == 0) {
            file.delete();
            return null;
        }
        return new Hit(payload, age > Math.max(0L, freshForMs), age);
    }

    public synchronized void put(String key, String payload) {
        if (key == null || key.length() == 0 || payload == null
                || payload.length() == 0) {
            return;
        }
        byte[] bytes = payload.getBytes(UTF_8);
        if (bytes.length > MAX_ENTRY_BYTES) return;
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        File target = fileFor(key);
        File part = new File(directory, target.getName() + ".part");
        File old = new File(directory, target.getName() + ".old");
        part.delete();
        old.delete();
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(part);
            out.write(bytes);
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            if (target.isFile() && !target.renameTo(old)) {
                part.delete();
                return;
            }
            if (!part.renameTo(target)) {
                if (old.isFile()) old.renameTo(target);
                part.delete();
                return;
            }
            old.delete();
            target.setLastModified(System.currentTimeMillis());
            trim();
        } catch (Exception ignored) {
            part.delete();
            if (!target.isFile() && old.isFile()) old.renameTo(target);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {}
            }
        }
    }

    public synchronized void setMaxBytes(long bytes) {
        maxBytes = clampMaxBytes(bytes);
        trim();
    }

    public synchronized long sizeBytes() {
        long total = 0L;
        File[] files = cacheFiles();
        for (File file : files) total += Math.max(0L, file.length());
        return total;
    }

    public synchronized void clear() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file != null && file.isFile()) file.delete();
        }
    }

    private void trim() {
        File[] files = cacheFiles();
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                if (left.lastModified() == right.lastModified()) return 0;
                return left.lastModified() < right.lastModified() ? -1 : 1;
            }
        });
        long total = 0L;
        for (File file : files) total += Math.max(0L, file.length());
        for (File file : files) {
            if (total <= maxBytes) break;
            long length = Math.max(0L, file.length());
            if (file.delete()) total -= length;
        }
    }

    private File[] cacheFiles() {
        File[] files = directory.listFiles();
        if (files == null) return new File[0];
        int count = 0;
        for (File file : files) {
            if (file != null && file.isFile() && file.getName().endsWith(".cache")) {
                count++;
            }
        }
        File[] out = new File[count];
        int index = 0;
        for (File file : files) {
            if (file != null && file.isFile() && file.getName().endsWith(".cache")) {
                out[index++] = file;
            }
        }
        return out;
    }

    private File fileFor(String key) {
        return new File(directory, hash(key != null ? key : "") + ".cache");
    }

    private static String read(File file) {
        FileInputStream in = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
            while ((count = in.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_ENTRY_BYTES) return "";
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), UTF_8);
        } catch (Exception ignored) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                out.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
            }
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static long clampMaxBytes(long bytes) {
        return Math.max(128L * 1024L, Math.min(8L * 1024L * 1024L, bytes));
    }
}
