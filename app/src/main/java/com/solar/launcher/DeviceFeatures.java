package com.solar.launcher;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 2026-07-05 — Device family hub: Y1 (MT6572), Y2 (MT6582), A5 (Timmkoo touch 240×320).
 * 2026-07-11 — A5 is exclusive third family (not Y1-by-default); touch + portrait themes.
 * 2026-07-15 — Dual-storage resilience: all families scan every healthy volume;
 *   Primary storage pref only picks where new downloads/recordings go;
 *   Y1 may discover Internal via A5-style probes when a second mount exists.
 * 2026-07-15 — A5 stock props often claim model=Y1; use display 240×320 + input scheme,
 *   not ro.product.model alone (staging/adb must use the same checks).
 * Intentional divergences: Y1 no power button (BACK-long overlay); Y2 power/BACK-long;
 *   Y1 AVRCP native BT patches; Y2 dual storage; Y2 hides volume/lock chips;
 *   A5 touchscreen, dual face/side nav modes, portrait default, overlay volume/lock.
 * Reversal: drop isA5 branches; restore isY1 = !isY2; revert Y1 secondary to null.
 */
public final class DeviceFeatures {
    private static final String TAG = "DeviceFeatures";

    /** Reset → MicroSD wipe target — MicroSD when present, else primary fall-open. */
    public static File getMicroSdWipeRoot() {
        File micro = getMicroSdRoot();
        if (micro != null) return micro;
        return getPrimaryStorageRoot();
    }

    public static java.util.List<File> getMusicRoots() {
        return subdirRoots("Music");
    }

    /** 2026-07-15 — Audiobooks/ on each browsable volume. */
    public static java.util.List<File> getAudiobookRoots() {
        return subdirRoots("Audiobooks");
    }

    public static java.util.List<File> getPodcastRoots() {
        return subdirRoots("Podcasts");
    }

    public static java.util.List<File> getVideoRoots() {
        return subdirRoots("Videos");
    }

    public static java.util.List<File> getThemeRoots() {
        return subdirRoots("Themes");
    }

    public static java.util.List<File> getPhotoRoots() {
        java.util.List<File> list = new java.util.ArrayList<File>();
        for (File root : getStorageRoots()) {
            list.add(new File(root, "Pictures"));
            list.add(new File(root, "DCIM"));
        }
        return list;
    }

    public static java.util.List<File> getFmRecordingRoots() {
        return subdirRoots("FM Recordings");
    }

    private static java.util.List<File> subdirRoots(String subdir) {
        java.util.List<File> list = new java.util.ArrayList<File>();
        for (File root : getStorageRoots()) {
            list.add(new File(root, subdir));
        }
        return list;
    }

    public static void setCachedFamilyForTest(String family) {
        cachedFamily = family;
    }

    static void resetCacheForTest() {
        cachedFamily = null;
        testMicroSdPresentOverride = null;
    }

    /** Unit tests — simulate SD mount without /storage paths. */
    static void setMicroSdPresentForTest(boolean present) {
        testMicroSdPresentOverride = present;
    }

    static String resolvePrimaryMediaPrefForTest(String explicitPref, Boolean legacyInternal) {
        if (PRIMARY_MEDIA_MICROSD.equals(explicitPref) || PRIMARY_MEDIA_INTERNAL.equals(explicitPref)) {
            return explicitPref;
        }
        if (legacyInternal != null) {
            return legacyInternal ? PRIMARY_MEDIA_INTERNAL : PRIMARY_MEDIA_MICROSD;
        }
        return isMicroSdPresent() ? PRIMARY_MEDIA_MICROSD : PRIMARY_MEDIA_INTERNAL;
    }

    static String detectFamilyForTest(String cpuHardware, String boardHardware, int sdkInt, String model) {
        cachedFamily = null;
        return probeFamily(cpuHardware, boardHardware, sdkInt, model, "", 0, 0, "");
    }

    /** Unit / emulator — include manufacturer tokens (timmkoo, etc.). */
    static String detectFamilyForTest(String cpuHardware, String boardHardware, int sdkInt,
            String model, String manufacturer) {
        cachedFamily = null;
        return probeFamily(cpuHardware, boardHardware, sdkInt, model, manufacturer, 0, 0, "");
    }

