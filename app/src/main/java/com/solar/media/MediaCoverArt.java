package com.solar.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.net.SolarHttp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 2026-07-29: Consolidated async cover-art fetcher — shared by Navidrome, Jellyfin, and Plex.
 *
 * Callers resolve the final cover-art URL themselves (each server has its own auth/endpoint
 * conventions), then pass it here for download + decode + callback on the main thread.
 */
public final class MediaCoverArt {

    public interface Listener {
        void onBitmap(Bitmap bmp);
        void onFailed();
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    private MediaCoverArt() {}

    /**
     * Fetch a cover-art URL on a background thread, decode it, and deliver the Bitmap
     * (or onFailed) on the main thread.
     */
    public static void load(final String url, final Listener listener) {
        if (url == null || url.isEmpty() || listener == null) {
            if (listener != null) listener.onFailed();
            return;
        }
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] raw = SolarHttp.getBytes(url, "image/*", "SolarLauncher/1.0");
                    if (raw == null || raw.length == 0) {
                        postFailed(listener);
                        return;
                    }
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 1;
                    final Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                    if (bmp == null) {
                        postFailed(listener);
                        return;
                    }
                    postBitmap(listener, bmp);
                } catch (Exception e) {
                    postFailed(listener);
                }
            }
        });
    }

    private static void postBitmap(final Listener listener, final Bitmap bmp) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override public void run() {
                listener.onBitmap(bmp);
            }
        });
    }

    private static void postFailed(final Listener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override public void run() {
                listener.onFailed();
            }
        });
    }
}
