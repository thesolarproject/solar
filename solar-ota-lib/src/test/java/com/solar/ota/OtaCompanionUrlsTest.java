package com.solar.ota;

import org.junit.Test;

/** OTA companion URL constants. */
public class OtaCompanionUrlsTest {

    @Test
    public void selfCheckPasses() {
        OtaCompanionUrls.selfCheck();
    }

    @Test
    public void jjUrlIsPresent() {
        if (!OtaCompanionUrls.JJ_APK_URL.contains("jj_latest.apk")) {
            throw new AssertionError("JJ URL missing");
        }
    }
}