    /**
     * 2026-07-15 — Include display pixels when model props lie (A5 reports as Y1).
     * @param displayW physical/logical width px (0 = unknown)
     * @param displayH physical/logical height px (0 = unknown)
     */
    static String detectFamilyForTest(String cpuHardware, String boardHardware, int sdkInt,
            String model, String manufacturer, int displayW, int displayH) {
        cachedFamily = null;
        return probeFamily(cpuHardware, boardHardware, sdkInt, model, manufacturer,
                displayW, displayH, "");
    }

    /** Unit — optional persist.solar.device_family pin after SoC / A5 gates. */
    static String detectFamilyForTest(String cpuHardware, String boardHardware, int sdkInt,
            String model, String manufacturer, int displayW, int displayH, String familyPin) {
        cachedFamily = null;
        return probeFamily(cpuHardware, boardHardware, sdkInt, model, manufacturer,
                displayW, displayH, familyPin);
    }

    private static String detectFamily() {
        if (cachedFamily != null) return cachedFamily;
        synchronized (DeviceFeatures.class) {
            if (cachedFamily != null) return cachedFamily;
            int[] px = readDisplayPixels();
            // 2026-07-16 — A5 QVGA is unique: force a5 even when stock props / pin say y1.
            // Do NOT short-circuit ~360×480 to y1: Y2 shares that panel; SoC + SDK disambiguate.
            if (looksLikeA5Display(px[0], px[1])) {
                cachedFamily = "a5";
                logDetected(cachedFamily, px);
                return cachedFamily;
            }
            String board = (Build.HARDWARE != null ? Build.HARDWARE : "")
                    + " " + (Build.BOARD != null ? Build.BOARD : "");
            String manu = (Build.MANUFACTURER != null ? Build.MANUFACTURER : "")
                    + " " + (Build.BRAND != null ? Build.BRAND : "")
                    + " " + (Build.PRODUCT != null ? Build.PRODUCT : "");
            String prop = readSystemProperty(PROP_DEVICE_FAMILY);
            cachedFamily = probeFamily(readProcCpuHardware(), board, Build.VERSION.SDK_INT,
                    Build.MODEL != null ? Build.MODEL : "", manu, px[0], px[1], prop);
            logDetected(cachedFamily, px);
            return cachedFamily;
        }
    }

    private static void logDetected(String family, int[] px) {
        try {
            Log.i(TAG, "detected device family: " + family
                    + " hw=" + Build.HARDWARE + " board=" + Build.BOARD
                    + " sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL
                    + " manu=" + Build.MANUFACTURER
                    + " display=" + (px != null ? px[0] + "x" + px[1] : "?"));
        } catch (Throwable ignored) {}
    }

    /**
     * 2026-07-16 — Family priority for Reach usernames / UI:
     * A5 panel → A5 tokens → SoC (MT6582/MT6572) → ROM pin → shared 360p via SDK
     * (4.4.x/SDK19 = Y2, 4.2.x/SDK17 = Y1) → model tokens → y1.
     * Was: early 360p short-circuit forced y1 and mislabeled Y2; A5 model=Y1 was OK via QVGA.
     */
    private static String probeFamily(String cpuHardware, String boardHardware, int sdkInt,
            String model, String manufacturer, int displayW, int displayH) {
        return probeFamily(cpuHardware, boardHardware, sdkInt, model, manufacturer,
                displayW, displayH, "");
    }

    private static String probeFamily(String cpuHardware, String boardHardware, int sdkInt,
            String model, String manufacturer, int displayW, int displayH, String familyPin) {
        String cpu = cpuHardware != null ? cpuHardware.toLowerCase(Locale.US) : "";
        String board = boardHardware != null ? boardHardware.toLowerCase(Locale.US) : "";
        String m = model != null ? model.toLowerCase(Locale.US) : "";
        String manu = manufacturer != null ? manufacturer.toLowerCase(Locale.US) : "";
        String pin = familyPin != null ? familyPin.toLowerCase(Locale.US).trim() : "";
        // Strongest: physical A5 panel (stock ROM still ships Y1 product props).
        if (looksLikeA5Display(displayW, displayH)) return "a5";
        // Explicit A5 model string (lab / future ROMs).
        if (looksLikeA5ModelOrManu(m, manu)) return "a5";
        // SoC before shared landscape panel — Y2 is MT6582, Y1 is MT6572.
        if (cpu.contains("mt6582") || board.contains("mt6582")) return "y2";
        if (cpu.contains("mt6572") || board.contains("mt6572")) return "y1";
        // ROM / emulator pin when SoC unknown (after A5 panel so stale y1 pin cannot own A5).
        if ("a5".equals(pin) || "y1".equals(pin) || "y2".equals(pin)) return pin;
        // Shared ~360×480: disambiguate by Android release when SoC/pin absent.
        // 360p + 4.4.x (SDK 19+) → Y2; 360p + 4.2.x (SDK ≤17) → Y1.
        if (looksLikeY1Display(displayW, displayH)) {
            if (sdkInt >= 19) return "y2";
            if (sdkInt <= 17) return "y1";
            // SDK 18 middle: model tokens, else Y1.
            if (m.contains("y2")) return "y2";
            if (m.contains("y1")) return "y1";
            return "y1";
        }
        // No reliable panel: SDK / model fallbacks (kitkat-class → y2).
        if (sdkInt >= 19) return "y2";
        if (sdkInt <= 16) return "y1";
        if (m.contains("y2")) return "y2";
        if (m.contains("y1")) return "y1";
        return "y1";
    }

