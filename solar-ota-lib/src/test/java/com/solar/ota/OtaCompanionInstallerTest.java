package com.solar.ota;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 2026-07-06 — OtaCompanionInstaller pm staging guards (KitKat private-path install gap). */
public class OtaCompanionInstallerTest {

    @Test
    public void needsPmStage_privateAppUpdateDir() {
        assertTrue(OtaCompanionInstaller.needsPmStage(
                "/data/data/com.solar.launcher/app_update/jj_latest.apk"));
    }

    @Test
    public void needsPmStage_systemAndTmpPaths() {
        assertFalse(OtaCompanionInstaller.needsPmStage("/system/app/com.themoon.y1.apk"));
        assertFalse(OtaCompanionInstaller.needsPmStage("/data/local/tmp/jj_latest.apk"));
        assertFalse(OtaCompanionInstaller.needsPmStage(null));
    }

    @Test
    public void companionSystemApkPaths_underSystemApp() {
        if (!OtaCompanionUrls.JJ_SYSTEM_APK.startsWith("/system/app/")) {
            throw new AssertionError("JJ must stage under /system/app");
        }
        if (!OtaCompanionUrls.ROCKBOX_SYSTEM_APK.startsWith("/system/app/")) {
            throw new AssertionError("Rockbox must stage under /system/app");
        }
    }

    @Test
    public void installJjLatest_nullContext() {
        assertFalse(OtaCompanionInstaller.installJjLatest(null, null, null));
    }

    @Test
    public void installRockboxLatest_nullContext() {
        OtaCompanionInstaller.Result r = OtaCompanionInstaller.installRockboxLatest(null, null, null);
        assertFalse(r.jjOk);
                    }
}
