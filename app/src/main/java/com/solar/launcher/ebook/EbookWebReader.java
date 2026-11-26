package com.solar.launcher.ebook;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 2026-11-26 — EPUB engine: the platform WebView renders real chapter HTML.
 * Layman: the book is shown the way a normal Android reader shows it — the book's own
 *   pages, with its pictures and styling — two columns at a time.
 * Tech: the zip is unpacked once into the app cache, the chapter XHTML is loaded from
 *   file:// with a small style/script payload injected, and pages are CSS multi-column
 *   columns scrolled horizontally. Page turns are a scrollLeft jump, so nothing is
 *   re-flowed while reading (that was the stutter in the old hand-rolled renderer).
 *
 *   Geometry is computed in JS from the live viewport, so density and screen size need no
 *   special casing. If the WebView cannot start (a ROM without a WebView provider) or
 *   never reports back, {@link Listener#onWebReaderFailed} lets the host fall back to
 *   the native renderer in {@link EbookReaderState}.
 */
public final class EbookWebReader {
    /** Sent to solarGo() to mean "the last spread" (Back into the previous chapter). */
    private static final int LAST_SPREAD = 999999;
    private static final String PAGE_FILE = "__solar_page.html";
    private static final long READY_TIMEOUT_MS = 6000L;

    /** Host fallback hook: the WebView path is unusable for this book. */
    public interface Listener {
        void onWebReaderFailed(File book);
    }

    private Context app;
    private WebView web;
    private EpubBook book;
    private File file;
    private File dir;
    private int chapter;
    private int spread;
    private int spreads = 1;
    private int gen;
    private boolean open;
    private boolean ready;
    private Listener listener;
    private String assetCss;
    private String assetJs;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ebook-web");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isOpen() {
        return open;
    }

    public String title() {
        return book != null ? book.title() : "";
    }

    /** Status-bar label: "Chapter 4 · 10–11 / 240". */
    public String label() {
        if (!open) return "";
        int total = Math.max(1, spreads);
        int first = spread * 2 + 1;
        int last = Math.min(total * 2, first + 1);
        String pages = last > first
                ? String.format(Locale.US, "%d–%d", first, last)
                : String.valueOf(first);
        return book != null
                ? book.chapterTitle(chapter) + " · " + pages + " / " + (total * 2)
                : pages;
    }

    /**
     * Open a book in the given WebView. False means the caller should use the native
     * reader instead (no WebView on this ROM / book could not be parsed).
     */
    public boolean open(Context ctx, WebView view, File f) {
        close();
        if (ctx == null || view == null || f == null) return false;
        app = ctx.getApplicationContext();
        web = view;
        file = f;
        try {
            applySettings(web);
        } catch (Throwable t) {
            web = null;
            return false;
        }
        try {
            book = EpubBook.open(f, 320, 400, EbookReaderState.TEXT_SIZE);
        } catch (IOException e) {
            book = null;
            web = null;
            return false;
        }
        EbookProgressStore.Progress p = EbookProgressStore.load(app, f);
        chapter = Math.max(0, Math.min(p.chapter, book.chapterCount() - 1));
        spread = Math.max(0, p.page);
        open = true;
        ready = false;
        showLoading();
        prepareAsync();
        return true;
    }

    private void applySettings(WebView view) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setDefaultTextEncodingName("utf-8");
        s.setLightTouchEnabled(false);
        view.setBackgroundColor(0xFF000000);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setLongClickable(false);
        view.addJavascriptInterface(new Bridge(), "SolarReader");
        view.setWebViewClient(new WebViewClient());
        // 2026-11-26 — tap a side to turn, like the native view. The click wheel is
        // handled by the activity before it can reach the WebView.
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    turn(event.getX() >= v.getWidth() / 2f);
                }
                return true;
            }
        });
    }

    private void showLoading() {
        if (web == null) return;
        String label = app != null
                ? app.getString(com.solar.launcher.R.string.ebook_formatting)
                : "…";
        String html = "<html><head><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1,user-scalable=no\">"
                + "</head><body style=\"margin:0;background:#000;color:#9a9a9a;"
                + "font:13px sans-serif;text-align:center;padding-top:45%\">" + label
                + "</body></html>";
        web.loadData(html, "text/html", "utf-8");
    }

    private void prepareAsync() {
        final int g = ++gen;
        final EpubBook source = book;
        final File f = file;
        final Context ctx = app;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                File target = null;
                try {
                    target = extract(ctx, source, f);
                } catch (Throwable t) {
                    target = null;
                }
                final File result = target;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!open || g != gen) return;
                        if (result == null) {
                            fail();
                            return;
                        }
                        dir = result;
                        loadChapter(chapter, spread);
                    }
                });
            }
        });
    }

    /** Unpack the book once per file revision; the WebView then loads real files. */
    private File extract(Context ctx, EpubBook source, File f) throws IOException {
        String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        File base = new File(ctx.getCacheDir(), "ebook");
        File out = new File(base, Integer.toHexString(key.hashCode()));
        File done = new File(out, ".done");
        if (done.isFile()) return out;
        // Only the book being read is kept unpacked.
        File[] old = base.listFiles();
        if (old != null) {
            for (File o : old) {
                if (!o.equals(out)) deleteTree(o);
            }
        }
        deleteTree(out);
        if (!out.mkdirs()) throw new IOException("no cache dir");
        source.extractTo(out);
        FileOutputStream mark = new FileOutputStream(done);
        try {
            mark.write('1');
        } finally {
            mark.close();
        }
        return out;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void loadChapter(int c, int targetSpread) {
        if (!open || book == null || dir == null || web == null) return;
        chapter = Math.max(0, Math.min(c, book.chapterCount() - 1));
        spread = Math.max(0, targetSpread);
        ready = false;
        File src = new File(dir, book.chapterPath(chapter));
        if (!src.isFile()) {
            fail();
            return;
        }
        String html;
        try {
            html = readFile(src);
        } catch (IOException e) {
            fail();
            return;
        }
        File pageFile = new File(src.getParentFile(), PAGE_FILE);
        try {
            writeFile(pageFile, inject(html, spread));
        } catch (IOException e) {
            fail();
            return;
        }
        final int g = ++gen;
        web.loadUrl(Uri.fromFile(pageFile).toString());
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (open && g == gen && !ready) fail();
            }
        }, READY_TIMEOUT_MS);
    }

    private void fail() {
        if (!open) return;
        open = false;
        ready = false;
        gen++;
        File book = file;
        if (this.book != null) {
            this.book.close();
            this.book = null;
        }
        Listener l = listener;
        if (web != null) {
            try {
                web.loadUrl("about:blank");
            } catch (Throwable ignored) {}
        }
        if (l != null && book != null) l.onWebReaderFailed(book);
    }

    /** Page turn: scroll by one spread, or move into the next/previous chapter. */
    public boolean turn(boolean next) {
        if (!open || web == null || !ready) return false;
        int target = spread + (next ? 1 : -1);
        if (target >= 0 && target < spreads) {
            spread = target;
            go(target);
            saveProgress();
            return true;
        }
        if (next) {
            if (book != null && chapter + 1 < book.chapterCount()) {
                loadChapter(chapter + 1, 0);
                return true;
            }
            return false;
        }
        if (book != null && chapter > 0) {
            loadChapter(chapter - 1, LAST_SPREAD);
            return true;
        }
        return false;
    }

    private void go(int index) {
        if (web == null) return;
        String js = "solarGo(" + index + ")";
        // KitKat has evaluateJavascript (API 19); older builds fall back to a JS URL.
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            try {
                web.evaluateJavascript(js, null);
                return;
            } catch (Throwable ignored) {}
        }
        try {
            web.loadUrl("javascript:" + js);
        } catch (Throwable ignored) {}
    }

    public void saveProgress() {
        if (app == null || file == null) return;
        EbookProgressStore.save(app, file, chapter, spread);
    }

    public void close() {
        if (open) saveProgress();
        open = false;
        ready = false;
        gen++;
        if (web != null) {
            try {
                web.loadUrl("about:blank");
            } catch (Throwable ignored) {}
        }
        web = null;
        if (book != null) {
            book.close();
            book = null;
        }
        dir = null;
        file = null;
    }

    // --- chapter HTML -------------------------------------------------------------------

    private String inject(String html, int startSpread) {
        String head = "<meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1,user-scalable=no\">"
                + "<style>" + assetCss() + "</style>"
                + "<script>window.__solarInit={start:" + startSpread
                + ",title:" + jsString(book != null ? book.title() : "")
                + ",chapter:" + jsString(book != null ? book.chapterTitle(chapter) : "")
                + "};</script>"
                + "<script>" + assetJs() + "</script>";
        int at = indexOfIgnoreCase(html, "</head>");
        if (at >= 0) return html.substring(0, at) + head + html.substring(at);
        at = indexOfIgnoreCase(html, "<body");
        if (at >= 0) {
            int gt = html.indexOf('>', at);
            if (gt >= 0) return html.substring(0, gt + 1) + head + html.substring(gt + 1);
        }
        return head + html;
    }

    /** Page CSS/JS live in assets so they can be read and tested outside the app. */
    private String assetCss() {
        if (assetCss == null) assetCss = readAsset("ebook/reader.css");
        return assetCss;
    }

    private String assetJs() {
        if (assetJs == null) assetJs = readAsset("ebook/reader.js");
        return assetJs;
    }

    private String readAsset(String name) {
        Context ctx = app;
        if (ctx == null) return "";
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static String jsString(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n' || c == '\r') b.append(' ');
            else if (c < 32) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.US).indexOf(needle.toLowerCase(Locale.US));
    }

    private static String readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            try {
                return out.toString("UTF-8");
            } catch (UnsupportedEncodingException e) {
                return out.toString();
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {}
        }
    }

    private static void writeFile(File f, String text) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    // --- JS bridge ----------------------------------------------------------------------

    private final class Bridge {
        @JavascriptInterface
        public void onReady(final int count) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!open) return;
                    ready = true;
                    spreads = Math.max(1, count);
                    saveProgress();
                }
            });
        }

        @JavascriptInterface
        public void onPage(final int index, final int count) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!open) return;
                    spreads = Math.max(1, count);
                    spread = Math.max(0, Math.min(index, spreads - 1));
                }
            });
        }
    }
}