    /**
     * 2026-07-15 — A5 QVGA portrait 240×320 (order-independent; ±20px for overscan).
     * Pure logic for unit tests and adb staging scripts.
     * 2026-07-20 — public for PhoneChromePolicy (other package).
     */
    public static boolean looksLikeA5Display(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return false;
        int a = Math.min(widthPx, heightPx);
        int b = Math.max(widthPx, heightPx);
        return a >= 220 && a <= 260 && b >= 300 && b <= 340;
    }

    /**
     * 2026-07-15 — Y1 360×480 (landscape 480×360 or portrait). ±20px.
     * 2026-07-20 — public for PhoneChromePolicy (other package).
     */
    public static boolean looksLikeY1Display(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return false;
        int a = Math.min(widthPx, heightPx);
        int b = Math.max(widthPx, heightPx);
        return a >= 340 && a <= 380 && b >= 460 && b <= 500;
    }

    /**
     * 2026-07-20 — Family pin for phone-chrome gate (empty when unset).
     * PhoneChromePolicy treats y1|y2|a5 as “no chrome” even on tall skins.
     * Reversal: delete; policy can only use display size.
     */
    public static String readDeviceFamilyPinForPhoneChrome() {
        return readSystemProperty(PROP_DEVICE_FAMILY);
    }

    /**
     * Model/brand tokens for A5 when display is unknown.
     * 2026-07-15 — Do not treat bare "Y1" or bare "Timmkoo" as A5 (Y1 is also Timmkoo).
     * Was: any timmkoo → a5; model y1 blocked a5 tokens forever.
     */
    private static boolean looksLikeA5ModelOrManu(String m, String manu) {
        if (m == null) m = "";
        if (manu == null) manu = "";
        // Explicit A5 in model/product string.
        if (m.contains("a5") || manu.contains("a5")) return true;
        // "Timmkoo A5" style — a5 already matched; avoid classifying plain "Y1" + Timmkoo.
        if ((manu.contains("timmkoo") || m.contains("timmkoo"))
                && !m.contains("y1") && !m.contains("y2")
                && m.length() > 0 && !m.equals("y1")) {
            // Brand alone is ambiguous — only when model is empty/generic lab.
            if (m.contains("generic") || m.length() == 0) return false;
        }
        return false;
    }

    /** @deprecated use {@link #looksLikeA5ModelOrManu} — kept for older tests if any. */
    private static boolean looksLikeA5(String m, String manu) {
        return looksLikeA5ModelOrManu(m, manu);
    }

    /** Read built-in display size; [0]=w [1]=h or 0,0 when unavailable (JVM tests). */
    private static int[] readDisplayPixels() {
        int[] out = new int[] { 0, 0 };
        try {
            android.content.res.Resources res = android.content.res.Resources.getSystem();
            if (res == null) return out;
            android.util.DisplayMetrics dm = res.getDisplayMetrics();
            if (dm == null) return out;
            out[0] = dm.widthPixels;
            out[1] = dm.heightPixels;
        } catch (Throwable ignored) {}
        return out;
    }

    private static String readSystemProperty(String key) {
        if (key == null || key.length() == 0) return "";
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v != null ? String.valueOf(v).trim().toLowerCase(Locale.US) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readProcCpuHardware() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase(Locale.US).startsWith("hardware")) {
                    int colon = trimmed.indexOf(':');
                    if (colon >= 0 && colon + 1 < trimmed.length()) {
                        return trimmed.substring(colon + 1).trim();
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {}
            }
        }
        return "";
    }
}
