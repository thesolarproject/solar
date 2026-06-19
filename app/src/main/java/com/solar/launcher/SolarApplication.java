package com.solar.launcher;

import android.app.Application;

import com.solar.launcher.avrcp.TrackInfoWriter;
import com.solar.launcher.net.TlsHelper;

/** ponytail: Cobrowse-style Conscrypt at boot — TLS 1.2 for API 17 Y1. */
public class SolarApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        TlsHelper.init(this);
        try {
            TrackInfoWriter.INSTANCE.init(this);
        } catch (Throwable ignored) {}
    }
}
