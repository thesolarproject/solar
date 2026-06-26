package com.solar.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * API 17 MT6572 crashes in View.onDrawScrollBars / ScrollBarDrawable.setAlpha.
 * ponytail: draw content only on Jelly Bean; full draw on API 19+.
 */
public class Y1SafeScrollView extends ScrollView {

    public Y1SafeScrollView(Context context) {
        super(context);
        init();
    }

    public Y1SafeScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public Y1SafeScrollView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setScrollbarFadingEnabled(false);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
    }

    @Override
    public void draw(Canvas canvas) {
        if (Build.VERSION.SDK_INT >= 19) {
            super.draw(canvas);
            return;
        }
        final int scrollX = getScrollX();
        final int scrollY = getScrollY();
        canvas.save();
        canvas.clipRect(scrollX, scrollY, scrollX + getWidth(), scrollY + getHeight());
        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
