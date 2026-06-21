package com.solar.launcher;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SolarUpdateClientTest {
    @Test
    public void selfCheck() {
        SolarUpdateClient.selfCheck();
    }

    @Test
    public void fetchLiveCatalog() throws Exception {
        List<SolarUpdateClient.ReleaseInfo> releases =
                SolarUpdateClient.fetchUpdates(SolarUpdateClient.DEFAULT_UPDATES_URL);
        if (releases.isEmpty()) {
            throw new AssertionError("live OTA catalog empty");
        }
        List<SolarUpdateClient.ReleaseInfo> picker = SolarUpdateClient.releasesForPicker(
                releases, 4, "0.2.1", SolarUpdateClient.MAX_PICKER_RELEASES);
        if (picker.isEmpty()) {
            throw new AssertionError("picker empty for stable install");
        }
    }

    @Test
    public void pickerCapsLargeCatalog() {
        List<SolarUpdateClient.ReleaseInfo> many = new ArrayList<SolarUpdateClient.ReleaseInfo>();
        for (int i = 1; i <= 30; i++) {
            many.add(new SolarUpdateClient.ReleaseInfo(
                    "nightly-" + i, "nightly-" + i, i, "https://x/a.apk", true));
        }
        List<SolarUpdateClient.ReleaseInfo> picker = SolarUpdateClient.releasesForPicker(
                many, 4, "0.2.1", SolarUpdateClient.MAX_PICKER_RELEASES);
        if (picker.size() != SolarUpdateClient.MAX_PICKER_RELEASES) {
            throw new AssertionError("expected cap " + SolarUpdateClient.MAX_PICKER_RELEASES
                    + " got " + picker.size());
        }
        if (!"nightly-30".equals(picker.get(0).listLabel())) {
            throw new AssertionError("newest nightly first");
        }
    }
}
