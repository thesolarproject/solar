package com.solar.launcher.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.solar.launcher.theme.ThemeManager;

import java.util.List;

/**
 * Apple Cover Flow renderer — 45° carousel, reflections, in-place flip to tracklist.
 */
public final class FlowView extends View {

    public interface Callback {
        void requestCover(int itemIndex, String coverKey, FlowItem item);
        void onFocusIndexChanged(int index);
        void onBackRowSelected(FlowScreenHost.FlowBackRow row, int index);
        void onFlipDismissed();
    }

    private final FlowEngine engine = new FlowEngine();
    private final FlowFlipController flip = new FlowFlipController();
    private final Paint coverPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflectionPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint backPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backRowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backSelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Camera camera = new Camera();
    private final int[] drawOrder = new int[CoverFlowLayout.SIDE_SLIDES * 2 + 1];
    private FlowCoverCache cache;
    private List<FlowItem> items = java.util.Collections.emptyList();
    private Callback callback;
    private int reflectionTint = 0x44FFFFFF;
    private int lastNotifiedFocus = -1;
    private float viewW;
    private float viewH;
    private float titleAlpha = 1f;
    private int titleFadeFrom = -1;
    private int titleFadeTo = -1;
    private long titleFadeStartMs;
    private static final int TITLE_FADE_MS = 150;

