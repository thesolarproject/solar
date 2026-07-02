package com.solar.launcher.flow;

import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Shared Camera.rotateY album cover draw — Flow carousel, handoff flyer, Now Playing.
 * ponytail: static helpers only; one bitmap in, no duplicate decode.
 */
public final class FlowAlbumArt3d {

    /** Classipod / iPod Now Playing resting Y tilt — opposite lean from carousel center. */
    public static final float PLAYER_ROT_Y_DEG = 14f;
    /** Idle carousel reflection peak — keep floor gloss subtle so title text stays legible. */
    public static final float REFLECT_PEAK_FRAC = 0.28f;

    private static final Camera camera = new Camera();
    private static final Matrix matrix = new Matrix();
    private static final RectF bitmapSrc = new RectF();

    private FlowAlbumArt3d() {}

    /** View-local draw rect + rotation — single source for NP and handoff landing. */
    public static final class AlbumArtPose {
        public final RectF drawRect;
        public final float rotationYDeg;

        AlbumArtPose(RectF drawRect, float rotationYDeg) {
            this.drawRect = drawRect;
            this.rotationYDeg = rotationYDeg;
        }
    }

    /** Handoff / 3D card bounds — square prism, not south-encroached back-face rect. */
    public static RectF squareBounds(RectF rect) {
        if (rect == null) return new RectF();
        float size = Math.min(rect.width(), rect.height());
        if (size <= 0f) return new RectF(rect);
        float cx = rect.centerX();
        float cy = rect.centerY();
        return new RectF(cx - size * 0.5f, cy - size * 0.5f, cx + size * 0.5f, cy + size * 0.5f);
    }

    /** Screen rect of a Flow center slot — always square transform bounds. */
    public static RectF screenRectFromSlot(View view, FlowEngine.SlotTransform t) {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float half = t.width * 0.5f;
        return new RectF(
                loc[0] + t.centerX - half,
                loc[1] + t.centerY - half,
                loc[0] + t.centerX + half,
                loc[1] + t.centerY + half);
    }

    /** Left-aligned, vertically centered square — Classipod / iPod Now Playing 3D slot. */
    public static AlbumArtPose playerPose(float viewW, float viewH) {
        float size = Math.min(viewW, viewH);
        if (size <= 0f) {
            return new AlbumArtPose(new RectF(), PLAYER_ROT_Y_DEG);
        }
        float top = (viewH - size) * 0.5f;
        return new AlbumArtPose(
                new RectF(0f, top, size, top + size),
                PLAYER_ROT_Y_DEG);
    }

    /** @deprecated use {@link #playerPose(float, float)} */
    public static RectF playerAlbumDrawRect(float viewW, float viewH) {
        return playerPose(viewW, viewH).drawRect;
    }

    /** Screen rect for handoff landing — same geometry as resting Now Playing 3D slot. */
    public static RectF playerScreenRect(View container) {
        if (container == null) return new RectF();
        int[] loc = new int[2];
        container.getLocationOnScreen(loc);
        float w = container.getWidth();
        float h = container.getHeight();
        AlbumArtPose pose = playerPose(w, h);
        RectF r = pose.drawRect;
        if (r.width() <= 0f) return new RectF();
        return new RectF(loc[0] + r.left, loc[1] + r.top, loc[0] + r.right, loc[1] + r.bottom);
    }

    /** @deprecated use {@link #playerScreenRect(View)} */
    public static RectF playerAlbumScreenRect(View container) {
        return playerScreenRect(container);
    }

    public static void drawCover(Canvas canvas, Bitmap bmp, RectF rect, float rotationYDeg,
            float alpha, Paint paint) {
        if (canvas == null || bmp == null || bmp.isRecycled() || rect == null || paint == null) return;
        RectF square = squareBounds(rect);
        if (square.width() <= 0f) return;

        canvas.save();
        float pivotX = CoverFlowLayout.pivotXForCoverFlow(rotationYDeg,
                square.left, square.right);
        float pivotY = square.centerY();
        camera.save();
        camera.rotateY(rotationYDeg);
        matrix.reset();
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-pivotX, -pivotY);
        matrix.postTranslate(pivotX, pivotY);
        canvas.concat(matrix);

        int oldAlpha = paint.getAlpha();
        paint.setAlpha((int) (oldAlpha * alpha));
        canvas.drawBitmap(bmp, null, square, paint);
        paint.setAlpha(oldAlpha);
        canvas.restore();
    }

    /**
     * Cover + floor reflection (Flow carousel gloss).
     *
     * @param reflectHeight px below square bottom; 0 skips reflection
     */
    public static void drawCoverWithReflection(Canvas canvas, Bitmap bmp, RectF rect,
            float rotationYDeg, float alpha, float reflectHeight, int[] reflectTable,
            Paint coverPaint, Paint reflectionPaint) {
        if (canvas == null || bmp == null || bmp.isRecycled() || rect == null) return;
        RectF square = squareBounds(rect);
        drawCover(canvas, bmp, square, rotationYDeg, alpha, coverPaint);

        if (reflectHeight <= 0f || reflectionPaint == null) return;
        RectF refRect = new RectF(square.left, square.bottom,
                square.right, square.bottom + reflectHeight);
        // ponytail: per-row Rockbox reflect_table — saveLayer+DST_IN painted white on MT6572.
        int rows = reflectTable != null && reflectTable.length > 0
                ? reflectTable.length : Math.max(1, Math.round(reflectHeight));
        float rowH = reflectHeight / rows;
        bitmapSrc.set(0f, 0f, bmp.getWidth(), bmp.getHeight());
        matrix.reset();
        matrix.setRectToRect(bitmapSrc, square, Matrix.ScaleToFit.FILL);
        matrix.postScale(1f, -1f, square.centerX(), square.bottom);

        canvas.save();
        canvas.clipRect(refRect);
        int oldAlpha = reflectionPaint.getAlpha();
        reflectionPaint.setShader(null);
        for (int row = 0; row < rows; row++) {
            int tableA = reflectTable != null && row < reflectTable.length
                    ? reflectTable[row] : (768 * (rows - row) / rows);
            int peakAlpha = (tableA * Math.round(alpha * 256) + 129) >> 8;
            peakAlpha = Math.min(255, Math.max(0, peakAlpha * 255 / 768));
            peakAlpha = Math.round(peakAlpha * REFLECT_PEAK_FRAC);
            if (peakAlpha <= 0) continue;
            float y0 = refRect.top + row * rowH;
            float y1 = (row == rows - 1) ? refRect.bottom : y0 + rowH;
            canvas.save();
            canvas.clipRect(square.left, y0, square.right, y1);
            reflectionPaint.setAlpha(peakAlpha);
            canvas.drawBitmap(bmp, matrix, reflectionPaint);
            canvas.restore();
        }
        reflectionPaint.setAlpha(oldAlpha);
        canvas.restore();
    }
}
