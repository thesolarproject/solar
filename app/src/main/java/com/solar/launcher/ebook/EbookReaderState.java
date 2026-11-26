package com.solar.launcher.ebook;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * 2026-11-26 — Reader session: which book, which spread, and where to resume.
 * Layman: remembers the page you are on and turns pages two at a time.
 * Tech: EPUB pages live inside a chapter (paginated lazily at the view's page size);
 *   PDF pages are document pages. Page is always the left page of the spread, so
 *   two-up shows (page, page+1) and one-up shows page only.
 */
public final class EbookReaderState {
    public static final int GUTTER = 8;
    public static final int HEADER_H = 22;
    public static final int FOOTER_H = 18;
    /** Below this page width two pages are unreadable — fall back to one page. */
    public static final int MIN_TWO_UP_PAGE_W = 150;
    public static final float TEXT_SIZE = 16f;

    private Context app;
    private File file;
    private EpubBook epub;
    private PdfBook pdf;
    private int chapter;
    private int page;
    private boolean twoUp = true;
    private int viewW;
    private int viewH;
    private int pageW = 320;
    private int pageH = 400;
    private boolean open;

    public boolean open(Context ctx, File f) {
        close();
        app = ctx != null ? ctx.getApplicationContext() : null;
        file = f;
        if (EbookLibrary.isPdf(f)) {
            try {
                pdf = PdfBook.open(ctx, f);
            } catch (IOException e) {
                file = null;
                return false;
            }
        } else {
            try {
                epub = EpubBook.open(f, pageW, pageH, TEXT_SIZE);
            } catch (IOException e) {
                file = null;
                return false;
            }
        }
        EbookProgressStore.Progress p = EbookProgressStore.load(app, f);
        chapter = p.chapter;
        page = p.page;
        open = true;
        applyViewLayout();
        clampPage();
        return true;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isPdf() {
        return pdf != null;
    }

    public File file() {
        return file;
    }

    public EpubBook epub() {
        return epub;
    }

    public PdfBook pdf() {
        return pdf;
    }

    /** View size changed (first layout / resize): recompute page geometry and clamp. */
    public void setViewSize(int width, int height) {
        viewW = width;
        viewH = height;
        applyViewLayout();
        clampPage();
    }

    private void applyViewLayout() {
        twoUp = viewW >= 2 * MIN_TWO_UP_PAGE_W + 3 * GUTTER;
        pageW = Math.max(1, twoUp ? (viewW - 3 * GUTTER) / 2 : viewW - 2 * GUTTER);
        pageH = Math.max(1, viewH - HEADER_H - FOOTER_H);
        if (epub != null) epub.setLayout(pageW, pageH, TEXT_SIZE);
    }

    private void clampPage() {
        int count = totalPages();
        if (count <= 0) {
            page = 0;
            return;
        }
        if (page >= count) page = lastSpreadStart(count);
        if (page < 0) page = 0;
    }

    private int step() {
        return twoUp ? 2 : 1;
    }

    private int lastSpreadStart(int count) {
        int s = step();
        return ((count - 1) / s) * s;
    }

    public boolean twoUp() {
        return twoUp;
    }

    public int pageWidth() {
        return pageW;
    }

    public int pageHeight() {
        return pageH;
    }

    public int viewWidth() {
        return viewW;
    }

    public int viewHeight() {
        return viewH;
    }

    /** Total pages: chapter pages for EPUB, document pages for PDF. */
    public int totalPages() {
        if (pdf != null) return pdf.pageCount();
        if (epub != null && chapter < epub.chapterCount()) return epub.pageCount(chapter);
        return 0;
    }

    public int page() {
        return page;
    }

    public int chapter() {
        return chapter;
    }

    /** Page index shown in a slot (0 = left, 1 = right); -1 when the slot is empty. */
    public int pageAt(int slot) {
        int p = page + slot;
        return p < totalPages() ? p : -1;
    }

    public boolean next() {
        if (!open) return false;
        int step = step();
        if (page + step < totalPages()) {
            page += step;
            return true;
        }
        if (epub != null && chapter + 1 < epub.chapterCount()) {
            chapter++;
            page = 0;
            return true;
        }
        // Odd last page in two-up: show it alone rather than flipping backwards.
        if (twoUp && page + 1 < totalPages()) {
            page = totalPages() - 1;
            return true;
        }
        return false;
    }

    public boolean prev() {
        if (!open) return false;
        int step = step();
        if (page - step >= 0) {
            page -= step;
            return true;
        }
        if (epub != null && chapter > 0) {
            chapter--;
            page = lastSpreadStart(epub.pageCount(chapter));
            return true;
        }
        if (page > 0) {
            page = 0;
            return true;
        }
        return false;
    }

    public String title() {
        if (epub != null) return epub.title();
        return file != null ? EbookLibrary.title(file) : "";
    }

    public String chapterTitle() {
        return epub != null ? epub.chapterTitle(chapter) : "";
    }

    /** Save progress; the caller may keep the state object (close releases the document). */
    public void saveProgress() {
        if (!open || file == null) return;
        EbookProgressStore.save(app, file, chapter, page);
    }

    public void close() {
        saveProgress();
        if (epub != null) {
            epub.close();
            epub = null;
        }
        if (pdf != null) {
            pdf.close();
            pdf = null;
        }
        open = false;
    }

    public String positionLabel() {
        int total = totalPages();
        if (total <= 0) return "";
        int shown = twoUp && page + 1 < total ? 2 : 1;
        String pages = shown == 2
                ? String.format(Locale.US, "%d–%d", page + 1, page + 2)
                : String.valueOf(page + 1);
        if (epub != null) {
            return chapterTitle() + " · " + pages + " / " + total;
        }
        return pages + " / " + total;
    }
}
