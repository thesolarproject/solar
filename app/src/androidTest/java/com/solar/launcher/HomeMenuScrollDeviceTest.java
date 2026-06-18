package com.solar.launcher;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Home menu ScrollView must scroll and keep labels when many rows. */
@RunWith(AndroidJUnit4.class)
public class HomeMenuScrollDeviceTest {
    @Test
    public void manyRows_scrollViewKeepsLabelsVisible() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        int rowH = (int) ctx.getResources().getDimension(R.dimen.y1_menu_item_height);
        int count = 12;
        for (int i = 0; i < count; i++) {
            FrameLayout row = new FrameLayout(ctx);
            TextView label = new TextView(ctx);
            label.setSingleLine(true);
            label.setText("Item " + i);
            row.addView(label, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, rowH));
            container.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowH));
        }
        scroll.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(307, android.view.View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, 480, 307);

        View last = container.getChildAt(count - 1);
        last.requestFocus();
        scroll.requestChildFocus(container, last);
        scroll.layout(0, 0, 480, 307);

        TextView visible = (TextView) ((FrameLayout) container.getChildAt(count - 1)).getChildAt(0);
        if (visible.getText() == null || visible.getText().length() == 0) {
            throw new AssertionError("Last row label missing");
        }
        if (scroll.getScrollY() <= 0 && count > 6) {
            throw new AssertionError("ScrollView did not scroll toward last row, scrollY=" + scroll.getScrollY());
        }
    }
}
