package com.solar.launcher;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class XposedModuleStoreTest {

    @Test
    public void parseUserDisabledSkipsCommentsAndBlanks() {
        String text = "# comment\ncom.solar.launcher.xposed.bridge.y1\n\n# tail\n"
                + "com.solar.launcher.xposed.themefont\n";
        List<String> lines = XposedModuleStore.parseUserDisabledLines(text);
        if (lines.size() != 2) throw new AssertionError("expected 2 pkgs");
        Set<String> set = new HashSet<String>(lines);
        if (!set.contains("com.solar.launcher.xposed.bridge.y1")
                || !set.contains("com.solar.launcher.xposed.themefont")) {
            throw new AssertionError("parse mismatch: " + lines);
        }
    }

    @Test
    public void parseUserDisabledEmptyInput() {
        List<String> lines = XposedModuleStore.parseUserDisabledLines("");
        if (!lines.isEmpty()) throw new AssertionError("empty input");
    }

    @Test
    public void parseEnabledModulesXmlMultipleFlags() {
        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<map>\n"
                + "    <int name=\"com.solar.launcher.xposed.bridge.y1\" value=\"1\" />\n"
                + "    <int name=\"com.solar.launcher.xposed.themefont\" value=\"0\" />\n"
                + "    <int name=\"com.solar.launcher.xposed.rockbox.ime\" value=\"1\" />\n"
                + "</map>\n";
        java.util.Map<String, Boolean> flags = XposedModuleStore.parseEnabledModulesXml(xml);
        if (flags.size() != 3) throw new AssertionError("expected 3 flags, got " + flags.size());
        if (!Boolean.TRUE.equals(flags.get("com.solar.launcher.xposed.bridge.y1"))) {
            throw new AssertionError("y1 bridge should be enabled");
        }
        if (!Boolean.FALSE.equals(flags.get("com.solar.launcher.xposed.themefont"))) {
            throw new AssertionError("theme font should be disabled");
        }
        
    }
}
