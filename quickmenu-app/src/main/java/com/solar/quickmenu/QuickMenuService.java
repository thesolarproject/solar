package com.solar.quickmenu;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.solar.launcher.contracts.SolarIntents;
import com.solar.launcher.theme.ThemeManager;
import com.solar.launcher.theme.ThemeSolarSkin;
import com.solar.launcher.ui.Y1RowFactory;
import com.solar.launcher.contracts.SolarSkin;

/** Global quick-menu overlay — TYPE_SYSTEM_ALERT on API 17. */
public class QuickMenuService extends Service {
    private WindowManager wm;
    private View overlay;

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeSolarSkin.bind(getApplicationContext());
        ThemeManager.ensureBundledDefault(this);
        ThemeManager.loadAllThemes(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (SolarIntents.ACTION_QUICKMENU_DISMISS.equals(action)) {
            hide();
        } else {
            show(intent.getStringExtra(SolarIntents.EXTRA_QUICKMENU_TIER));
        }
        return START_NOT_STICKY;
    }

    private void show(String tier) {
        hide();
        SolarSkin skin = ThemeSolarSkin.INSTANCE;
        overlay = Y1RowFactory.createLabelRow(this, skin,
                "Solar Quick Menu", tier != null ? tier : "root");
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        wm.addView(overlay, lp);
    }

    private void hide() {
        if (wm != null && overlay != null) {
            wm.removeView(overlay);
            overlay = null;
        }
    }

    @Override
    public void onDestroy() {
        hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
