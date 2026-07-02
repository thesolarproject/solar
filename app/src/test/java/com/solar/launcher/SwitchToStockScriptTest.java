package com.solar.launcher;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Bundled switch-to-stock must disable Rockbox before starting Solar. */
public class SwitchToStockScriptTest {

    @Test
    public void stockPathDisablesRockboxBeforeSolar() throws Exception {
        File f = new File("app/src/main/assets/y1/switch-to-stock.sh");
        if (!f.isFile()) {
            f = new File("src/main/assets/y1/switch-to-stock.sh");
        }
        assertTrue("switch-to-stock.sh missing", f.isFile());
        String script = readFile(f);
        assertFalse(script.toLowerCase().contains("reboot"));
        int stockFn = script.indexOf("switch_to_stock()");
        assertTrue(stockFn >= 0);
        String body = script.substring(stockFn);
        int disable = body.indexOf("pm disable \"$ROCKBOX_PKG\"");
        int enableSolar = body.indexOf("pm enable \"$SOLAR_PKG\"");
        assertTrue(disable >= 0);
        assertTrue(enableSolar >= 0);
        assertTrue(disable < enableSolar);
        assertTrue(body.contains("verify_rockbox_disabled"));
        assertTrue(body.contains("refusing to enable Solar"));
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
