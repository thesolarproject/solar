package com.solar.launcher;

import org.junit.Test;

public class XposedModuleEnsurerTest {

    @Test
    public void requiredModulesMatchDeviceFamily() {
        assertRequiredModules("y1", "com.solar.launcher.xposed.bridge.y1", 3, false);
        assertRequiredModules("y2", "com.solar.launcher.xposed.bridge.y2", 4, true);
    }

    @Test
    public void ensurerExposesUserOverrideSkipHook() {
        if (XposedModuleEnsurer.shouldSkipForcedEnable(null)) {
            throw new AssertionError("null package must not skip repair");
        }
    }

    private static void assertRequiredModules(String family, String bridge, int expectedSize,
            boolean expectCompat) {
        DeviceFeatures.setCachedFamilyForTest(family);
        try {
            java.util.List<String> pkgs = XposedModuleEnsurer.requiredModulePackages();
            if (pkgs.size() != expectedSize) {
                throw new AssertionError("unexpected required module count for " + family);
            }
            if (!bridge.equals(pkgs.get(0))) throw new AssertionError(family + " bridge pkg");
            if (!pkgs.contains("com.solar.launcher.xposed.themefont")) {
                throw new AssertionError("theme font pkg");
            }
            if (!pkgs.contains("com.solar.launcher.xposed.rockbox.ime")) {
                throw new AssertionError("rockbox ime pkg");
            }
            if (expectCompat != pkgs.contains("com.solar.launcher.xposed.rockbox.compat")) {
                throw new AssertionError("rockbox compat policy");
            }
        } finally {
            DeviceFeatures.resetCacheForTest();
        }
    }
}
