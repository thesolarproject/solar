package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Seeds unified Y1-Rockbox.kl to /system/usr/keylayout/{Generic,Stock,Rockbox,mtk-tpd-kpd}.kl via root.
 */
public final class Y1KeymapSync {
    private static final String TAG = "Y1KeymapSync";
    private static final String ASSET_KL = "y1/Y1-Rockbox.kl";
    private static final String ASSET_SCRIPT = "y1/sync-y1-keymap.sh";
    private static final String SYSTEM_KL = "/system/etc/solar/Y1-Rockbox.kl";
    private static final String SYSTEM_SCRIPT = "/system/etc/solar/sync-y1-keymap.sh";

    private Y1KeymapSync() {}

    /** Before Rockbox switch and on Solar boot — unified keymap for both launchers. */
    public static void ensureUnified(Context context) {
        if (context == null) return;
        File kl = extractAssetToCache(context, ASSET_KL, "Y1-Rockbox.kl");
        File script = extractAssetToCache(context, ASSET_SCRIPT, "sync-y1-keymap.sh");
        if (kl == null || script == null) {
            Log.w(TAG, "keymap assets missing");
            return;
        }
        runSu("mkdir -p /system/etc/solar /system/usr/keylayout"
                + " && mount -o remount,rw /system 2>/dev/null"
                + " && cp " + shellQuote(kl.getAbsolutePath()) + " " + shellQuote(SYSTEM_KL)
                + " && cp " + shellQuote(script.getAbsolutePath()) + " " + shellQuote(SYSTEM_SCRIPT)
                + " && chmod 644 " + shellQuote(SYSTEM_KL)
                + " && chmod 755 " + shellQuote(SYSTEM_SCRIPT)
                + " && sh " + shellQuote(SYSTEM_SCRIPT));
        logKeymapVerify(context);
        Log.i(TAG, "unified keylayout installed");
    }

    private static void logKeymapVerify(Context context) {
        String lines = runSuCapture("grep -h '^key 10[56]' "
                + "/system/usr/keylayout/Generic.kl "
                + "/system/usr/keylayout/mtk-tpd-kpd.kl "
                + "/system/usr/keylayout/mtk-kpd.kl 2>/dev/null");
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("wheelLines", lines);
            d.put("genericOk", lines.contains("MEDIA_PLAY") && lines.contains("MEDIA_PAUSE"));
            DebugAgentLog.log(context, "Y1KeymapSync.ensureUnified", "keymap verify", "H-KEYMAP", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    private static String runSuCapture(String command) {
        java.io.InputStream is = null;
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            is = proc.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            proc.waitFor();
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        } finally {
            try {
                if (is != null) is.close();
            } catch (Exception ignored) {}
        }
    }

    private static File extractAssetToCache(Context context, String asset, String name) {
        File out = new File(context.getCacheDir(), name);
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = context.getAssets().open(asset);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) fos.write(buf, 0, n);
            }
            fos.flush();
            return out.length() > 0 ? out : null;
        } catch (Exception e) {
            return null;
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
