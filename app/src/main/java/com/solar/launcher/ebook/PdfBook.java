package com.solar.launcher.ebook;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;

import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.File;
import java.io.IOException;

/**
 * 2026-11-26 — PDF pages as bitmaps via PDFium (platform PdfRenderer needs API 21).
 * Layman: turns a PDF page into a picture the reader can draw.
 * Tech: pdfium-android (Apache-2.0, API 14+) renders straight into a Bitmap; callers
 *   must render off the UI thread and close() when the book is closed.
 */
public final class PdfBook {
    private final File file;
    private final PdfiumCore core;
    private final PdfDocument document;
    private final ParcelFileDescriptor fd;
    private final int pageCount;

    private PdfBook(File file, PdfiumCore core, PdfDocument document, ParcelFileDescriptor fd,
                    int pageCount) {
        this.file = file;
        this.core = core;
        this.document = document;
        this.fd = fd;
        this.pageCount = pageCount;
    }

    public static PdfBook open(Context ctx, File file) throws IOException {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            PdfiumCore core = new PdfiumCore(ctx);
            PdfDocument doc = core.newDocument(pfd);
            int pages = core.getPageCount(doc);
            if (pages <= 0) throw new IOException("PDF has no pages");
            return new PdfBook(file, core, doc, pfd, pages);
        } catch (IOException e) {
            if (pfd != null) try { pfd.close(); } catch (IOException ignored) {}
            throw e;
        } catch (RuntimeException e) {
            if (pfd != null) try { pfd.close(); } catch (IOException ignored) {}
            throw new IOException("Could not open PDF", e);
        }
    }

    public int pageCount() {
        return pageCount;
    }

    public File file() {
        return file;
    }

    /** Renders one page into a bitmap that fits maxWidth x maxHeight. Blocking. */
    public Bitmap renderPage(int index, int maxWidth, int maxHeight) {
        if (index < 0 || index >= pageCount) return null;
        int pageW = Math.max(1, core.getPageWidthPoint(document, index));
        int pageH = Math.max(1, core.getPageHeightPoint(document, index));
        float scale = Math.min(maxWidth / (float) pageW, maxHeight / (float) pageH);
        int w = Math.max(1, Math.round(pageW * scale));
        int h = Math.max(1, Math.round(pageH * scale));
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            core.openPage(document, index);
            // White paper first: PDFium leaves untouched pixels transparent.
            bmp.eraseColor(0xFFFFFFFF);
            core.renderPageBitmap(document, bmp, index, 0, 0, w, h);
            // pdfium-android 1.9.0 has no closePage(document); pages are released by
            // the next openPage() / closeDocument().
            return bmp;
        } catch (Throwable t) {
            if (bmp != null) bmp.recycle();
            return null;
        }
    }

    public void close() {
        try { core.closeDocument(document); } catch (Throwable ignored) {}
        try { fd.close(); } catch (IOException ignored) {}
    }
}
