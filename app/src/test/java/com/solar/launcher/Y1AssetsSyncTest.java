package com.solar.launcher;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

/** APK assets/y1 must match solar-rom/scripts — ROM and Y1RomPrep both depend on parity. */
public class Y1AssetsSyncTest {

    private static final List<String> SYNCED_FILES = Arrays.asList(
            "switch-to-stock.sh",
            "switch-to-rockbox.sh",
            "sync-rockbox-libs.sh",
            "sync-y1-keymap.sh",
            "disable-rockbox-for-solar.sh",
            "solar-usb-recovery-agent.sh",
            "Y1-Rockbox.kl");

    @Test
    public void apkAssetsMatchRomScripts() throws Exception {
        File scripts = new File("solar-rom/scripts");
        if (!scripts.isDirectory()) {
            scripts = new File("../solar-rom/scripts");
        }
        File assets = new File("app/src/main/assets/y1");
        if (!assets.isDirectory()) {
            assets = new File("src/main/assets/y1");
        }
        assertTrue("solar-rom/scripts missing", scripts.isDirectory());
        assertTrue("app assets y1 missing", assets.isDirectory());
        for (String name : SYNCED_FILES) {
            File src = new File(scripts, name);
            File dst = new File(assets, name);
            assertTrue("ROM source missing: " + name, src.isFile());
            assertTrue("APK asset missing: " + name + " — run sync-y1-assets.sh", dst.isFile());
            assertTrue("drift in " + name + " — run solar-rom/scripts/sync-y1-assets.sh",
                    filesEqual(src, dst));
        }
    }

    private static boolean filesEqual(File a, File b) throws Exception {
        if (a.length() != b.length()) return false;
        BufferedReader ra = new BufferedReader(
                new InputStreamReader(new FileInputStream(a), "UTF-8"));
        BufferedReader rb = new BufferedReader(
                new InputStreamReader(new FileInputStream(b), "UTF-8"));
        try {
            String la;
            String lb;
            while ((la = ra.readLine()) != null) {
                lb = rb.readLine();
                if (lb == null || !la.equals(lb)) return false;
            }
            return rb.readLine() == null;
        } finally {
            ra.close();
            rb.close();
        }
    }
}
