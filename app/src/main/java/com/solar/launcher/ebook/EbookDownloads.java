package com.solar.launcher.ebook;

import android.content.Context;

import java.io.File;

/**
 * 2026-11-26 — One background book download for the E-Book tab.
 * Layman: fetching a Z-Library book without freezing the menu.
 * Tech: thin thread wrapper so MediaSuiteHost only deals with rows and callbacks.
 */
public final class EbookDownloads {

    public interface Listener {
        /** Called on a background thread. */
        void onProgress(int percent);

        /** Called on a background thread; {@code saved} is the finished file. */
        void onDone(File saved);

        /** Called on a background thread. */
        void onError(String message);
    }

    private EbookDownloads() {}

    public static void start(final Context ctx, final ZlibClient client,
                             final ZlibClient.Book book, final Listener listener) {
        final Context app = ctx != null ? ctx.getApplicationContext() : null;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File target = EbookLibrary.downloadTarget(app, book.title, book.author,
                            book.extension);
                    client.download(book, target, new ZlibClient.Progress() {
                        @Override
                        public void onProgress(int percent) {
                            if (listener != null) listener.onProgress(percent);
                        }
                    });
                    if (listener != null) listener.onDone(target);
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg == null || msg.length() == 0) msg = "Download failed";
                    if (listener != null) listener.onError(msg);
                }
            }
        }).start();
    }
}
