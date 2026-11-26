package com.solar.launcher.ebook;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * 2026-11-26 — Reader session: which book, which spread, and where to resume.
 * Layman: remembers the page you are on and turns pages two at a time.
 * Tech: EPUB pages live inside a chapter (cut on a worker thread at the view's page size);
 *   PDF pages are document pages. Page is always the left page of the spread, so
 *   two-up shows (page, page+1) and one-up shows page only.
 *
 * 2026-11-26 (reader rework) — Spreads always start on an even page, and reaching the end
 *   of a chapter no longer parks on a lone odd page (that made the next Back jump two
 *   spreads). A chapter that is still formatting reports isLoading() and the view paints
 *   "Formatting…" instead of stalling the UI thread.
 *   Was: next() had an odd-last-page branch and clampPage() ignored page parity.
 *   Reversal: restore those two lines.
 */
public final class EbookReaderState {
    public static final int GUTTER = 8;
    public static final int HEADER_H = 22;
    public static final int FOOTER_H = 18;
    /** Inner padding of a paper page — the EPUB layout is built to fit inside it. */
    public static final int PAGE_PAD_X = 4;
    public static final int PAGE_PAD_Y = 2;
    /** Below this page width two pages are unreadable — fall back to one page. */
    public static final int MIN_TWO_UP_PAGE_W = 150;
    public static final float TEXT_SIZE = 16f;

    /** Repaint hook for the view (page ready, resize, turn). */
    public interface Listener {
        void onReaderChanged();
    }

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
    /** Backwards into a chapter that is still formatting: land on its last spread. */
    private boolean landLastSpread;
    private Listener listener;

    public void setListener(Listener l) {
        listener = l;
    }

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
            attachEpub(epub);
        }
        EbookProgressStore.Progress p = EbookProgressStore.load(app, f);
        chapter = p.chapter;
        page = p.page;
        landLastSpread = false;
        open = true;
        applyViewLayout();
        clampPage();
        requestChapter();
        return true;
    }

    private void attachEpub(final EpubBook book) {
        book.setReadyListener(new EpubBook.Ready() {
            @Override
            public void onPagesReady(int readyChapter) {
                onChapterReady(book, readyChapter);
            }
        });
    }

    /** Main thread: a chapter just finished formatting. */
    private void onChapterReady(EpubBook book, int readyChapter) {
        if (!open || book != epub) return;
        if (readyChapter == chapter) {
            if (landLastSpread) {
                landLastSpread = false;
                page = lastSpreadStart(totalPages());
            }
            clampPage();
        }
        notifyChanged();
        // Keep one chapter ahead formatted so the next turn is instant.
        if (readyChapter == chapter && chapter + 1 < book.chapterCount()) {
            book.requestPages(chapter + 1);
        }
    }

    private void notifyChanged() {
        Listener l = listener;
        if (l != null) l.onReaderChanged();
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isPdf() {
        return pdf != null;
    }

    /** True while the current EPUB chapter is still being cut into pages. */
    public boolean isLoading() {
        return epub != null && !epub.pagesReady(chapter);
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
        if (width == viewW && height == viewH) return;
        viewW = width;
        viewH = height;
        applyViewLayout();
        clampPage();
        requestChapter();
        notifyChanged();
    }

    private void requestChapter() {
        if (epub != null) epub.requestPages(chapter);
    }

    private void applyViewLayout() {
        twoUp = viewW >= 2 * MIN_TWO_UP_PAGE_W + 3 * GUTTER;
        pageW = Math.max(1, twoUp ? (viewW - 3 * GUTTER) / 2 : viewW - 2 * GUTTER);
        pageH = Math.max(1, viewH - HEADER_H - FOOTER_H);
        if (epub != null) {
            epub.setLayout(Math.max(1, pageW - 2 * PAGE_PAD_X),
                    Math.max(1, pageH - 2 * PAGE_PAD_Y), TEXT_SIZE);
        }
    }

    private void clampPage() {
        int count = totalPages();
        if (count <= 0) {
            // Still formatting: keep the restored page instead of resetting to the start.
            if (page < 0) page = 0;
            return;
        }
        if (page >= count) page = lastSpreadStart(count);
        // 2026-11-26 — a spread always begins on a left page.
        if (twoUp) page -= page % 2;
        if (page < 0) page = 0;
    }

    private int step() {
        return twoUp ? 2 : 1;
    }

    private int lastSpreadStart(int count) {
        if (count <= 0) return 0;
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

    /** Total pages: chapter pages for EPUB, document pages for PDF. 0 while formatting. */
    public int totalPages() {
        if (pdf != null) return pdf.pageCount();
        if (epub != null) return epub.pageCount(chapter);
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
        if (isLoading()) return false;
        int step = step();
        if (page + step < totalPages()) {
            page += step;
            return true;
        }
        if (epub != null && chapter + 1 < epub.chapterCount()) {
            chapter++;
            page = 0;
            landLastSpread = false;
            requestChapter();
            return true;
        }
        return false;
    }

    public boolean prev() {
        if (!open) return false;
        if (isLoading()) return false;
        int step = step();
        if (page - step >= 0) {
            page -= step;
            return true;
        }
        if (epub != null && chapter > 0) {
            chapter--;
            page = 0;
            if (epub.pagesReady(chapter)) {
                landLastSpread = false;
                page = lastSpreadStart(totalPages());
            } else {
                landLastSpread = true;
                requestChapter();
            }
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

    /**
     * Save progress; the caller may keep the state object (close releases the document).
     * 2026-11-26 — apply() (queued write) instead of commit(): a synchronous disk write on
     * every page turn was stuttering the reader.
     */
    public void saveProgress() {
        if (!open || file == null) return;
        EbookProgressStore.save(app, file, chapter, page);
    }

    public void close() {
        saveProgress();
        open = false;
        landLastSpread = false;
        EpubBook book = epub;
        epub = null;
        if (book != null) {
            book.setReadyListener(null);
            book.close();
        }
        if (pdf != null) {
            pdf.close();
            pdf = null;
        }
    }

    public String positionLabel() {
        if (isLoading()) {
            return chapterTitle() + " · …";
        }
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