    private final Runnable animTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            engine.tick(now);
            boolean scrolling = engine.isAnimating();
            boolean flipping = flip.tick(now);
            tickTitleFade(now);
            notifyFocusIfChanged();
            invalidate();
            if (scrolling || flipping || flip.getState() == FlowFlipController.STATE_FLIP_TO_BACK
                    || flip.getState() == FlowFlipController.STATE_FLIP_TO_FRONT) {
                postDelayed(this, 16);
            }
        }
    };

    public FlowView(Context context) {
        this(context, null);
    }

    public FlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initFlowView();
    }

    private void initFlowView() {
        coverPaint.setFilterBitmap(false);
        reflectionPaint.setFilterBitmap(false);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(ThemeManager.getCustomFont());
        backPaint.setColor(0xCC1A1A22);
        backRowPaint.setColor(0xFFFFFFFF);
        backRowPaint.setTextAlign(Paint.Align.LEFT);
        backSelPaint.setColor(0xFF0066CC);
        setBackgroundColor(0xFF000000);
        setFocusable(true);
        reflectionTint = ThemeManager.getListButtonNormalBg() & 0x00FFFFFF | 0x55000000;
    }

    public FlowEngine engine() {
        return engine;
    }

    public FlowFlipController flipController() {
        return flip;
    }

    public void setCoverCache(FlowCoverCache cache) {
        this.cache = cache;
    }

    public void setItems(List<FlowItem> items) {
        this.items = items != null ? items : java.util.Collections.<FlowItem>emptyList();
        engine.setItemCount(this.items.size());
        lastNotifiedFocus = -1;
        flip.reset();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setDebugTheme(boolean on) {
        reflectionTint = on
                ? (ThemeManager.getStatusBarBackgroundColor() & 0x00FFFFFF) | 0x55000000
                : (ThemeManager.getListButtonNormalBg() & 0x00FFFFFF) | 0x55000000;
        invalidate();
    }

    public void resetFlip() {
        flip.reset();
        invalidate();
    }

    public FlowItem itemAt(int index) {
        if (index < 0 || index >= items.size()) return null;
        return items.get(index);
    }

    public boolean scrollWheel(int delta) {
        if (flip.getState() == FlowFlipController.STATE_BACK) {
            boolean moved = flip.scrollBackBy(delta);
            if (!moved) return false;
            invalidate();
            return true;
        }
        if (flip.blocksCarouselScroll()) return false;
        engine.setViewport(viewW, viewH);
        boolean moved = engine.scrollByReturningMoved(delta);
        if (moved) {
            prefetchAround(engine.getFocusIndex());
            invalidate();
            postAnimTick();
        }
        return moved;
    }

    public void flipToBack(String title, String subtitle, List<FlowScreenHost.FlowBackRow> rows) {
        flip.setBackContent(title, subtitle, rows);
        flip.startFlipToBack(null);
        invalidate();
        postAnimTick();
    }

    public boolean flipToFront() {
        if (!flip.isFlippedOrFlipping()) return false;
        if (flip.getState() == FlowFlipController.STATE_BACK
                || flip.getState() == FlowFlipController.STATE_FLIP_TO_BACK) {
            flip.startFlipToFront(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) callback.onFlipDismissed();
                }
            });
            invalidate();
            postAnimTick();
            return true;
        }
        return false;
    }

    public boolean isFlipped() {
        return flip.getState() == FlowFlipController.STATE_BACK
                || flip.getState() == FlowFlipController.STATE_FLIP_TO_BACK;
    }

    public boolean handleCenterOk() {
        if (flip.getState() == FlowFlipController.STATE_BACK) {
            FlowScreenHost.FlowBackRow row = flip.selectedRow();
            if (row != null && callback != null) {
                callback.onBackRowSelected(row, flip.backIndex());
            }
            return true;
        }
        return false;
    }

    /** Screen rect of center cover for handoff animation. */
    public RectF getCenterCoverScreenRect() {
        FlowEngine.SlotTransform t = engine.centerSlotTransform(viewW, viewH);
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        return new RectF(
                loc[0] + t.centerX - t.width * 0.5f,
                loc[1] + t.centerY - t.height * 0.5f,
                loc[0] + t.centerX + t.width * 0.5f,
                loc[1] + t.centerY + t.height * 0.5f);
    }

    public Bitmap getCenterCoverBitmap() {
        int focus = engine.getFocusIndex();
        if (focus < 0 || focus >= items.size() || cache == null) return null;
        return cache.get(items.get(focus).coverKey);
    }

    public void prefetchAround(int center) {
        if (callback == null || cache == null) return;
        int thumb = thumbPx();
        for (int d = -2; d <= 2; d++) {
            int idx = center + d;
            if (idx < 0 || idx >= items.size()) continue;
            FlowItem item = items.get(idx);
            if (cache.get(item.coverKey) == null) {
                callback.requestCover(idx, item.coverKey, item);
            }
        }
    }

    private int thumbPx() {
        if (cache != null) return cache.thumbSizePx(viewW, viewH);
        return CoverFlowLayout.metricsForViewport(viewW, viewH).thumbSizePx();
    }

    private void notifyFocusIfChanged() {
        int focus = engine.getFocusIndex();
        if (focus != lastNotifiedFocus) {
            if (lastNotifiedFocus >= 0 && focus != lastNotifiedFocus) {
                titleFadeFrom = lastNotifiedFocus;
                titleFadeTo = focus;
                titleFadeStartMs = System.currentTimeMillis();
                titleAlpha = 0f;
            }
            lastNotifiedFocus = focus;
            prefetchAround(focus);
            if (callback != null) callback.onFocusIndexChanged(focus);
        }
    }

    private void tickTitleFade(long nowMs) {
        if (titleFadeFrom < 0) {
            titleAlpha = 1f;
            return;
        }
        float t = (nowMs - titleFadeStartMs) / (float) TITLE_FADE_MS;
        if (t >= 1f) {
            titleFadeFrom = -1;
            titleAlpha = 1f;
        } else {
            titleAlpha = t;
        }
    }

    private void postAnimTick() {
        removeCallbacks(animTick);
        post(animTick);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;
        engine.setViewport(viewW, viewH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        viewW = w;
        viewH = h;
        engine.setViewport(w, h);
        if (w <= 0 || h <= 0 || items.isEmpty()) {
            drawEmptyHint(canvas, w, h);
            return;
        }

        CoverFlowLayout.Metrics metrics = CoverFlowLayout.metricsForViewport(w, h);
        float sideDim = flip.sideDimAlpha();
        int count = engine.fillDrawOrder(drawOrder);
        int centerIdx = engine.getFocusIndex();
        float flipP = flip.flipProgress();

        for (int k = 0; k < count; k++) {
            int idx = drawOrder[k];
            if (idx == centerIdx && flip.isFlippedOrFlipping()) {
                drawFlippingCenter(canvas, idx, metrics, flipP);
            } else {
                drawSlot(canvas, idx, metrics, idx == centerIdx ? 1f : sideDim);
            }
        }

        if (!flip.isFlippedOrFlipping() || flip.getState() == FlowFlipController.STATE_FLIP_TO_FRONT) {
            int focus = engine.getFocusIndex();
            if (focus >= 0 && focus < items.size()) {
                FlowItem item = items.get(focus);
                drawTitle(canvas, item.title, item.subtitle, w, h, metrics);
            }
        }
    }

    private void drawSlot(Canvas canvas, int index, CoverFlowLayout.Metrics metrics, float alphaMul) {
        FlowEngine.SlotTransform t = engine.slotTransform(index, metrics.viewW, metrics.viewH, 0f);
        if (t.alpha <= 0.01f) return;
        t.alpha *= alphaMul;
        drawCoverAt(canvas, index, t, metrics, 0f, false);
    }

    private void drawFlippingCenter(Canvas canvas, int index, CoverFlowLayout.Metrics metrics, float flipP) {
        FlowEngine.SlotTransform t = engine.centerSlotTransform(metrics.viewW, metrics.viewH);
        float angle = flipP * 180f;
        boolean showBack = angle >= 90f;
        float drawAngle = showBack ? angle - 180f : angle;
        if (showBack) {
            drawCoverAt(canvas, index, t, metrics, drawAngle, true);
        } else {
            drawCoverAt(canvas, index, t, metrics, drawAngle, false);
        }
    }

    private void drawCoverAt(Canvas canvas, int index, FlowEngine.SlotTransform t,
            CoverFlowLayout.Metrics metrics, float extraRotY, boolean backFace) {
        FlowItem item = items.get(index);
        Bitmap bmp = cache != null ? cache.get(item.coverKey) : null;
        if (bmp == null && callback != null && !backFace) {
            callback.requestCover(index, item.coverKey, item);
        }

        float left = t.centerX - t.width * 0.5f;
        float top = t.centerY - t.height * 0.5f;
        RectF rect = new RectF(left, top, left + t.width, top + t.height);

        canvas.save();
        camera.save();
        camera.translate(0, 0, t.zDepth);
        camera.rotateY(t.rotationYDeg + extraRotY);
        matrix.reset();
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-t.centerX, -t.centerY);
        matrix.postTranslate(t.centerX, t.centerY);
        canvas.concat(matrix);

        if (backFace) {
            drawBackFace(canvas, rect);
        } else {
            coverPaint.setAlpha((int) (255 * t.alpha));
            if (bmp != null && !bmp.isRecycled()) {
                canvas.drawBitmap(bmp, null, rect, coverPaint);
                drawReflection(canvas, bmp, rect, t.alpha, metrics);
            } else {
                Paint placeholder = new Paint();
                placeholder.setColor(ThemeManager.getListButtonNormalBg() | 0xFF000000);
                placeholder.setAlpha((int) (255 * t.alpha));
                canvas.drawRect(rect, placeholder);
            }
        }
        canvas.restore();
        coverPaint.setAlpha(255);
    }

    private void drawBackFace(Canvas canvas, RectF rect) {
        canvas.drawRect(rect, backPaint);
        float pad = rect.width() * 0.06f;
        float headerH = rect.height() * 0.18f;
        RectF header = new RectF(rect.left, rect.top, rect.right, rect.top + headerH);
        backSelPaint.setColor(0xFF003366);
        canvas.drawRect(header, backSelPaint);
        backSelPaint.setColor(0xFF0066CC);

        backRowPaint.setTextSize(headerH * 0.38f);
        backRowPaint.setTypeface(Typeface.create(ThemeManager.getCustomFont(), Typeface.BOLD));
        String title = flip.headerTitle();
        if (title.length() > 28) title = title.substring(0, 27) + "…";
        canvas.drawText(title, rect.left + pad, rect.top + headerH * 0.55f, backRowPaint);

        if (flip.headerSubtitle() != null && !flip.headerSubtitle().isEmpty()) {
            backRowPaint.setTextSize(headerH * 0.28f);
            backRowPaint.setTypeface(Typeface.create(ThemeManager.getCustomFont(), Typeface.NORMAL));
            canvas.drawText(flip.headerSubtitle(), rect.left + pad, rect.top + headerH * 0.85f, backRowPaint);
        }

        float rowH = (rect.height() - headerH) / Math.max(1, flip.visibleBackRowCount());
        int start = flip.visibleBackRowStart();
        int count = flip.visibleBackRowCount();
        List<FlowScreenHost.FlowBackRow> rows = flip.backRows();
        backRowPaint.setTypeface(Typeface.create(ThemeManager.getCustomFont(), Typeface.NORMAL));
        for (int i = 0; i < count; i++) {
            int rowIdx = start + i;
            if (rowIdx >= rows.size()) break;
            FlowScreenHost.FlowBackRow row = rows.get(rowIdx);
            float y0 = rect.top + headerH + i * rowH;
            RectF rowRect = new RectF(rect.left, y0, rect.right, y0 + rowH);
            if (rowIdx == flip.backIndex()) {
                canvas.drawRect(rowRect, backSelPaint);
                backRowPaint.setColor(0xFFFFFFFF);
            } else {
                backRowPaint.setColor(0xFFCCCCCC);
            }
            backRowPaint.setTextSize(rowH * 0.42f);
            String line = row.title != null ? row.title : "";
            if (line.length() > 32) line = line.substring(0, 31) + "…";
            canvas.drawText(line, rect.left + pad, y0 + rowH * 0.65f, backRowPaint);
        }
        backRowPaint.setColor(0xFFFFFFFF);
    }

    private void drawReflection(Canvas canvas, Bitmap bmp, RectF coverRect, float alpha,
            CoverFlowLayout.Metrics metrics) {
        float refH = metrics.reflectHeight;
        if (refH <= 0f) return;
        RectF refRect = new RectF(coverRect.left, coverRect.bottom,
                coverRect.right, coverRect.bottom + refH);

        int[] table = metrics.reflectTable;
        int topA = table.length > 0 ? table[0] : 768;
        int topColor = ((topA * Math.round(alpha * 256) + 129) >> 8);
        topColor = (topColor << 24) | (reflectionTint & 0x00FFFFFF);

        canvas.save();
        canvas.clipRect(refRect);
        matrix.reset();
        matrix.preScale(1f, -1f, coverRect.centerX(), coverRect.bottom);
        reflectionPaint.setShader(new LinearGradient(
                refRect.left, refRect.top, refRect.left, refRect.bottom,
                topColor, 0x00000000, Shader.TileMode.CLAMP));
        reflectionPaint.setAlpha(255);
        canvas.drawBitmap(bmp, matrix, reflectionPaint);
        reflectionPaint.setShader(null);
        canvas.restore();
    }

    private void drawTitle(Canvas canvas, String title, String subtitle, float w, float h,
            CoverFlowLayout.Metrics metrics) {
        float titleY = metrics.reflectTop + (h - metrics.reflectTop) * 0.35f;
        textPaint.setAlpha((int) (255 * titleAlpha));
        textPaint.setTextSize(w * 0.055f);
        textPaint.setTypeface(Typeface.create(ThemeManager.getCustomFont(), Typeface.BOLD));
        canvas.drawText(title != null ? title : "", w * 0.5f, titleY, textPaint);
        if (subtitle != null && !subtitle.isEmpty()) {
            textPaint.setTextSize(w * 0.038f);
            textPaint.setTypeface(Typeface.create(ThemeManager.getCustomFont(), Typeface.NORMAL));
            textPaint.setColor(ThemeManager.getTextColorSecondary());
            canvas.drawText(subtitle, w * 0.5f, titleY + h * 0.06f, textPaint);
            textPaint.setColor(0xFFFFFFFF);
        }
        textPaint.setAlpha(255);
    }

    private void drawEmptyHint(Canvas canvas, float w, float h) {
        if (w <= 0 || h <= 0) return;
        textPaint.setTextSize(w * 0.05f);
        canvas.drawText(getContext().getString(com.solar.launcher.R.string.flow_empty),
                w * 0.5f, h * 0.5f, textPaint);
    }
}
