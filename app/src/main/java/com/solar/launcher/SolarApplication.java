package com.solar.launcher;

import android.app.Application;

import com.solar.launcher.net.TlsHelper;
import com.solar.launcher.theme.ThemeSolarSkin;

/**
 * 2026-07-16 — Conscrypt TLS at process start only.
 * Rockbox/JJ coexistence is never part of cold start; full device staging
 * runs from first-run UI via {@link com.solar.launcher.stage.SolarDeviceStaging}.
 */
public class SolarApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeSolarSkin.bind(this);
        // Light bootstrap: in-process TLS provider. System certs/JNI staged on first run.
        new Thread(new Runnable() {
            @Override
            public void run() {
                TlsHelper.init(SolarApplication.this);
            }
        }, "SolarTlsInit").start();
    }
}
