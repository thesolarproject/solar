package com.solar.launcher;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SolarUpdateClientTest {
    @Test
    public void selfCheck() {
        SolarUpdateClient.selfCheck();
    }

    @Test
    public void fetchLiveCatalog() throws Exception {
        List<SolarUpdateClient.ReleaseInfo> raw =
                SolarUpdateClient.fetchUpdatesRaw(SolarUpdateClient.DEFAULT_UPDATES_URL);
        if (raw.isEmpty()) {
            throw new AssertionError("live OTA catalog empty");
        }
        List<SolarUpdateClient.ReleaseInfo> releases =
                SolarUpdateClient.filterForDevice(raw);
        List<SolarUpdateClient.ReleaseInfo> picker = SolarUpdateClient.releasesForPicker(
                raw, 4, "0.2.1", SolarUpdateClient.MAX_PICKER_RELEASES);
        if (picker.isEmpty() && !releases.isEmpty()) {
            throw new AssertionError("picker empty for stable install");
        }
    }

    @Test
    public void pickerCapsLargeCatalog() {
        String variant = SolarUpdateClient.deviceVariant();
        List<SolarUpdateClient.ReleaseInfo> many = new ArrayList<SolarUpdateClient.ReleaseInfo>();
        for (int i = 1; i <= 30; i++) {
            String day = String.format(Locale.US, "%02d", (i % 28) + 1);
            String tag = String.format(Locale.US, "nightly-202406%s-%04d", day, 1000 + i);
            many.add(new SolarUpdateClient.ReleaseInfo(
                    tag, tag, 1000 + i,
                    "https://x/solar-" + variant + "-" + tag + ".apk", true));
        }
        List<SolarUpdateClient.ReleaseInfo> picker = SolarUpdateClient.releasesForPicker(
                many, 4, "0.2.1", SolarUpdateClient.MAX_PICKER_RELEASES);
        if (picker.size() != SolarUpdateClient.MAX_PICKER_RELEASES) {
            throw new AssertionError("expected cap " + SolarUpdateClient.MAX_PICKER_RELEASES
                    + " got " + picker.size());
        }
        if (!picker.get(0).tag.contains("202406")) {
            throw new AssertionError("newest timestamp nightly first");
        }
    }
}
