package com.solar.launcher.xposed.themefont;

/**
 * 2026-07-05 — Package allow/deny for ActivitySkin — fail-open to stock when skipped.
 */
final class SkinPackagePolicy {

    private static final String INSTALLER = "de.robv.android.xposed.installer";

    private SkinPackagePolicy() {}

    /** Paint third-party and stock apps; never Solar or Xposed Installer. */
    static boolean shouldSkin(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (INSTALLER.equals(packageName)) return false;
        if (packageName.startsWith("com.solar.launcher")) return false;
        return ThemeSkinSidecar.isEnabled();
    }
}
