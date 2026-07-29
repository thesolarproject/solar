package com.solar.launcher.media;

import com.solar.launcher.net.SolarHttp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads a creator-provided direct audio-file URL into Solar's media library.
 *
 * <p>This deliberately accepts only a URL whose path names a format Solar can already decode.
 * It is not a web-page extractor, does not resolve media-platform streams, and never overwrites an
 * existing library file. A stable hidden partial makes the existing HTTP Range support resumable
 * across a paused transfer or process restart.</p>
 */
public final class AuthorizedDirectDownload {
    private static final long STORAGE_RESERVE_BYTES = 8L * 1024L * 1024L;
    private static final int HASH_BUFFER_BYTES = 32 * 1024;
    private static final int MAX_URL_CHARS = 8000;
    private static final int MAX_DUPLICATE_CANDIDATES = 256;

    /** Validated URL metadata with no filesystem or network side effects. */
    public static final class Source {
        public final String url;
        public final String host;
        public final String displayName;

        Source(String url, String host, String displayName) {
            this.url = url;
            this.host = host;
            this.displayName = displayName;
        }
    }

    public static final class Plan {
        public final String url;
        public final String host;
        public final String displayName;
        public final File target;
        public final File partial;
        final File directory;
        final boolean recoverPublishedTarget;

        private Plan(String url, String host, String displayName, File directory,
                File target, File partial, boolean recoverPublishedTarget) {
            this.url = url;
            this.host = host;
            this.displayName = displayName;
            this.directory = directory;
            this.target = target;
            this.partial = partial;
            this.recoverPublishedTarget = recoverPublishedTarget;
        }
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

    private static final class ParsedUrl {
        final String url;
        final String host;
        final String displayName;

        ParsedUrl(String url, String host, String displayName) {
            this.url = url;
            this.host = host;
            this.displayName = displayName;
        }
    }

    private static final class StorageRuntimeException extends RuntimeException {
        StorageRuntimeException(String message) {
            super(message);
        }
    }

    private AuthorizedDirectDownload() {}

    /** Creates a new transfer plan without touching the network. */
    public static Plan prepare(String suppliedUrl, File downloadDirectory) throws IOException {
        ParsedUrl parsed = parse(suppliedUrl);
        ensureDirectory(downloadDirectory);
        ensureCapacity(downloadDirectory, 0L);
        File target = nextAvailableDestination(downloadDirectory, parsed.displayName);
        return plan(parsed, downloadDirectory, target, false);
    }

    /** Validates a direct playable-audio URL without creating folders or making a request. */
    public static Source inspect(String suppliedUrl) throws IOException {
        ParsedUrl parsed = parse(suppliedUrl);
        return new Source(parsed.url, parsed.host, parsed.displayName);
    }

    /**
     * Reconstructs a plan from the private transfer journal.
     *
     * <p>The canonical-parent check prevents a damaged or edited journal from publishing outside
     * Solar's dedicated Music/Downloads directory.</p>
     */
    public static Plan resume(String suppliedUrl, File target, File downloadDirectory)
            throws IOException {
        ParsedUrl parsed = parse(suppliedUrl);
        ensureDirectory(downloadDirectory);
        if (target == null || target.getName().length() == 0
                || !MediaCompatibilityService.isSupportedAudioName(target.getName())) {
            throw new IOException("The saved download destination is invalid");
        }
        File canonicalDirectory = downloadDirectory.getCanonicalFile();
        File canonicalTarget = target.getCanonicalFile();
        if (canonicalTarget.getParentFile() == null
                || !canonicalDirectory.equals(canonicalTarget.getParentFile())) {
            throw new IOException("The saved download destination is outside Music/Downloads");
        }
        return plan(parsed, canonicalDirectory, canonicalTarget, true);
    }

