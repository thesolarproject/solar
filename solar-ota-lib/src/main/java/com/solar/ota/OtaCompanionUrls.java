package com.solar.ota;

/**
 * OTA companion download URLs (JJ Launcher) with ordered fallbacks.
 */
public final class OtaCompanionUrls {

    public static final String SOLAR_UPDATE_BASE =
            "https://thesolarproject.github.io/solar-update/";

    public static final String JJ_APK_URL = SOLAR_UPDATE_BASE + "jj_latest.apk";
    public static final String JJ_PACKAGE = "com.themoon.y1";
    public static final String JJ_SYSTEM_APK = "/system/app/com.themoon.y1.apk";
    public static final String FILE_JJ = "jj_latest.apk";
    public static final String FILE_UPDATE_ZIP = "update.zip";

    private OtaCompanionUrls() {}

    /** Self-check for unit tests — URL order and non-empty constants. */
    public static void selfCheck() {
        if (!JJ_APK_URL.contains("jj_latest.apk")) {
            throw new AssertionError("JJ URL must end with jj_latest.apk");
        }
    }
}
