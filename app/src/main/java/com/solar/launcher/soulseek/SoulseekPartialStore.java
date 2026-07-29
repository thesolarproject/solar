package com.solar.launcher.soulseek;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Durable bookkeeping for an interrupted Soulseek transfer.
 *
 * <p>The peer protocol supports a byte offset, but an offset is only safe when the partial file
 * belongs to the exact same peer/path and the advertised size has not changed. The small sidecar
 * stores a one-way source identity rather than credentials or session state.</p>
 */
final class SoulseekPartialStore {
    static final long STORAGE_RESERVE_BYTES = 8L * 1024L * 1024L;
    private static final int MAGIC = 0x534C5054; // SLPT
    private static final int VERSION = 1;
    private static final int MAX_DUPLICATES = 1000;
    private static final int MAX_SAFE_NAME_CHARS = 180;

    static final class Entry {
        final File completeFile;
        final File partialFile;
        final File metadataFile;
        final String sourceIdentity;
        final long expectedSize;

        Entry(File completeFile, String sourceIdentity, long expectedSize) {
            this.completeFile = completeFile;
            this.partialFile = new File(completeFile.getParentFile(),
                    completeFile.getName() + ".part");
            this.metadataFile = new File(completeFile.getParentFile(),
                    completeFile.getName() + ".part.meta");
            this.sourceIdentity = sourceIdentity;
            this.expectedSize = expectedSize;
        }
    }

    private static final class Metadata {
        final String sourceIdentity;
        final long expectedSize;

        Metadata(String sourceIdentity, long expectedSize) {
            this.sourceIdentity = sourceIdentity;
            this.expectedSize = expectedSize;
        }
    }

    private SoulseekPartialStore() {}

    static Entry select(File directory, String remoteName, String peer, long expectedSize)
            throws IOException {
        if (directory == null) throw new IOException("Download directory is unavailable");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create download directory");
        }

        String safeName = safeBasename(remoteName);
        String identity = sourceIdentity(peer, remoteName);
        int dot = safeName.lastIndexOf('.');
        String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
        String extension = dot > 0 ? safeName.substring(dot) : "";
        Entry firstFree = null;

        for (int i = 0; i < MAX_DUPLICATES; i++) {
            String candidateName = i == 0 ? safeName : stem + "_" + i + extension;
            Entry candidate = new Entry(new File(directory, candidateName), identity, expectedSize);
            if (candidate.completeFile.exists()) continue;

            if (candidate.partialFile.isFile()) {
                Metadata metadata = readMetadata(candidate.metadataFile);
                if (metadata != null
                        && identity.equals(metadata.sourceIdentity)
                        && sizesCompatible(metadata.expectedSize, expectedSize)) {
                    if (metadata.expectedSize != expectedSize && expectedSize > 0) {
                        writeMetadata(candidate);
                    }
                    return candidate;
                }
                // Never append one peer's bytes to another peer/path with the same basename.
                continue;
            }

            if (candidate.metadataFile.exists()) {
                // A sidecar without a payload cannot be resumed and is safe to clean up.
                candidate.metadataFile.delete();
            }
            if (firstFree == null) firstFree = candidate;
        }

        if (firstFree == null) {
            firstFree = new Entry(new File(directory,
                    stem + "_" + System.currentTimeMillis() + extension), identity, expectedSize);
        }
        writeMetadata(firstFree);
        return firstFree;
    }

    static long prepareResume(Entry entry) throws IOException {
        long partialSize = entry.partialFile.isFile() ? entry.partialFile.length() : 0L;
        if (entry.expectedSize > 0 && partialSize > entry.expectedSize) {
            if (!entry.partialFile.delete()) {
                throw new IOException("Invalid partial file cannot be reset");
            }
            partialSize = 0L;
        }
        writeMetadata(entry);
        return partialSize;
    }

    static File finish(Entry entry) throws IOException {
        if (!entry.partialFile.isFile()) {
            throw new IOException("Downloaded partial file is missing");
        }
        if (entry.expectedSize > 0 && entry.partialFile.length() != entry.expectedSize) {
            throw new IOException("Incomplete download: " + entry.partialFile.length()
                    + " of " + entry.expectedSize + " bytes saved");
        }
        if (entry.completeFile.exists()) {
            throw new IOException("Download destination already exists");
        }
        if (!entry.partialFile.renameTo(entry.completeFile)) {
            throw new IOException("Could not publish completed download; partial file was kept");
        }
        if (entry.metadataFile.exists()) entry.metadataFile.delete();
        return entry.completeFile;
    }

    static void ensureStorageAvailable(File directory, long totalSize, long existingBytes)
            throws IOException {
        if (totalSize <= 0) return;
        long remaining = Math.max(0L, totalSize - Math.max(0L, existingBytes));
        long usable = directory.getUsableSpace();
        if (!hasEnoughSpace(usable, remaining, STORAGE_RESERVE_BYTES)) {
            throw new IOException("Not enough storage: need " + remaining
                    + " bytes plus an 8 MiB safety reserve");
        }
    }

    static boolean hasEnoughSpace(long usable, long remaining, long reserve) {
        if (usable <= 0) return true; // Some Android 4.2 storage providers report unknown.
        if (remaining < 0 || reserve < 0) return false;
        if (remaining > Long.MAX_VALUE - reserve) return false;
        return usable >= remaining + reserve;
    }

    static String safeBasename(String remoteName) {
        String name = remoteName != null ? remoteName : "";
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
            name = "download.bin";
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

    private static String trimUnsafeEdges(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '.')) start++;
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '.')) end--;
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

    private static boolean sizesCompatible(long stored, long current) {
        return stored <= 0 || current <= 0 || stored == current;
    }

    private static String sourceIdentity(String peer, String remoteName) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, peer != null ? peer.toLowerCase(Locale.US) : "");
            digest.update((byte) 0);
            updateDigest(digest, remoteName != null ? remoteName : "");
            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IOException("Cannot identify partial download", e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) throws Exception {
        digest.update(value.getBytes("UTF-8"));
    }

    private static Metadata readMetadata(File file) {
        if (!file.isFile()) return null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            String identity = in.readUTF();
            long expected = in.readLong();
            return new Metadata(identity, expected);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void writeMetadata(Entry entry) throws IOException {
        File temp = new File(entry.metadataFile.getParentFile(),
                entry.metadataFile.getName() + ".tmp");
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)));
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(entry.sourceIdentity);
            out.writeLong(entry.expectedSize);
            out.flush();
        } finally {
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
        }
        if (entry.metadataFile.exists() && !entry.metadataFile.delete()) {
            temp.delete();
            throw new IOException("Cannot update partial-download metadata");
        }
        if (!temp.renameTo(entry.metadataFile)) {
            temp.delete();
            throw new IOException("Cannot persist partial-download metadata");
        }
    }
}
