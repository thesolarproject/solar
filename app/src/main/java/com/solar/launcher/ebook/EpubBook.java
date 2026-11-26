package com.solar.launcher.ebook;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 2026-11-26 — Minimal EPUB reader: zip → container.xml → OPF spine → chapter text.
 * Layman: pulls the words out of the book and cuts them into pages that fit the screen.
 * Tech: no dependency beyond the platform. XHTML is walked with XmlPullParser into a
 *   SpannableStringBuilder (bold/italic/heading sizes kept), laid out once per chapter as
 *   a single StaticLayout, and cut into pages by line index — pages are drawn as windows
 *   into that one layout, so turning never re-flowed text.
 *
 * 2026-11-26 (reader rework) — Pagination runs on one low-priority worker thread and the
 *   reader shows "Formatting…" until the chapter is ready, so opening or leaving a chapter
 *   no longer freezes the UI. Only the last few chapters are kept in memory.
 *   Was: paginate lazily on the UI thread from onDraw. Reversal: pages(chapter) called
 *   synchronously by pageCount/pageLayout.
 */
public final class EpubBook {
    /** Called on the main thread once a chapter's page breaks are ready. */
    public interface Ready {
        void onPagesReady(int chapter);
    }

    /** Chapters kept laid out in memory (current + one ahead + one behind). */
    private static final int CACHE_CHAPTERS = 3;
    private static final float LINE_SPACING_MULT = 1.06f;
    private static final float LINE_SPACING_ADD = 2f;

    private final File file;
    private final ZipFile zip;
    private final String title;
    private final List<String> spineHrefs = new ArrayList<String>();

    private final Object lock = new Object();
    private final Map<Integer, SpannableStringBuilder> textCache = bounded(CACHE_CHAPTERS);
    /** Full chapter layout — pages are windows into this. */
    private final Map<Integer, StaticLayout> layouts = bounded(CACHE_CHAPTERS);
    /** First line index of every page in the chapter. */
    private final Map<Integer, List<Integer>> starts = bounded(CACHE_CHAPTERS);
    private final Set<Integer> queued = new HashSet<Integer>();

    private final ExecutorService worker;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile Ready ready;
    private volatile int layoutW;
    private volatile int layoutH;
    private volatile float textSize;
    /** Bumped on every geometry change so a stale background pass is thrown away. */
    private volatile int layoutGen;
    private volatile boolean closed;

