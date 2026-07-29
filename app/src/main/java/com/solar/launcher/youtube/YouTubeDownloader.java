package com.solar.launcher.youtube;

import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * Compatibility boundary for old Solar call sites.
 *
 * YouTube is metadata-only. Creator-provided downloads, user-owned originals,
 * podcasts, and other authorized files enter through separate providers; this
 * class can never resolve or download a YouTube audiovisual stream.
 */
public final class YouTubeDownloader {

    public interface Callback {
        void onProgress(String phase, int percent, long doneBytes, long totalBytes);
        void onComplete(File savedFile);
        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private YouTubeDownloader() {}

    public static void saveVideo(android.content.Context context, YouTubeVideo video,
            Callback callback) {
        blocked(callback);
    }

    public static void saveAudio(android.content.Context context, YouTubeVideo video,
            Callback callback) {
        blocked(callback);
    }

    public static void cacheAudioForPlay(android.content.Context context, YouTubeVideo video,
            Callback callback) {
        blocked(callback);
    }

    private static void blocked(final Callback callback) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(YouTubeClient.ACQUISITION_BLOCKED);
            }
        });
    }

    /** Sanitize title for migration paths and authorized local imports. */
    static String safeName(String raw) {
        return YouTubeSavePaths.safeName(raw);
    }
}
