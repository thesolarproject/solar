package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Patches Rockbox-y1 launcher switch scripts at runtime via root.
 * Rockbox APK calls {@code sh /data/data/switch-to-stock.sh} — must be our rebootless script,
 * not the rockbox-y1 base image copy that swaps keylayouts and reboots.
 */
public final class Y1RomPrep {
    private static final String TAG = "Y1RomPrep";

    private Y1RomPrep() {}

    /** Overwrite Rockbox switch scripts from bundled assets (requires su). */
    public static void ensureSwitchScripts(Context context) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean legacy = isLegacyRockboxSwitchScript("/data/data/switch-to-stock.sh");
                    runSu("mkdir -p /data/data /system/etc/solar");
                    int patched = 0;
                    patched += patchSwitchScript(context, "y1/switch-to-stock.sh",
                            "/data/data/switch-to-stock.sh") ? 1 : 0;
                    patched += patchSwitchScript(context, "y1/switch-to-rockbox.sh",
                            "/data/data/switch-to-rockbox.sh") ? 1 : 0;
                    patched += patchSwitchScript(context, "y1/switch-to-stock.sh",
                            "/system/etc/solar/switch-to-stock.sh") ? 1 : 0;
                    patched += patchSwitchScript(context, "y1/switch-to-rockbox.sh",
                            "/system/etc/solar/switch-to-rockbox.sh") ? 1 : 0;
                    patched += patchSwitchScript(context, "y1/sync-rockbox-libs.sh",
                            "/system/etc/solar/sync-rockbox-libs.sh") ? 1 : 0;
                    patched += patchSwitchScript(context, "y1/sync-y1-keymap.sh",
                            "/system/etc/solar/sync-y1-keymap.sh") ? 1 : 0;
                    patched += patchSwitchScript(context, "y1/disable-rockbox-for-solar.sh",
                            "/system/etc/solar/disable-rockbox-for-solar.sh") ? 1 : 0;
                    patched += patchSwitchScript(context, "y1/solar-usb-recovery-agent.sh",
                            "/system/etc/solar/solar-usb-recovery-agent.sh") ? 1 : 0;
                    patched += patchSwitchScript(context, "y1/Y1-Rockbox.kl",
                            "/system/etc/solar/Y1-Rockbox.kl") ? 1 : 0;
                    RockboxCoexistence.ensureOnSolarStart(context);
                    RockboxDisable.ensureOnce(context);
                    LauncherSwitch.assertRockboxDisabledWhileSolarHome(context);
                    runSu("chmod 755 /data/data/switch-to-stock.sh /data/data/switch-to-rockbox.sh "
                            + "/system/etc/solar/switch-to-stock.sh /system/etc/solar/switch-to-rockbox.sh "
                            + "/system/etc/solar/sync-rockbox-libs.sh /system/etc/solar/sync-y1-keymap.sh "
                            + "/system/etc/solar/disable-rockbox-for-solar.sh "
                            + "/system/etc/solar/solar-usb-recovery-agent.sh "
                            + "2>/dev/null; chmod 644 /system/etc/solar/Y1-Rockbox.kl 2>/dev/null");
                    Log.i(TAG, "patched " + patched + "/4 switch script paths"
                            + (legacy ? " (replaced legacy rockbox-y1 script)" : ""));
                    // #region agent log
                    try {
                        JSONObject d = new JSONObject();
                        d.put("patched", patched);
                        d.put("legacyReplaced", legacy);
                        d.put("dataStockOk", !isLegacyRockboxSwitchScript("/data/data/switch-to-stock.sh"));
                        DebugAgentLog.log(context, "Y1RomPrep.ensureSwitchScripts", "switch scripts patched",
                                "H-patch", d);
                    } catch (Exception ignored) {}
                    // #endregion
                } catch (Exception e) {
                    Log.w(TAG, "switch script patch failed: " + e.getMessage());
                }
            }
        }, "Y1RomPrep").start();
    }

    /** True when rockbox-y1 base script (reboot / keylayout swap) is still present. */
    static boolean isLegacyRockboxSwitchScript(String path) {
        File f = new File(path);
        if (!f.isFile()) return false;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String lower = line.toLowerCase();
                if (lower.contains("reboot")) return true;
                if (lower.contains("keylayout") && lower.contains("cp ")) return true;
                if (lower.contains("generic.kl") || lower.contains("stock.kl")) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {}
        }
    }

    private static boolean patchSwitchScript(Context context, String assetPath, String destPath) {
        File tmp = new File(context.getCacheDir(), new File(assetPath).getName() + ".patch");
        if (!extractAsset(context, assetPath, tmp)) return false;
        return runSu("cp " + shellQuote(tmp.getAbsolutePath()) + " " + shellQuote(destPath));
    }

    private static boolean extractAsset(Context context, String assetPath, File out) {
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = context.getAssets().open(assetPath);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) fos.write(buf, 0, n);
            }
            fos.flush();
            return out.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {}
            try {
                if (fos != null) fos.close();
            } catch (Exception ignored) {}
        }
    }

    private static boolean runSu(String command) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }
}
