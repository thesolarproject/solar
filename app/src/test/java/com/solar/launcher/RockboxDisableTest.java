package com.solar.launcher;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.assertTrue;

public class RockboxDisableTest {

    @Test
    public void markerPath_matchesRomInit() {
        if (!"/data/data/.solar_rom_home_ready".equals(RockboxDisable.MARKER_PATH)) {
            throw new AssertionError("marker path drift from 99SolarInit / build-rom wipe");
        }
    }

    @Test
    public void disableScript_reRunsWhenRockboxReEnabled() throws Exception {
        File f = scriptFile("disable-rockbox-for-solar.sh");
        String script = readFile(f);
        assertTrue(script.contains("packages -d"));
        assertTrue(script.contains("ROCKBOX_PKG"));
        assertFalse(script.contains("[ -f \"$MARKER\" ] && exit 0"));
    }

    private static File scriptFile(String name) {
        File f = new File("solar-rom/scripts/" + name);
        if (!f.isFile()) {
            f = new File("app/src/main/assets/y1/" + name);
        }
        if (!f.isFile()) {
            f = new File("../solar-rom/scripts/" + name);
        }
        return f;
    }

    private static String readFile(File f) throws Exception {
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }

    private static void assertFalse(boolean condition) {
        if (condition) throw new AssertionError("expected false");
    }
}
