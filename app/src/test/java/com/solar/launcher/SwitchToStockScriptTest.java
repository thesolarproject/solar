package com.solar.launcher;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-07-08 — switch-to-stock must cooperate with helper HOME + enforcer, not fight it.
 * Reversal: weaker checks that only required "no reboot" + solar-launcher-exec string.
 */
public class SwitchToStockScriptTest {

    @Test
    public void stockPathDelegatesToLauncherExec() throws Exception {
        String script = readAssetOrRom("switch-to-stock.sh");
        assertTrue("switch-to-stock.sh missing", script.length() > 0);
        // Ignore comments — build audit + Rockbox require no live reboot/keylayout swap commands.
        String code = stripShellComments(script).toLowerCase();
        assertFalse("must stay rebootless", code.contains("reboot"));
        assertFalse("must not swap keylayouts", code.contains("generic.kl"));
        assertTrue(script.contains("solar-launcher-exec.sh"));
        assertTrue(script.contains("switch"));
        assertTrue(script.contains("solar"));
        // Rockbox bare call → Solar; flag matrix must stay.
        assertTrue(script.contains("--rockbox"));
        assertTrue(script.contains("--jj"));
        assertTrue(script.contains("--innioasis") || script.contains("--stock"));
        // Legacy fallback must still arm transition + keep helper, not clear preferred HOME.
        assertTrue(script.contains("persist.solar.home.applying")
                || script.contains("PROP_HOME_APPLYING"));
        assertTrue(script.contains("homehelper") || script.contains("HELPER"));
        assertTrue(script.contains("sys.solar.handoff.jj") || script.contains("PROP_JJ_HANDOFF"));
    }

    @Test
    public void buttonScriptKeepsUnsafeY1LauncherHandoffDisabled() throws Exception {
        File f = new File("solar-rom/system/99Y1ButtonScript");
        if (!f.isFile()) {
            f = new File("../solar-rom/system/99Y1ButtonScript");
        }
        assertTrue("99Y1ButtonScript missing", f.isFile());
        String text = readFile(f);
        String code = stripShellComments(text);
        assertTrue(code.contains("launcher_handoff()"));
        assertTrue(code.contains("launcher handoff disabled"));
        assertFalse(code.contains("switch-to-stock.sh --rockbox"));
        assertFalse(code.contains("pm disable"));
    }

    private static String readAssetOrRom(String name) throws Exception {
        File f = new File("app/src/main/assets/y1/" + name);
        if (!f.isFile()) {
            f = new File("src/main/assets/y1/" + name);
        }
        if (!f.isFile()) {
            f = new File("solar-rom/scripts/" + name);
        }
        if (!f.isFile()) {
            return "";
        }
        return readFile(f);
    }

    /** Drop #-comments so reversal notes mentioning "reboot" do not fail the no-reboot contract. */
    private static String stripShellComments(String script) {
        StringBuilder sb = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            int hash = line.indexOf('#');
            sb.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
        }
        return sb.toString();
    }

    private static String readFile(File f) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }
}
