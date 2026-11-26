package com.solar.launcher.ebook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 2026-11-26 — Two-page book surface (single page when the panel is too narrow).
 * Layman: left/right pages; tap a side, or the side buttons, to turn.
 * Tech: EPUB draws StaticLayouts, PDF draws PDFium bitmaps rendered off-thread.
 *   Rendering is generation-guarded so a fast page turn cannot paint an old spread.
 */
public final class EbookReaderView extends View {
    private EbookReaderState state;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paper = new Paint();
    private Bitmap leftBitmap;
    private Bitmap rightBitmap;
    private int renderGen;
    /** Spread+size already sent to PDFium — stops onDraw from queueing the same render twice. */
    private String pendingPdfKey;
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
        state = s;
        releaseBitmaps();
        if (state != null) state.setViewSize(getWidth(), getHeight());
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
        if (state != null) {
            state.setViewSize(w, h);
            releaseBitmaps();
            invalidate();
        }
    }

    /** Page turn from hard buttons / wheel / touch. */
    public boolean turn(boolean next) {
        if (state == null || !state.isOpen()) return false;
        boolean moved = next ? state.next() : state.prev();
        if (!moved) return false;
        state.saveProgress();
        releaseBitmaps();
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
            canvas.drawText("Book could not be opened", 12f, 40f, paint);
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
        for (int slot = 0; slot <= 1; slot++) {
            int pageIndex = state.pageAt(slot);
            if (pageIndex < 0) continue;
            Layout layout = state.epub().pageLayout(state.chapter(), pageIndex);
            if (layout == null) continue;
            int x = pageLeftX(slot);
            int top = contentTop();
            canvas.drawRect(x, top, x + state.pageWidth(), top + state.pageHeight(), paper);
            canvas.save();
            canvas.translate(x + 4f, top + 2f);
            canvas.clipRect(0f, 0f, state.pageWidth() - 8, state.pageHeight() - 4);
            paint.setColor(0xFF111111);
            layout.getPaint().setColor(0xFF111111);
            layout.draw(canvas);
            canvas.restore();
        }
    }

    private void drawPdf(Canvas canvas) {
        if (leftBitmap == null || (state.twoUp() && rightBitmap == null
                && state.pageAt(1) >= 0)) {
            requestPdf();
        }
        if (leftBitmap != null && !leftBitmap.isRecycled()) {
            drawPdfBitmap(canvas, leftBitmap, 0);
        }
        if (rightBitmap != null && !rightBitmap.isRecycled()) {
            drawPdfBitmap(canvas, rightBitmap, 1);
        }
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

    private void requestPdf() {
        final EbookReaderState snapshot = state;
        if (snapshot == null || snapshot.pdf() == null) return;
        final int leftPage = snapshot.pageAt(0);
        final int rightPage = snapshot.pageAt(1);
        final int w = snapshot.pageWidth();
        final int h = snapshot.pageHeight();
        String key = leftPage + ":" + rightPage + ":" + w + "x" + h;
        if (key.equals(pendingPdfKey)) return;
        pendingPdfKey = key;
        final int gen = ++renderGen;
        final PdfBook source = snapshot.pdf();
        if (source == null) return;
        if (leftPage >= 0 && leftBitmap == null) {
            render(source, leftPage, w, h, gen, true);
        }
        if (rightPage >= 0 && rightBitmap == null) {
            render(source, rightPage, w, h, gen, false);
        }
    }

    private void render(final PdfBook source, final int page,
                        final int w, final int h, final int gen, final boolean left) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = source.renderPage(page, w, h);
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != renderGen || state == null) {
                            if (bmp != null) bmp.recycle();
                            return;
                        }
                        if (left) leftBitmap = bmp;
                        else rightBitmap = bmp;
                        invalidate();
                    }
                });
            }
        }).start();
    }

    private void releaseBitmaps() {
        renderGen++;
        pendingPdfKey = null;
        if (leftBitmap != null && !leftBitmap.isRecycled()) leftBitmap.recycle();
        if (rightBitmap != null && !rightBitmap.isRecycled()) rightBitmap.recycle();
        leftBitmap = null;
        rightBitmap = null;
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