    public static Result download(final Plan plan, final SolarHttp.DownloadProgress progress,
            AtomicBoolean cancel) throws IOException {
        if (plan == null) throw new IOException("No direct download was prepared");
        ensureDirectory(plan.directory);
        ensureCapacity(plan.directory, 0L);

        if (plan.recoverPublishedTarget && plan.target.isFile()
                && plan.target.length() > 0L && !plan.partial.exists()) {
            rejectObviousNonAudio(plan.target);
            return new Result(plan.target, true, plan.target.length());
        }

        final long[] lastStorageCheck = new long[] {-1L};
        try {
            SolarHttp.downloadToFile(plan.url, plan.partial,
                    new SolarHttp.DownloadProgress() {
                        @Override
                        public void onProgress(long done, long total) {
                            if (lastStorageCheck[0] < 0L
                                    || done - lastStorageCheck[0] >= 1024L * 1024L
                                    || (total > 0L && done >= total)) {
                                lastStorageCheck[0] = done;
                                long remaining = total > done ? total - done : 0L;
                                try {
                                    ensureCapacity(plan.directory, remaining);
                                } catch (IOException e) {
                                    throw new StorageRuntimeException(e.getMessage());
                                }
                            }
                            if (progress != null) progress.onProgress(done, total);
                        }
                    }, 0L, null, cancel, plan.partial.isFile() ? plan.partial.length() : 0L);
        } catch (StorageRuntimeException e) {
            throw new IOException(e.getMessage());
        }

        if (cancel != null && cancel.get()) throw new IOException("Download cancelled");
        if (!plan.partial.isFile() || plan.partial.length() <= 0L) {
            throw new IOException("The direct audio file is empty");
        }
        sync(plan.partial);
        try {
            rejectObviousNonAudio(plan.partial);
        } catch (IOException e) {
            if (!plan.partial.delete() && plan.partial.exists()) {
                throw new IOException(e.getMessage() + "; the invalid partial could not be removed");
            }
            throw e;
        }
        if (cancel != null && cancel.get()) throw new IOException("Download cancelled");

        synchronized (AuthorizedDirectDownload.class) {
            File duplicate = findDuplicate(plan.directory, plan.displayName, plan.partial);
            if (cancel != null && cancel.get()) throw new IOException("Download cancelled");
            if (duplicate != null) {
                if (!plan.partial.delete() && plan.partial.exists()) {
                    throw new IOException("Could not remove the duplicate download partial");
                }
                return new Result(duplicate, true, duplicate.length());
            }
            File destination = plan.target.exists()
                    ? nextAvailableDestination(plan.directory, plan.displayName) : plan.target;
            if (cancel != null && cancel.get()) throw new IOException("Download cancelled");
            if (!plan.partial.renameTo(destination)) {
                throw new IOException("Could not publish downloaded audio");
            }
            return new Result(destination, false, destination.length());
        }
    }

    /** Deletes only the hidden partial derived from a validated plan. */
    public static boolean deletePartial(Plan plan) {
        return plan == null || !plan.partial.exists() || plan.partial.delete();
    }

    private static Plan plan(ParsedUrl parsed, File directory, File target,
            boolean recoverPublishedTarget) throws IOException {
        String fingerprint = shortSha256(parsed.url);
        File partial = new File(directory,
                "." + target.getName() + "." + fingerprint + ".download.part");
        return new Plan(parsed.url, parsed.host, parsed.displayName, directory, target, partial,
                recoverPublishedTarget);
    }

    private static ParsedUrl parse(String suppliedUrl) throws IOException {
        String raw = suppliedUrl != null ? suppliedUrl.trim() : "";
        if (raw.length() == 0) throw new IOException("Enter a direct audio-file URL");
        if (raw.length() > MAX_URL_CHARS) throw new IOException("The URL is too long");
        final URI uri;
        try {
            uri = new URI(raw);
        } catch (Exception e) {
            throw new IOException("The direct audio URL is not valid");
        }
        String scheme = uri.getScheme() != null
                ? uri.getScheme().toLowerCase(Locale.US) : "";
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IOException("Only HTTP or HTTPS direct audio URLs are supported");
        }
        String host = uri.getHost();
        if (host == null || host.trim().length() == 0) {
            throw new IOException("The direct audio URL has no host");
        }
        host = trimTrailingDot(host.toLowerCase(Locale.US));
        if (uri.getRawUserInfo() != null && uri.getRawUserInfo().length() > 0) {
            throw new IOException("URLs containing a username or password are not accepted");
        }
        if (uri.getRawFragment() != null) {
            throw new IOException("Remove the # fragment from the direct audio URL");
        }
        if (isMediaPlatformHost(host)) {
            throw new IOException(
                    "Use YouTube Metadata for YouTube; Solar does not extract its media streams");
        }
        String path = uri.getRawPath();
        int slash = path != null ? path.lastIndexOf('/') : -1;
        String rawName = path != null && slash + 1 < path.length()
                ? path.substring(slash + 1) : "";
        final String decodedName;
        try {
            // URLDecoder treats '+' as a form-space, but '+' is literal inside a URI path.
            decodedName = URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            throw new IOException("The direct audio filename is not valid");
        }
        String safeName = AuthorizedMediaImporter.safeBasename(decodedName);
        if (decodedName.trim().length() == 0
                || !MediaCompatibilityService.isSupportedAudioName(safeName)) {
            throw new IOException(
                    "The URL path must end in an audio format this Solar build can play");
        }
        return new ParsedUrl(raw, host, safeName);
    }

