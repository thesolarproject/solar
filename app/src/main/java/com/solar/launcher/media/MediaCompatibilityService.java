package com.solar.launcher.media;

import java.util.Locale;

/**
 * Conservative playback routing for audio files accepted by Solar's media library.
 *
 * <p>The platform route covers formats that Android's API 17/19 MediaPlayer path is expected to
 * handle on the Y1. Formats already supported by Solar's bundled IJK decoder use that fallback.
 * This class does not claim to convert files: an unknown extension remains unsupported until a
 * reproducible converter is actually bundled.</p>
 */
public final class MediaCompatibilityService {
    public enum PlaybackPath {
        PLATFORM,
        IJK,
        UNSUPPORTED
    }

    public static final class Decision {
        public final String extension;
        public final PlaybackPath playbackPath;
        public final boolean canImportWithoutConversion;
        public final String reason;

        private Decision(String extension, PlaybackPath playbackPath,
                boolean canImportWithoutConversion, String reason) {
            this.extension = extension;
            this.playbackPath = playbackPath;
            this.canImportWithoutConversion = canImportWithoutConversion;
            this.reason = reason;
        }
    }

    private MediaCompatibilityService() {}

    public static Decision analyzeName(String name) {
        String extension = extensionOf(name);
        if ("mp3".equals(extension)
                || "flac".equals(extension)
                || "wav".equals(extension)
                || "ogg".equals(extension)
                || "m4a".equals(extension)
                || "m4b".equals(extension)
                || "aac".equals(extension)) {
            return new Decision(extension, PlaybackPath.PLATFORM, true,
                    "Supported by the Y1 platform decoder");
        }
        if ("opus".equals(extension)
                || "webm".equals(extension)
                || "ape".equals(extension)
                || "wma".equals(extension)) {
            return new Decision(extension, PlaybackPath.IJK, true,
                    "Supported by Solar's bundled fallback decoder");
        }
        return new Decision(extension, PlaybackPath.UNSUPPORTED, false,
                "No compatible decoder or converter is bundled");
    }

    public static boolean isSupportedAudioName(String name) {
        return analyzeName(name).canImportWithoutConversion;
    }

    public static boolean prefersIjk(String name) {
        return analyzeName(name).playbackPath == PlaybackPath.IJK;
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        String clean = name.trim();
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < clean.length()) clean = clean.substring(slash + 1);
        int dot = clean.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= clean.length()) return "";
        return clean.substring(dot + 1).toLowerCase(Locale.US);
    }
}
