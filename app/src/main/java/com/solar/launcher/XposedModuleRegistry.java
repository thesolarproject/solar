package com.solar.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2026-07-05 — Catalog anchor for production Xposed modules (Debug UI + boot ensurer).
 * APK/ROM parity: requiredModulePackages() must match manifest.json modules + 99XposedInit.sh seeds.
 * When adding a production module also update manifest.json, sync-platform-assets.sh, 99XposedInit.sh.
 * Reversal: delete registry rows; ensurer falls back to hard-coded package list only.
 */
public final class XposedModuleRegistry {

    /** One hook module the user may toggle from Debug (with warnings when required). */
    public static final class Entry {
        public final String packageName;
        public final int labelResId;
        public final boolean required;
        public final int disableWarningResId;
        /** Lab-only modules — shown locked off (PowerMenuTest). */
        public final boolean forceDisabled;

        Entry(String packageName, int labelResId, boolean required,
                int disableWarningResId, boolean forceDisabled) {
            this.packageName = packageName;
            this.labelResId = labelResId;
            this.required = required;
            this.disableWarningResId = disableWarningResId;
            this.forceDisabled = forceDisabled;
        }
    }

    private static final String PKG_BRIDGE_Y1 = "com.solar.launcher.xposed.bridge.y1";
    private static final String PKG_BRIDGE_Y2 = "com.solar.launcher.xposed.bridge.y2";
    private static final String PKG_THEME_FONT = "com.solar.launcher.xposed.themefont";
    private static final String PKG_POWERMENU_TEST = "com.solar.launcher.xposed.powermenu";

    private XposedModuleRegistry() {}

    /** Bridge package baked on this device family. */
    public static String bridgePackageForDevice() {
        return DeviceFeatures.isY2() ? PKG_BRIDGE_Y2 : PKG_BRIDGE_Y1;
    }

    /** Registry metadata for Debug UI labels and disable warnings — not the discovery list. */
    public static List<Entry> allModules() {
        List<Entry> out = new ArrayList<Entry>();
        out.add(new Entry(
                bridgePackageForDevice(),
                R.string.settings_debug_xposed_bridge,
                true,
                R.string.settings_debug_xposed_bridge_disable_warning,
                false));
        out.add(new Entry(
                PKG_THEME_FONT,
                R.string.settings_debug_xposed_theme_font,
                true,
                R.string.settings_debug_xposed_theme_font_disable_warning,
                false));
        // 2026-07-15 — NotPipe bridge removed; YouTube is native in Solar.
        out.add(new Entry(
                PKG_POWERMENU_TEST,
                R.string.settings_debug_xposed_powermenu_test,
                false,
                0,
                true));
        return Collections.unmodifiableList(out);
    }

    /** Required production modules — ensurer repairs unless user override disables them. */
    public static List<String> requiredModulePackages() {
        List<String> pkgs = new ArrayList<String>();
        pkgs.add(bridgePackageForDevice());
        pkgs.add(PKG_THEME_FONT);
        return Collections.unmodifiableList(pkgs);
    }

    /** System APK path for a production module — discovery fallback when PM grep misses. */
    public static String systemApkPathForPackage(String pkg) {
        if (pkg == null) return null;
        if (PKG_BRIDGE_Y1.equals(pkg)) return "/system/app/SolarContextBridgeY1.apk";
        if (PKG_BRIDGE_Y2.equals(pkg)) return "/system/app/SolarContextBridgeY2.apk";
        if (PKG_THEME_FONT.equals(pkg)) return "/system/app/SolarThemeFont.apk";
        // 2026-07-15 — A5 ROM may still ship unmodified NotPipe (not an Xposed module).
        if ("io.github.gohoski.notpipe".equals(pkg)) return "/system/app/io.github.gohoski.notpipe.apk";
        if ("com.solar.launcher.globalcontext".equals(pkg)) return "/system/app/SolarGlobalContextModal.apk";
        return null;
    }

    /** Package for a baked /system/app Solar hook APK — inverse of systemApkPathForPackage. */
    public static String packageForSystemApkPath(String apkPath) {
        if (apkPath == null || apkPath.isEmpty()) return null;
        if (apkPath.endsWith("SolarContextBridgeY1.apk")) return PKG_BRIDGE_Y1;
        if (apkPath.endsWith("SolarContextBridgeY2.apk")) return PKG_BRIDGE_Y2;
        if (apkPath.endsWith("SolarThemeFont.apk")) return PKG_THEME_FONT;
        if (apkPath.endsWith("io.github.gohoski.notpipe.apk")) return "io.github.gohoski.notpipe";
        if (apkPath.endsWith("SolarGlobalContextModal.apk")) return "com.solar.launcher.globalcontext";
        return null;
    }

    /** Lookup one registry row by package name. */
    public static Entry findByPackage(String pkg) {
        if (pkg == null) return null;
        for (Entry e : allModules()) {
            if (pkg.equals(e.packageName)) return e;
        }
        return null;
    }

    /** Lab-only package — never auto-enabled; toggle locked in Debug UI. */
    public static boolean isForceDisabledLabPackage(String pkg) {
        return PKG_POWERMENU_TEST.equals(pkg);
    }
}
