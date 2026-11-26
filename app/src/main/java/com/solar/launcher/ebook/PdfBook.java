package com.solar.launcher.ebook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
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
    /** Pages render at this multiple of the box and are filtered down when stretched. */
    private static final int PDF_SUPERSAMPLE = 2;

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

    /**
     * Renders one page into a bitmap of exactly width x height, filling the box. Blocking.
     *
     * 2026-11-26 — the page box is taller than the sheet's own proportions, so the sheet is
     * rendered at its own ratio first (a box of another ratio can letterbox or distort,
     * depending on the PDFium build) and then scaled up in equal parts to the box in
     * stretchPage(). The result keeps the full page width — "good weight" — and gives it the
     * box's height, which is the part the letterboxed version was missing.
     *
     * Portrait sheets fill the box; landscape sheets are rotated upright and fitted instead
     * (stretching those into a tall box would flatten them by ~3x).
     */
    public Bitmap renderPage(int index, int width, int height) {
        if (index < 0 || index >= pageCount) return null;
        int pageW = Math.max(1, core.getPageWidthPoint(document, index));
        int pageH = Math.max(1, core.getPageHeightPoint(document, index));
        int outW = Math.max(1, width);
        int outH = Math.max(1, height);
        // Render at PDF_SUPERSAMPLE times the box and filter down: the stretch magnifies the
        // sheet by ~1.5x vertically, and the downscale keeps the stems from looking blocky.
        int renderW = Math.min(outW * PDF_SUPERSAMPLE, 1600);
        int renderH = Math.min(Math.max(1, Math.round(renderW * pageH / (float) pageW)), 2400);
        boolean landscape = pageW > pageH;
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.RGB_565);
            core.openPage(document, index);
            // White paper first: PDFium leaves untouched pixels transparent.
            bmp.eraseColor(0xFFFFFFFF);
            core.renderPageBitmap(document, bmp, index, 0, 0, renderW, renderH);
            // pdfium-android 1.9.0 has no closePage(document); pages are released by
            // the next openPage() / closeDocument().
            return landscape ? rotateIntoBox(bmp, outW, outH) : stretchPage(bmp, outW, outH);
        } catch (Throwable t) {
            if (bmp != null) bmp.recycle();
            return null;
        }
    }

    /**
     * Turns a landscape sheet a quarter turn clockwise and centres it, upright and at its own
     * proportions, inside width x height. Blocking.
     * The empty part of the box is painted the reader's black, so the page reads as a page
     * standing in the column instead of a white slab with a small page inside it.
     * Rotating clockwise puts the sheet's top edge on the right: tilt the device left to read.
     */
    private static Bitmap rotateIntoBox(Bitmap src, int width, int height) {
        Bitmap out = null;
        try {
            out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(0xFF000000);
            float scale = Math.min(width / (float) src.getHeight(),
                    height / (float) src.getWidth());
            float w = src.getHeight() * scale; // footprint after the quarter turn
            float h = src.getWidth() * scale;
            Matrix m = new Matrix();
            m.setRotate(90f);
            m.postScale(scale, scale);
            m.postTranslate((width + w) / 2f, (height - h) / 2f);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setAntiAlias(true);
            canvas.drawBitmap(src, m, paint);
            src.recycle();
            return out;
        } catch (Throwable t) {
            if (out != null) out.recycle();
            return src;
        }
    }

    /**
     * Scales a rendered sheet to exactly width x height. Blocking.
     * The bitmap is the whole page at the page's own proportions, so drawing it into the box
     * stretches it to the box's shape — the same page width, more height — with filtering.
     */
    private static Bitmap stretchPage(Bitmap src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height) return src;
        Bitmap out = null;
        try {
            out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(0xFFFFFFFF);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setAntiAlias(true);
            canvas.drawBitmap(src, null, new RectF(0, 0, width, height), paint);
            src.recycle();
            return out;
        } catch (Throwable t) {
            if (out != null) out.recycle();
            return src;
        }
    }

    public void close() {
        try { core.closeDocument(document); } catch (Throwable ignored) {}
        try { fd.close(); } catch (IOException ignored) {}
    }
}
