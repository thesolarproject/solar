package com.solar.launcher.ebook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.solar.launcher.R;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 2026-11-26 — Two-page book surface (single page when the panel is too narrow).
 * Layman: left/right pages; tap a side, or the side buttons, to turn.
 * Tech: EPUB paints windows into one chapter layout, PDF draws PDFium bitmaps.
 *
 * 2026-11-26 (reader rework) — PDF pages render on ONE worker thread with a small bitmap
 *   cache and the following spread prefetched: PDFium is not thread safe, and the old code
 *   started a fresh thread per page (two at once) and re-rendered every turn.
 *   Was: leftBitmap/rightBitmap + new Thread per page, recycled on every turn.
 *   Reversal: restore that pair of fields and the per-page Thread.
 */
public final class EbookReaderView extends View {
    /** Rendered PDF pages kept around (current spread + the one ahead + the one behind). */
    private static final int PDF_CACHE_MAX = 6;

    private EbookReaderState state;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paper = new Paint();
    private final Map<String, Bitmap> pdfCache =
            new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    if (size() > PDF_CACHE_MAX) {
                        Bitmap b = eldest.getValue();
                        if (b != null && !b.isRecycled()) b.recycle();
                        return true;
                    }
                    return false;
                }
            };
    private final Set<String> pdfQueued = new HashSet<String>();
    private ExecutorService pdfWorker;
    private int renderGen;
    private boolean touchTurnEnabled = true;

    public EbookReaderView(Context ctx) {
        super(ctx);
        init();
    }

    public EbookReaderView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        setBackgroundColor(Color.BLACK);
        paper.setColor(0xFFF7F7F2);
        paint.setTextSize(13f);
        paint.setColor(0xFF9A9A9A);
    }

    public void setReader(EbookReaderState s) {
        if (state != null) state.setListener(null);
        state = s;
        releaseBitmaps();
        if (state != null) {
            state.setListener(new EbookReaderState.Listener() {
                @Override
                public void onReaderChanged() {
                    invalidate();
                }
            });
            if (getWidth() > 0 && getHeight() > 0) {
                state.setViewSize(getWidth(), getHeight());
            }
        }
        invalidate();
    }

    public EbookReaderState reader() {
        return state;
    }

    public void setTouchTurnEnabled(boolean enabled) {
        touchTurnEnabled = enabled;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (state != null && w > 0 && h > 0) {
            state.setViewSize(w, h);
            releaseBitmaps();
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseBitmaps();
        super.onDetachedFromWindow();
    }

    /** Page turn from hard buttons / wheel / touch. */
    public boolean turn(boolean next) {
        if (state == null || !state.isOpen()) return false;
        boolean moved = next ? state.next() : state.prev();
        if (!moved) return false;
        state.saveProgress();
        invalidate();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!touchTurnEnabled || state == null || !state.isOpen()) return false;
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        return turn(event.getX() >= getWidth() / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (state == null || !state.isOpen()) {
            paint.setColor(0xFF9A9A9A);
            canvas.drawText(getContext().getString(R.string.ebook_open_failed), 12f, 40f, paint);
            return;
        }
        drawHeader(canvas);
        if (state.isPdf()) {
            drawPdf(canvas);
        } else {
            drawEpub(canvas);
        }
        drawFooter(canvas);
    }

    private void drawHeader(Canvas canvas) {
        paint.setColor(0xFF9A9A9A);
        paint.setTextSize(11f);
        String title = state.title();
        canvas.drawText(ellipsize(title, getWidth() - 12), 6f, 15f, paint);
        paint.setColor(0xFF333333);
        canvas.drawRect(0f, EbookReaderState.HEADER_H - 1f, getWidth(),
                EbookReaderState.HEADER_H, paint);
    }

    private void drawFooter(Canvas canvas) {
        paint.setColor(0xFF777777);
        paint.setTextSize(11f);
        String label = state.positionLabel();
        float width = paint.measureText(label);
        canvas.drawText(label, (getWidth() - width) / 2f,
                getHeight() - EbookReaderState.FOOTER_H + 14f, paint);
    }

    private int contentTop() {
        return EbookReaderState.HEADER_H;
    }

    private int pageLeftX(int slot) {
        if (!state.twoUp()) return EbookReaderState.GUTTER;
        return slot == 0
                ? EbookReaderState.GUTTER
                : 2 * EbookReaderState.GUTTER + state.pageWidth();
    }

    private void drawEpub(Canvas canvas) {
        if (state.isLoading()) {
            drawCentered(canvas, getContext().getString(R.string.ebook_formatting));
            return;
        }
        EpubBook book = state.epub();
        if (book == null) {
            drawCentered(canvas, getContext().getString(R.string.ebook_open_failed));
            return;
        }
        for (int slot = 0; slot <= 1; slot++) {
            int pageIndex = state.pageAt(slot);
            if (pageIndex < 0) continue;
            int x = pageLeftX(slot);
            int top = contentTop();
            canvas.drawRect(x, top, x + state.pageWidth(), top + state.pageHeight(), paper);
            book.drawPage(canvas, state.chapter(), pageIndex,
                    x + EbookReaderState.PAGE_PAD_X, top + EbookReaderState.PAGE_PAD_Y,
                    state.pageWidth() - 2 * EbookReaderState.PAGE_PAD_X,
                    state.pageHeight() - 2 * EbookReaderState.PAGE_PAD_Y);
        }
    }

    private void drawPdf(Canvas canvas) {
        for (int slot = 0; slot <= 1; slot++) {
            int pageIndex = state.pageAt(slot);
            if (pageIndex < 0) continue;
            int x = pageLeftX(slot);
            int top = contentTop();
            canvas.drawRect(x, top, x + state.pageWidth(), top + state.pageHeight(), paper);
            Bitmap bmp = pdfCache.get(pdfKey(pageIndex));
            if (bmp == null || bmp.isRecycled()) {
                requestPdfPage(pageIndex);
                continue;
            }
            drawPdfBitmap(canvas, bmp, slot);
        }
        // Warm the spread after this one so the next turn paints immediately.
        int nextFirst = state.page() + (state.twoUp() ? 2 : 1);
        requestPdfPage(nextFirst);
        if (state.twoUp()) requestPdfPage(nextFirst + 1);
    }

    private void drawPdfBitmap(Canvas canvas, Bitmap bmp, int slot) {
        int x = pageLeftX(slot);
        int top = contentTop();
        float scale = Math.min(1f, Math.min(
                (float) state.pageWidth() / bmp.getWidth(),
                (float) state.pageHeight() / bmp.getHeight()));
        float w = bmp.getWidth() * scale;
        float h = bmp.getHeight() * scale;
        float dx = x + (state.pageWidth() - w) / 2f;
        float dy = top + (state.pageHeight() - h) / 2f;
        canvas.drawBitmap(bmp, dx, dy, null);
    }

    private String pdfKey(int page) {
        return page + "@" + state.pageWidth() + "x" + state.pageHeight();
    }

    private void requestPdfPage(final int page) {
        final EbookReaderState snapshot = state;
        if (snapshot == null || snapshot.pdf() == null) return;
        final PdfBook source = snapshot.pdf();
        if (page < 0 || page >= source.pageCount()) return;
        final String key = pdfKey(page);
        if (pdfCache.containsKey(key) || pdfQueued.contains(key)) return;
        pdfQueued.add(key);
        final int w = snapshot.pageWidth();
        final int h = snapshot.pageHeight();
        final int gen = renderGen;
        pdfWorker().execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = source.renderPage(page, w, h);
                post(new Runnable() {
                    @Override
                    public void run() {
                        pdfQueued.remove(key);
                        if (bmp == null) return;
                        if (gen != renderGen || state == null || state.pdf() != source) {
                            bmp.recycle();
                            return;
                        }
                        pdfCache.put(key, bmp);
                        invalidate();
                    }
                });
            }
        });
    }

    private ExecutorService pdfWorker() {
        if (pdfWorker == null) {
            pdfWorker = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ebook-pdf");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return pdfWorker;
    }

    private void releaseBitmaps() {
        renderGen++;
        for (Bitmap b : pdfCache.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        pdfCache.clear();
    }

    private void drawCentered(Canvas canvas, String text) {
        paint.setColor(0xFF9A9A9A);
        paint.setTextSize(13f);
        float w = paint.measureText(text);
        canvas.drawText(text, (getWidth() - w) / 2f, getHeight() / 2f, paint);
    }

    private String ellipsize(String text, float maxWidth) {
        if (text == null) return "";
        if (paint.measureText(text) <= maxWidth) return text;
        String out = text;
        while (out.length() > 3 && paint.measureText(out + "…") > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}
