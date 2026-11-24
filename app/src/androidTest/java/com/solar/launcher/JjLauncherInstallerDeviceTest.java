package com.solar.launcher;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solar.launcher.net.SolarHttp;
import com.solar.launcher.net.TlsHelper;
import com.solar.ota.OtaCompanionInstaller;
import com.solar.ota.OtaCompanionUrls;
import com.solar.ota.net.OtaDownload;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertTrue;

/**
 * 2026-07-19 — Presence + optional URL reachability on Y1/Y2 (Solar no longer installs companions).
 * Was: asserted OTA download+install of JJ/Rockbox. Reversal: restore install assertions.
 */
@RunWith(AndroidJUnit4.class)
public class JjLauncherInstallerDeviceTest {

    @Test
    public void device_downloadJjApk_overHttps() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!ConnectivityHelper.isOnline(ctx)) {
            android.util.Log.w("JjInstallerTest", "offline — skip");
            return;
        }
        TlsHelper.init(ctx.getApplicationContext());
        File dest = new File(ctx.getCacheDir(), "jj_device_test.apk");
        if (dest.exists()) dest.delete();
        SolarHttp.downloadToFile(JjLauncherAvailability.JJ_APK_URL, dest);
        assertTrue("jj_latest.apk must be >1MB", dest.isFile() && dest.length() > 1_000_000L);
        android.util.Log.i("JjInstallerTest", "downloaded bytes=" + dest.length());
    }


    /**
     * 2026-07-19 — installJjIfNeeded is presence-only (never downloads).
     * Was: asserted install after download. Reversal: restore download+assert install.
     */
    @Test
    public void device_installJjIfNeeded_presenceOnly() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File workDir = ctx.getDir("update", Context.MODE_PRIVATE);
        boolean ok = OtaCompanionInstaller.installJjIfNeeded(ctx, workDir);
        // True only when JJ already on device; false is success for Solar-only policy.
        android.util.Log.i("JjInstallerTest", "installJjIfNeeded presence=" + ok
                + " pm=" + LauncherSwitch.isJjInstalled(ctx));
        org.junit.Assert.assertEquals(LauncherSwitch.isJjInstalled(ctx), ok);
    }
}

