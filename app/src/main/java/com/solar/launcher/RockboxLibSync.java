package com.solar.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Re-extract org.rockbox JNI codec libs from /system/app/org.rockbox.apk when stale.
 * ponytail: must stay aligned with rockbox-y1 APK layout — see .cursor/rules/rockbox-y1-coexistence.mdc
 */
public final class RockboxLibSync {
    private static final String TAG = "RockboxLibSync";
    private static final String ASSET = "y1/sync-rockbox-libs.sh";
    private static final String SYSTEM_SCRIPT = "/system/etc/solar/sync-rockbox-libs.sh";

    private RockboxLibSync() {}

    /** Install bundled sync script to /system/etc/solar (root) and run it. */
    public static boolean syncBeforeRockboxSwitch(Context context) {
        if (!new File("/system/app/org.rockbox.apk").exists()) return true;
        File runnable = ensureRunnableScript(context);
        if (runnable == null) {
            Log.w(TAG, "sync script unavailable");
            return false;
        }
        boolean ok = runSu("sh " + shellQuote(runnable.getAbsolutePath()));
        if (ok) Log.i(TAG, "native libs synced before Rockbox switch");
        return ok;
    }

    /** Boot-time: seed script to system, sync if APK changed. */
    static void ensureOnSolarStart(Context context) {
        if (!new File("/system/app/org.rockbox.apk").exists()) return;
        ensureRunnableScript(context);
        runSu("[ -f " + shellQuote(SYSTEM_SCRIPT) + " ] && sh " + shellQuote(SYSTEM_SCRIPT));
    }

    /** Prefer /system/etc/solar copy; fall back to cache after seeding. */
    private static File ensureRunnableScript(Context context) {
        runSu("mkdir -p /system/etc/solar");
        File cached = new File(context.getCacheDir(), "sync-rockbox-libs.sh");
        if (!extractAsset(context, cached)) return null;
        runSu("cp " + shellQuote(cached.getAbsolutePath()) + " " + shellQuote(SYSTEM_SCRIPT)
                + " && chmod 755 " + shellQuote(SYSTEM_SCRIPT));
        if (new File(SYSTEM_SCRIPT).exists()) return new File(SYSTEM_SCRIPT);
        runSu("chmod 755 " + shellQuote(cached.getAbsolutePath()));
        return cached;
    }

    private static boolean extractAsset(Context context, File out) {
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = context.getAssets().open(ASSET);
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
