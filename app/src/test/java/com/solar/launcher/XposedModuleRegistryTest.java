package com.solar.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class XposedModuleRegistryTest {

    @Test
    public void requiredModulesIncludeBridgeThemeFontAndRockboxIme() {
        List<String> pkgs = XposedModuleRegistry.requiredModulePackages();
        if (pkgs.size() < 3) throw new AssertionError("expected bridge + theme font + rockbox ime");
        Set<String> set = new HashSet<String>(pkgs);
        if (!set.contains("com.solar.launcher.xposed.themefont")) {
            throw new AssertionError("missing theme font");
        }
        if (!set.contains("com.solar.launcher.xposed.rockbox.ime")) {
            throw new AssertionError("missing rockbox ime");
        }
        if (set.contains("com.solar.launcher.xposed.notpipe")) {
            throw new AssertionError("retired NotPipe bridge must not be required");
        }
        String bridge = DeviceFeatures.isY2()
                ? "com.solar.launcher.xposed.bridge.y2"
                : "com.solar.launcher.xposed.bridge.y1";
        if (!set.contains(bridge)) throw new AssertionError("missing bridge for device");
    }

    @Test
    public void requiredModulesHaveDisableWarnings() {
        for (XposedModuleRegistry.Entry e : XposedModuleRegistry.allModules()) {
            if (!e.required) continue;
            if (e.disableWarningResId == 0) {
                throw new AssertionError("missing disable warning for " + e.packageName);
            }
        }
    }

    @Test
    public void powerMenuTestForceDisabled() {
        XposedModuleRegistry.Entry e = XposedModuleRegistry.findByPackage(
                "com.solar.launcher.xposed.powermenu");
        if (e == null || !e.forceDisabled) {
            throw new AssertionError("PowerMenuTest must stay locked off");
        }
    }
}
