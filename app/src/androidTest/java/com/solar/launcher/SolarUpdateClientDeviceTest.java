package com.solar.launcher;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solar.launcher.net.TlsHelper;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** OTA catalog fetch on real Y1 hardware (TLS + updates.xml parse). */
@RunWith(AndroidJUnit4.class)
public class SolarUpdateClientDeviceTest {
    @Test
    public void device_fetchUpdatesXml_listsReleases() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TlsHelper.init(ctx.getApplicationContext());
        List<SolarUpdateClient.ReleaseInfo> releases =
                SolarUpdateClient.fetchUpdates(SolarUpdateClient.DEFAULT_UPDATES_URL);
        android.util.Log.i("SolarOtaTest", "releases=" + releases.size()
                + " first=" + (releases.isEmpty() ? "none" : releases.get(0).listLabel()));
        if (releases.isEmpty()) {
            throw new AssertionError("OTA catalog returned no releases");
        }
        List<SolarUpdateClient.ReleaseInfo> picker = SolarUpdateClient.releasesForPicker(
                releases, 3, "0.2.1", SolarUpdateClient.MAX_PICKER_RELEASES);
        android.util.Log.i("SolarOtaTest", "picker=" + picker.size());
        if (picker.isEmpty()) {
            throw new AssertionError("releasesForPicker returned empty for stable 0.2.1");
        }
    }
}
