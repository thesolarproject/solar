package com.solar.launcher.globalcontext;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 2026-07-05 — Companion HOME when persist.solar.emergency_mode=1 after crash loop.
 * Layman: explains Solar could not start and offers app picker or streak reset.
 * Technical: lightweight Activity launched explicitly by Solar during emergency recovery; it is not a HOME app.
 * Reversal: remove Activity; Solar main APK EmergencyRecoveryActivity remains the recovery fallback.
 */
public final class EmergencyRecoveryActivity extends Activity {

    /** No user-facing platform repair (2026-07-11). */
    private static final String[] MENU = {"launcher", "clear", "retry"};
    private int focusIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!EmergencyRockboxMode.isEmergencyMode()) {
            EmergencyRockboxMode.clearEmergencyAndRetrySolar(this);
            finish();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText(getString(R.string.emergency_recovery_title));
        title.setTextSize(18);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText(getString(R.string.emergency_recovery_body));
        body.setTextSize(14);
        body.setPadding(0, 16, 0, 24);
        root.addView(body);

        for (String id : MENU) {
            TextView row = new TextView(this);
            row.setTag(id);
            row.setText(labelFor(id));
            row.setTextSize(16);
            row.setPadding(8, 12, 8, 12);
            row.setFocusable(true);
            root.addView(row);
        }
        scroll.addView(root);
        setContentView(scroll);
        updateFocus(root);
    }

    private String labelFor(String id) {
        if ("launcher".equals(id)) return getString(R.string.emergency_recovery_launcher);
        if ("clear".equals(id)) return getString(R.string.emergency_recovery_clear);
        return getString(R.string.emergency_recovery_retry);
    }

    private void updateFocus(LinearLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            if (v.getTag() == null) continue;
            boolean sel = i - 2 == focusIndex;
            v.setBackgroundColor(sel ? 0x44FFFFFF : 0);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        ScrollView scroll = (ScrollView) findViewById(android.R.id.content);
        if (scroll == null || scroll.getChildCount() == 0) return super.onKeyDown(keyCode, event);
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (focusIndex < MENU.length - 1) focusIndex++;
            updateFocus(root);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (focusIndex > 0) focusIndex--;
            updateFocus(root);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            onMenuSelect(MENU[focusIndex]);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void onMenuSelect(String id) {
        if ("launcher".equals(id)) {
            EmergencyRockboxMode.openPackageLauncher(this);
            return;
        }
        if ("clear".equals(id)) {
            EmergencyRockboxMode.clearEmergencyAndRetrySolar(this);
            finish();
            return;
        }
        if ("retry".equals(id)) {
            EmergencyRockboxMode.clearEmergencyAndRetrySolar(this);
            finish();
        }
    }
}
