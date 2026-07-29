package com.solar.launcher.media;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Copies a user-selected audio file into Solar's library without overwriting existing media.
 *
 * <p>The input is written to a hidden partial in the destination directory and published with a
 * same-filesystem rename. The caller retains ownership of the supplied input stream.</p>
 */
public final class AuthorizedMediaImporter {
    static final long STORAGE_RESERVE_BYTES = 8L * 1024L * 1024L;
    private static final int BUFFER_BYTES = 32 * 1024;
    private static final int MAX_SAFE_NAME_CHARS = 180;

    public interface Progress {
        void onProgress(long copiedBytes, long totalBytes);
    }

    public static final class Result {
        public final File file;
        public final boolean duplicate;
        public final long bytes;

        Result(File file, boolean duplicate, long bytes) {
            this.file = file;
            this.duplicate = duplicate;
            this.bytes = bytes;
        }
    }

    private AuthorizedMediaImporter() {}

    public static Result copyToLibrary(InputStream input, String displayName, long expectedSize,
            File outputDirectory, Progress progress) throws IOException {
        if (input == null) throw new IOException("No media input");
        String safeName = safeBasename(displayName);
        if (!MediaCompatibilityService.isSupportedAudioName(safeName)) {
            throw new IOException("Unsupported audio format: " + extensionLabel(safeName));
        }
        ensureDirectory(outputDirectory);
        ensureStorageAvailable(outputDirectory, expectedSize);

        File partial = createPartial(outputDirectory, safeName);
        long copied = 0L;
        boolean published = false;
        try {
            BufferedInputStream in = new BufferedInputStream(input, BUFFER_BYTES);
            FileOutputStream fileOut = new FileOutputStream(partial);
            try {
                BufferedOutputStream out = new BufferedOutputStream(fileOut, BUFFER_BYTES);
                try {
                    byte[] buffer = new byte[BUFFER_BYTES];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (read == 0) continue;
                        out.write(buffer, 0, read);
                        copied += read;
                        if (progress != null) {
                            progress.onProgress(copied, expectedSize > 0 ? expectedSize : -1L);
                        }
                    }
                    out.flush();
                    fileOut.getFD().sync();
                } finally {
                    try { out.close(); } catch (IOException ignored) {}
                }
            } finally {
                try { fileOut.close(); } catch (IOException ignored) {}
            }

            if (copied <= 0L) throw new IOException("Selected audio file is empty");
            if (expectedSize > 0L && copied != expectedSize) {
                throw new IOException("Selected audio changed while it was being copied");
            }

            synchronized (AuthorizedMediaImporter.class) {
                File preferred = new File(outputDirectory, safeName);
                if (preferred.isFile() && filesEqual(preferred, partial)) {
                    if (!partial.delete() && partial.exists()) {
                        throw new IOException("Could not remove duplicate import partial");
                    }
                    published = true;
                    return new Result(preferred, true, copied);
                }
                File destination = nextAvailableDestination(outputDirectory, safeName);
                if (!partial.renameTo(destination)) {
                    throw new IOException("Could not publish imported audio");
                }
                published = true;
                return new Result(destination, false, copied);
            }
        } finally {
            if (!published && partial.exists()) partial.delete();
        }
    }

    public static String safeBasename(String suppliedName) {
        String name = suppliedName != null ? suppliedName : "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);

        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || c == '<' || c == '>' || c == ':'
                    || c == '"' || c == '/' || c == '\\' || c == '|'
                    || c == '?' || c == '*') {
                cleaned.append('_');
            } else {
                cleaned.append(c);
            }
        }
        name = trimUnsafeEdges(cleaned.toString());
        if (name.length() == 0 || ".".equals(name) || "..".equals(name)) {
            name = "Imported Audio";
        }
        if (isFatReservedName(name)) name = "_" + name;
        if (name.length() > MAX_SAFE_NAME_CHARS) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 17
                    ? name.substring(dot) : "";
            int stemLength = MAX_SAFE_NAME_CHARS - extension.length();
            name = name.substring(0, Math.max(1, stemLength)) + extension;
        }
        return name;
    }

    static boolean hasEnoughSpace(long usable, long required, long reserve) {
        if (usable <= 0L) return true;
        if (required < 0L || reserve < 0L) return false;
        if (required > Long.MAX_VALUE - reserve) return false;
        return usable >= required + reserve;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory == null) throw new IOException("No media storage is available");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create the import folder");
        }
        if (!directory.isDirectory()) throw new IOException("Import path is not a folder");
    }

    private static void ensureStorageAvailable(File directory, long expectedSize)
            throws IOException {
        if (expectedSize <= 0L) return;
        if (!hasEnoughSpace(directory.getUsableSpace(), expectedSize, STORAGE_RESERVE_BYTES)) {
            throw new IOException("Not enough storage for this import plus an 8 MiB reserve");
        }
    }

    private static File createPartial(File directory, String safeName) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            String suffix = Long.toHexString(System.nanoTime()) + "-" + attempt;
            File partial = new File(directory, "." + safeName + "." + suffix + ".import.part");
            if (partial.createNewFile()) return partial;
        }
        throw new IOException("Could not reserve an import partial");
    }

    private static File nextAvailableDestination(File directory, String safeName) {
        File preferred = new File(directory, safeName);
        if (!preferred.exists()) return preferred;
        int dot = safeName.lastIndexOf('.');
        String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
        String extension = dot > 0 ? safeName.substring(dot) : "";
        for (int number = 2; number < 10000; number++) {
            File candidate = new File(directory, stem + " (" + number + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, stem + "-" + Long.toHexString(System.nanoTime()) + extension);
    }

    private static boolean filesEqual(File first, File second) throws IOException {
        if (first.length() != second.length()) return false;
        byte[] firstHash = sha256(first);
        byte[] secondHash = sha256(second);
        if (firstHash.length != secondHash.length) return false;
        for (int i = 0; i < firstHash.length; i++) {
            if (firstHash[i] != secondHash[i]) return false;
        }
        return true;
    }

    private static byte[] sha256(File file) throws IOException {
        FileInputStream in = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(file);
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (Exception e) {
            throw new IOException("Could not verify imported audio", e);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static String trimUnsafeEdges(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '.')) start++;
        while (end > start && (value.charAt(end - 1) == ' '
                || value.charAt(end - 1) == '.')) end--;
        return value.substring(start, end);
    }

    private static boolean isFatReservedName(String name) {
        int dot = name.indexOf('.');
        String stem = (dot >= 0 ? name.substring(0, dot) : name).toUpperCase(Locale.US);
        if ("CON".equals(stem) || "PRN".equals(stem) || "AUX".equals(stem)
                || "NUL".equals(stem)) {
            return true;
        }
        if (stem.length() == 4) {
            String prefix = stem.substring(0, 3);
            char digit = stem.charAt(3);
            return ("COM".equals(prefix) || "LPT".equals(prefix))
                    && digit >= '1' && digit <= '9';
        }
        return false;
    }

    private static String extensionLabel(String name) {
        int dot = name != null ? name.lastIndexOf('.') : -1;
        return dot >= 0 && dot + 1 < name.length()
                ? name.substring(dot + 1).toUpperCase(Locale.US) : "unknown";
    }
}