    private static boolean isMediaPlatformHost(String host) {
        return hostEqualsOrSubdomain(host, "youtube.com")
                || hostEqualsOrSubdomain(host, "youtu.be")
                || hostEqualsOrSubdomain(host, "youtube-nocookie.com")
                || hostEqualsOrSubdomain(host, "googlevideo.com");
    }

    private static boolean hostEqualsOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String trimTrailingDot(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '.') end--;
        return value.substring(0, end);
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory == null) throw new IOException("No media storage is available");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create Music/Downloads");
        }
        if (!directory.isDirectory()) throw new IOException("Music/Downloads is not a folder");
    }

    private static void ensureCapacity(File directory, long remainingBytes) throws IOException {
        long usable = directory.getUsableSpace();
        if (usable <= 0L) return;
        if (remainingBytes < 0L
                || remainingBytes > Long.MAX_VALUE - STORAGE_RESERVE_BYTES
                || usable < remainingBytes + STORAGE_RESERVE_BYTES) {
            throw new IOException("Not enough storage to finish this download and keep 8 MiB free");
        }
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
        return new File(directory,
                stem + "-" + Long.toHexString(System.nanoTime()) + extension);
    }

    private static File findDuplicate(File directory, String safeName, File partial)
            throws IOException {
        File[] files = directory.listFiles();
        if (files == null) return null;
        int dot = safeName.lastIndexOf('.');
        String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
        String extension = dot > 0 ? safeName.substring(dot) : "";
        int checked = 0;
        for (File candidate : files) {
            if (candidate == null || !candidate.isFile() || candidate.equals(partial)) continue;
            String name = candidate.getName();
            boolean matchingName = name.equals(safeName)
                    || (name.startsWith(stem + " (") && name.endsWith(")" + extension));
            if (!matchingName || candidate.length() != partial.length()) continue;
            if (++checked > MAX_DUPLICATE_CANDIDATES) break;
            if (filesEqual(candidate, partial)) return candidate;
        }
        return null;
    }

    private static boolean filesEqual(File first, File second) throws IOException {
        byte[] firstHash = sha256(first);
        byte[] secondHash = sha256(second);
        if (firstHash.length != secondHash.length) return false;
        for (int i = 0; i < firstHash.length; i++) {
            if (firstHash[i] != secondHash[i]) return false;
        }
        return true;
    }

    private static byte[] sha256(File file) throws IOException {
        FileInputStream input = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input = new FileInputStream(file);
            byte[] buffer = new byte[HASH_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (Exception e) {
            throw new IOException("Could not verify downloaded audio", e);
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) {}
        }
    }

    private static String shortSha256(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                out.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IOException("Could not prepare the download partial", e);
        }
    }

    private static void sync(File file) throws IOException {
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file, true);
            output.getFD().sync();
        } finally {
            if (output != null) try { output.close(); } catch (IOException ignored) {}
        }
    }

    private static void rejectObviousNonAudio(File file) throws IOException {
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] prefix = new byte[(int) Math.min(512L, file.length())];
            int count = input.read(prefix);
            if (count <= 0) throw new IOException("The direct audio file is empty");
            int start = 0;
            while (start < count && isAsciiWhitespace(prefix[start])) start++;
            String sample = new String(prefix, start, count - start, "US-ASCII")
                    .toLowerCase(Locale.US);
            if (sample.startsWith("<!doctype")
                    || sample.startsWith("<html")
                    || sample.startsWith("<head")
                    || sample.startsWith("<body")
                    || sample.startsWith("<?xml")
                    || sample.startsWith("{\"error\"")
                    || sample.startsWith("{\"message\"")) {
                throw new IOException("The server returned a web page instead of an audio file");
            }
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) {}
        }
    }

    private static boolean isAsciiWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }
}
