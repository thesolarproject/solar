package com.solar.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Stock Y1 About storage pie: available #3CFFDE, used #FFCC23 */
public class StoragePieView extends View {
    private float usedFraction;
    private final Paint usedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint availPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    public StoragePieView(Context context) {
        this(context, null);
    }

    public StoragePieView(Context context, AttributeSet attrs) {
        super(context, attrs);
        usedPaint.setColor(0xFFFFCC23);
        availPaint.setColor(0xFF3CFFDE);
        usedPaint.setStyle(Paint.Style.FILL);
        availPaint.setStyle(Paint.Style.FILL);
    }

    public void setUsedFraction(float fraction) {
        usedFraction = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int size = Math.min(w, h);
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        oval.set(left, top, left + size, top + size);
        float usedDeg = usedFraction * 360f;
        canvas.drawArc(oval, -90f, 360f - usedDeg, true, availPaint);
        if (usedDeg > 0f) {
            canvas.drawArc(oval, -90f + (360f - usedDeg), usedDeg, true, usedPaint);
        }
    }
}
