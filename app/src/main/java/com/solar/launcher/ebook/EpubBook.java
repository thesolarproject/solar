package com.solar.launcher.ebook;

import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.StyleSpan;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 2026-11-26 — Minimal EPUB reader: zip → container.xml → OPF spine → chapter text.
 * Layman: pulls the words out of the book and cuts them into pages that fit the screen.
 * Tech: no dependency beyond the platform. XHTML is walked with XmlPullParser into a
 *   SpannableStringBuilder (bold/italic/heading sizes kept), then paginated with
 *   StaticLayout at the width/height the reader view asks for. Inline images, CSS and
 *   tables are dropped on purpose — ponytail: text-only, upgrade path is an image-aware
 *   layout pass if anyone misses covers mid-chapter.
 */
public final class EpubBook {
    private final File file;
    private final ZipFile zip;
    private final String title;
    private final List<String> spineHrefs = new ArrayList<String>();
    private final Map<Integer, SpannableStringBuilder> textCache =
            new HashMap<Integer, SpannableStringBuilder>();
    private final Map<Integer, List<StaticLayout>> pageCache =
            new HashMap<Integer, List<StaticLayout>>();
    private final Map<Integer, List<Integer>> startsCache =
            new HashMap<Integer, List<Integer>>();

    private int layoutW;
    private int layoutH;
    private float textSize;

    private EpubBook(File file, ZipFile zip, String title) {
        this.file = file;
        this.zip = zip;
        this.title = title;
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
        final Map<String, String> hrefById = new HashMap<String, String>();
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

    /** Page geometry changed (window resize / text size): rebuild page cuts lazily. */
    public void setLayout(int width, int height, float size) {
        if (width == layoutW && height == layoutH && size == textSize) return;
        layoutW = Math.max(1, width);
        layoutH = Math.max(1, height);
        textSize = size;
        pageCache.clear();
        startsCache.clear();
    }

    public int pageCount(int chapter) {
        pages(chapter);
        List<StaticLayout> pages = pageCache.get(chapter);
        return pages != null ? Math.max(1, pages.size()) : 1;
    }

    public StaticLayout pageLayout(int chapter, int page) {
        pages(chapter);
        List<StaticLayout> pages = pageCache.get(chapter);
        if (pages == null || pages.isEmpty()) return null;
        int i = Math.max(0, Math.min(page, pages.size() - 1));
        return pages.get(i);
    }

    /** Character offset of a page start — the resume anchor inside the chapter. */
    public int pageStart(int chapter, int page) {
        pages(chapter);
        List<Integer> starts = startsCache.get(chapter);
        if (starts == null || starts.isEmpty()) return 0;
        int i = Math.max(0, Math.min(page, starts.size() - 1));
        return starts.get(i);
    }

    public int pageForOffset(int chapter, int offset) {
        pages(chapter);
        List<Integer> starts = startsCache.get(chapter);
        if (starts == null || starts.isEmpty()) return 0;
        int best = 0;
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i) <= offset) best = i;
            else break;
        }
        return best;
    }

    private void pages(int chapter) {
        if (pageCache.containsKey(chapter)) return;
        SpannableStringBuilder text = chapterText(chapter);
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSize);
        StaticLayout full = new StaticLayout(text, paint, layoutW,
                Layout.Alignment.ALIGN_NORMAL, 1.05f, 0f, false);
        List<StaticLayout> pages = new ArrayList<StaticLayout>();
        List<Integer> starts = new ArrayList<Integer>();
        int lineCount = full.getLineCount();
        int firstLine = 0;
        int pageTop = 0;
        for (int line = 0; line < lineCount; line++) {
            int bottom = full.getLineBottom(line);
            if (bottom - pageTop > layoutH && line > firstLine) {
                int from = full.getLineStart(firstLine);
                int to = full.getLineStart(line);
                pages.add(new StaticLayout(text.subSequence(from, to), paint, layoutW,
                        Layout.Alignment.ALIGN_NORMAL, 1.05f, 0f, false));
                starts.add(from);
                firstLine = line;
                pageTop = full.getLineTop(line);
            }
        }
        if (firstLine < lineCount) {
            int from = full.getLineStart(firstLine);
            pages.add(new StaticLayout(text.subSequence(from, text.length()), paint, layoutW,
                    Layout.Alignment.ALIGN_NORMAL, 1.05f, 0f, false));
            starts.add(from);
        }
        if (pages.isEmpty()) {
            pages.add(new StaticLayout("", paint, layoutW,
                    Layout.Alignment.ALIGN_NORMAL, 1.05f, 0f, false));
            starts.add(0);
        }
        pageCache.put(chapter, pages);
        startsCache.put(chapter, starts);
    }

    private SpannableStringBuilder chapterText(int chapter) {
        SpannableStringBuilder cached = textCache.get(chapter);
        if (cached != null) return cached;
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (chapter < 0 || chapter >= spineHrefs.size()) {
            textCache.put(chapter, out);
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
        textCache.put(chapter, out);
        return out;
    }

    private static void readXhtml(InputStream in, SpannableStringBuilder out) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new InputStreamReader(in, "UTF-8"));
        int event = parser.getEventType();
        // Open emphasis spans: [style, start]
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
                            out.setSpan(new android.text.style.RelativeSizeSpan(top[1] / 100f),
                                    start, end,
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
        return s == null ? "" : s.toLowerCase(java.util.Locale.US);
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
        textCache.clear();
        pageCache.clear();
        startsCache.clear();
        try { zip.close(); } catch (IOException ignored) {}
    }

    public File file() {
        return file;
    }
}