    private EpubBook(File file, ZipFile zip, String title) {
        this.file = file;
        this.zip = zip;
        this.title = title;
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "epub-pages");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
    }

    public static EpubBook open(File file, int width, int height, float textSize)
            throws IOException {
        ZipFile zip = new ZipFile(file);
        try {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) throw new IOException("EPUB is missing its package file");
            String base = opfPath.indexOf('/') >= 0
                    ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            Manifest manifest = new Manifest();
            String[] titleHolder = new String[1];
            parseOpf(zip, opfPath, manifest, titleHolder);
            if (manifest.spine.isEmpty()) throw new IOException("EPUB has no text");
            EpubBook book = new EpubBook(file, zip,
                    titleHolder[0] != null ? titleHolder[0] : EbookLibrary.title(file));
            for (String idref : manifest.spine) {
                String href = manifest.hrefById.get(idref);
                if (href != null) book.spineHrefs.add(base + href);
            }
            if (book.spineHrefs.isEmpty()) throw new IOException("EPUB has no text");
            book.setLayout(width, height, textSize);
            return book;
        } catch (IOException e) {
            try { zip.close(); } catch (IOException ignored) {}
            throw e;
        } catch (Exception e) {
            // XmlPullParser throws checked parse errors — surface them as a plain IO failure.
            try { zip.close(); } catch (IOException ignored) {}
            throw new IOException("Could not read EPUB", e);
        }
    }

    private static final class Manifest {
        final Map<String, String> hrefById = new LinkedHashMap<String, String>();
        final List<String> spine = new ArrayList<String>();
    }

    public String title() {
        return title;
    }

    public int chapterCount() {
        return spineHrefs.size();
    }

    public String chapterTitle(int chapter) {
        return "Chapter " + (chapter + 1);
    }

    /** Zip path of a spine item — used by the WebView engine to load the chapter file. */
    public String chapterPath(int chapter) {
        if (chapter < 0 || chapter >= spineHrefs.size()) return "";
        return spineHrefs.get(chapter);
    }

    /**
     * Unpack the whole book so the WebView can load a chapter with its images and CSS.
     * Uses its own ZipFile handle: the native fallback may be paginating at the same time.
     */
    public void extractTo(File dir) throws IOException {
        if (dir == null) return;
        ZipFile source = new ZipFile(file);
        try {
            String base = dir.getCanonicalPath();
            byte[] buf = new byte[8192];
            java.util.Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name == null || name.length() == 0) continue;
                File out = new File(dir, name);
                // Zip-slip guard: never write outside the cache folder.
                if (!out.getCanonicalPath().startsWith(base)) continue;
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                InputStream in = null;
                OutputStream sink = null;
                try {
                    in = source.getInputStream(entry);
                    sink = new java.io.FileOutputStream(out);
                    int n;
                    while ((n = in.read(buf)) > 0) sink.write(buf, 0, n);
                } finally {
                    if (sink != null) try { sink.close(); } catch (IOException ignored) {}
                    if (in != null) try { in.close(); } catch (IOException ignored) {}
                }
            }
        } finally {
            try { source.close(); } catch (IOException ignored) {}
        }
    }

    /** Page geometry changed (first layout / resize): throw the old cuts away. */
    public void setLayout(int width, int height, float size) {
        if (width == layoutW && height == layoutH && size == textSize) return;
        layoutW = Math.max(1, width);
        layoutH = Math.max(1, height);
        textSize = size;
        layoutGen++;
        synchronized (lock) {
            textCache.clear();
            layouts.clear();
            starts.clear();
            queued.clear();
        }
    }

    /** True once the chapter has page breaks — false while it is still being formatted. */
    public boolean pagesReady(int chapter) {
        synchronized (lock) {
            return starts.containsKey(chapter);
        }
    }

    /** Pages in the chapter; 0 while it is still being formatted. */
    public int pageCount(int chapter) {
        synchronized (lock) {
            List<Integer> s = starts.get(chapter);
            return s != null ? Math.max(1, s.size()) : 0;
        }
    }

    /** Kick off background pagination for a chapter (no-op when ready or already queued). */
    public void requestPages(final int chapter) {
        if (closed || chapter < 0 || chapter >= spineHrefs.size()) return;
        synchronized (lock) {
            if (starts.containsKey(chapter) || !queued.add(chapter)) return;
        }
        final int gen = layoutGen;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                try {
                    ok = paginate(chapter, gen);
                } catch (Throwable t) {
                    ok = false;
                }
                final boolean done = ok;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (lock) {
                            queued.remove(chapter);
                        }
                        if (!done || closed) return;
                        Ready r = ready;
                        if (r != null) r.onPagesReady(chapter);
                    }
                });
            }
        });
    }

    public void setReadyListener(Ready listener) {
        ready = listener;
    }

    /**
     * Draw one page as a window into the chapter layout.
     * Layman: paints the slice of the chapter that belongs on this page.
     */
    public boolean drawPage(Canvas canvas, int chapter, int page,
                            float x, float y, float clipW, float clipH) {
        StaticLayout layout;
        int firstLine;
        synchronized (lock) {
            layout = layouts.get(chapter);
            List<Integer> s = starts.get(chapter);
            if (layout == null || s == null || s.isEmpty()) return false;
            firstLine = s.get(Math.max(0, Math.min(page, s.size() - 1)));
        }
        canvas.save();
        canvas.clipRect(x, y, x + clipW, y + clipH);
        canvas.translate(x, y - layout.getLineTop(firstLine));
        layout.draw(canvas);
        canvas.restore();
        return true;
    }

    /** Character offset of a page start — the resume anchor inside the chapter. */
    public int pageStart(int chapter, int page) {
        StaticLayout layout;
        int firstLine;
        synchronized (lock) {
            layout = layouts.get(chapter);
            List<Integer> s = starts.get(chapter);
            if (layout == null || s == null || s.isEmpty()) return 0;
            firstLine = s.get(Math.max(0, Math.min(page, s.size() - 1)));
        }
        int lines = Math.max(1, layout.getLineCount());
        int start = layout.getLineStart(Math.max(0, Math.min(firstLine, lines - 1)));
        return Math.max(0, start);
    }

    public int pageForOffset(int chapter, int offset) {
        StaticLayout layout;
        List<Integer> s;
        synchronized (lock) {
            layout = layouts.get(chapter);
            s = starts.get(chapter);
        }
        if (layout == null || s == null || s.isEmpty()) return 0;
        int best = 0;
        int lines = Math.max(1, layout.getLineCount());
        for (int i = 0; i < s.size(); i++) {
            int line = Math.max(0, Math.min(s.get(i), lines - 1));
            if (layout.getLineStart(line) <= offset) best = i;
            else break;
        }
        return best;
    }

    /** Runs on the worker thread. Returns false when the result was thrown away. */
    private boolean paginate(int chapter, int gen) {
        if (closed || gen != layoutGen) return false;
        int w = Math.max(1, layoutW);
        int h = Math.max(1, layoutH);

        SpannableStringBuilder text;
        try {
            text = chapterText(chapter);
        } catch (Throwable t) {
            text = new SpannableStringBuilder();
        }

        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSize);
        paint.setColor(0xFF111111);
        paint.setSubpixelText(true);

        StaticLayout layout;
        try {
            layout = new StaticLayout(text, paint, w, Layout.Alignment.ALIGN_NORMAL,
                    LINE_SPACING_MULT, LINE_SPACING_ADD, false);
        } catch (Throwable t) {
            layout = new StaticLayout("", paint, w, Layout.Alignment.ALIGN_NORMAL,
                    LINE_SPACING_MULT, LINE_SPACING_ADD, false);
        }

        List<Integer> pageStarts = new ArrayList<Integer>();
        pageStarts.add(0);
        int pageTop = 0;
        int lineCount = layout.getLineCount();
        for (int line = 1; line < lineCount; line++) {
            if (layout.getLineBottom(line) - pageTop > h) {
                pageStarts.add(line);
                pageTop = layout.getLineTop(line);
            }
        }

        if (closed || gen != layoutGen) return false;
        synchronized (lock) {
            if (closed || gen != layoutGen) return false;
            layouts.put(chapter, layout);
            starts.put(chapter, pageStarts);
        }
        return true;
    }

    private SpannableStringBuilder chapterText(int chapter) {
        synchronized (lock) {
            SpannableStringBuilder cached = textCache.get(chapter);
            if (cached != null) return cached;
        }
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (chapter < 0 || chapter >= spineHrefs.size()) {
            synchronized (lock) { textCache.put(chapter, out); }
            return out;
        }
        ZipEntry entry = zip.getEntry(spineHrefs.get(chapter));
        if (entry == null) {
            // Some producers list the href without the OPF folder prefix.
            String href = spineHrefs.get(chapter);
            int slash = href.indexOf('/');
            if (slash >= 0) entry = zip.getEntry(href.substring(slash + 1));
        }
        if (entry != null) {
            InputStream in = null;
            try {
                in = zip.getInputStream(entry);
                readXhtml(in, out);
            } catch (Exception ignored) {
                // Broken chapter: keep whatever text was collected.
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
            }
        }
        synchronized (lock) { textCache.put(chapter, out); }
        return out;
    }

    private static void readXhtml(InputStream in, SpannableStringBuilder out) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new InputStreamReader(in, "UTF-8"));
        int event = parser.getEventType();
        // Open emphasis spans: [style, sizePct, start]
        List<int[]> open = new ArrayList<int[]>();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = lower(parser.getName());
                int style = 0;
                int sizePct = 0;
                if ("b".equals(name) || "strong".equals(name)) style = 1;
                else if ("i".equals(name) || "em".equals(name)) style = 2;
                else if ("h1".equals(name)) { style = 1; sizePct = 150; }
                else if ("h2".equals(name)) { style = 1; sizePct = 130; }
                else if ("h3".equals(name)) { style = 1; sizePct = 115; }
                if (style != 0 || sizePct != 0) {
                    open.add(new int[]{style, sizePct, out.length()});
                }
                if ("br".equals(name)) out.append('\n');
            } else if (event == XmlPullParser.TEXT) {
                String text = parser.getText();
                if (text != null) out.append(text.replace('\n', ' ').replace('\t', ' '));
            } else if (event == XmlPullParser.END_TAG) {
                String name = lower(parser.getName());
                boolean block = "p".equals(name) || "div".equals(name)
                        || "h1".equals(name) || "h2".equals(name) || "h3".equals(name)
                        || "li".equals(name) || "tr".equals(name) || "blockquote".equals(name);
                if (block) {
                    trimTrailingSpace(out);
                    out.append("\n\n");
                }
                if (!open.isEmpty()) {
                    int[] top = open.remove(open.size() - 1);
                    int start = top[2];
                    int end = out.length();
                    if (end > start) {
                        if (top[0] == 1) {
                            out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end,
                                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                        } else if (top[0] == 2) {
                            out.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start, end,
                                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        if (top[1] > 0) {
                            out.setSpan(new RelativeSizeSpan(top[1] / 100f), start, end,
                                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                    }
                }
            }
            event = parser.nextToken();
        }
        trimTrailingSpace(out);
    }

    private static void trimTrailingSpace(SpannableStringBuilder sb) {
        while (sb.length() > 0) {
            char c = sb.charAt(sb.length() - 1);
            if (c == ' ' || c == '\n') sb.delete(sb.length() - 1, sb.length());
            else break;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    private static <V> Map<Integer, V> bounded(final int max) {
        return new LinkedHashMap<Integer, V>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, V> eldest) {
                return size() > max;
            }
        };
    }

    private static String findOpfPath(ZipFile zip) throws Exception {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) return null;
        InputStream in = zip.getInputStream(container);
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "rootfile".equals(lower(parser.getName()))) {
                    String path = attribute(parser, "full-path");
                    if (path != null && path.length() > 0) return path;
                }
                event = parser.next();
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        return null;
    }

    private static void parseOpf(ZipFile zip, String opfPath, Manifest manifest, String[] title)
            throws Exception {
        ZipEntry entry = zip.getEntry(opfPath);
        if (entry == null) return;
        InputStream in = zip.getInputStream(entry);
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = lower(parser.getName());
                    if ("item".equals(name)) {
                        String id = attribute(parser, "id");
                        String href = attribute(parser, "href");
                        if (id != null && href != null) manifest.hrefById.put(id, href);
                    } else if ("itemref".equals(name)) {
                        String idref = attribute(parser, "idref");
                        if (idref != null) manifest.spine.add(idref);
                    } else if ("title".equals(name)) {
                        if (title[0] == null) title[0] = parser.nextText().trim();
                    }
                }
                event = parser.next();
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static String attribute(XmlPullParser parser, String name) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (name.equals(lower(parser.getAttributeName(i)))) {
                return parser.getAttributeValue(i);
            }
        }
        return null;
    }

    public void close() {
        closed = true;
        ready = null;
        worker.shutdownNow();
        synchronized (lock) {
            textCache.clear();
            layouts.clear();
            starts.clear();
            queued.clear();
        }
        try { zip.close(); } catch (IOException ignored) {}
    }

    public File file() {
        return file;
    }
}
