package com.solar.launcher.stage;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import com.solar.launcher.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-16 — Solar-only device staging from the main APK.
 * No Rockbox/JJ install path. Each step is idempotent and may SKIP without root.
 */
public final class SolarDeviceStaging {
    private static final String TAG = "SolarDeviceStaging";
    public static final String PREFS = "SOLAR_SETTINGS";
    public static final String PREF_STAGE_COMPLETE = "solar_device_stage_v1_complete";
    public static final String PREF_FIRST_RUN_SEEN = "solar_first_run_v1_seen";

    /** OpenSSL subject_hash_old for assets/certs/*.pem (API 17 cacerts names). */
    private static final String[][] CERT_MAP = {
            { "certs/isrg-root-x1.pem", "6187b673.0" },
            { "certs/isrg-root-x2.pem", "8794b4e3.0" },
            { "certs/digicert-global-root-g2.pem", "c90bc37d.0" },
            { "certs/amazon-root-ca-1.pem", "fd08c599.0" },
            { "certs/gts-root-r1.pem", "f013ecaf.0" },
            { "certs/gts-root-r4.pem", "5acf816d.0" },
    };

    public interface Listener {
        void onStepStart(int index, int total, int titleResId);
        void onStepDone(StageStep step, int index, int total);
        void onFinished(List<StageStep> steps, boolean hadRoot);
    }

    private SolarDeviceStaging() {}

    public static boolean isStageComplete(Context ctx) {
        return prefs(ctx).getBoolean(PREF_STAGE_COMPLETE, false);
    }

    public static boolean isFirstRunSeen(Context ctx) {
        return prefs(ctx).getBoolean(PREF_FIRST_RUN_SEEN, false);
    }

    public static void markFirstRunSeen(Context ctx) {
        prefs(ctx).edit().putBoolean(PREF_FIRST_RUN_SEEN, true).apply();
    }

    public static void markStageComplete(Context ctx) {
        prefs(ctx).edit().putBoolean(PREF_STAGE_COMPLETE, true).apply();
    }

    public static void clearStageComplete(Context ctx) {
        prefs(ctx).edit().putBoolean(PREF_STAGE_COMPLETE, false).apply();
        SuHelper.clearRootCache();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Run all Solar staging steps on a worker thread. Never runs Rockbox/JJ install.
     * Always ends with markStageComplete so the user is not stuck on next launch.
     */
    public static void run(final Context context, final boolean force, final Listener listener) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<StageStep> out = new ArrayList<StageStep>();
                boolean root = SuHelper.hasRoot();
                int total = 9;
                int i = 0;

                i = runOne(app, out, listener, i, total, "root",
                        R.string.stage_step_root, stageRoot(root));
                i = runOne(app, out, listener, i, total, "tls",
                        R.string.stage_step_tls, stageTlsJni(app, root));
                i = runOne(app, out, listener, i, total, "certs",
                        R.string.stage_step_certs, stageCerts(app, root));
                i = runOne(app, out, listener, i, total, "folders",
                        R.string.stage_step_folders, stageFolders());
                i = runOne(app, out, listener, i, total, "scripts",
                        R.string.stage_step_scripts, stageSolarScripts(app, root));
                i = runOne(app, out, listener, i, total, "helpers",
                        R.string.stage_step_helpers, stageHelperApks(app, root));
                i = runOne(app, out, listener, i, total, "su",
                        R.string.stage_step_su, stageSuBinary(app, root));
                i = runOne(app, out, listener, i, total, "xposed",
                        R.string.stage_step_xposed, stageXposedModules(app, root));
                i = runOne(app, out, listener, i, total, "home",
                        R.string.stage_step_home, stageDefaultHome(app));

                markStageComplete(app);
                if (listener != null) listener.onFinished(out, root);
            }
        }, "SolarDeviceStaging").start();
    }

    private static int runOne(Context app, List<StageStep> out, Listener listener,
            int index, int total, String id, int titleRes, StageStep result) {
        if (listener != null) listener.onStepStart(index, total, titleRes);
        StageStep step = result != null ? result
                : new StageStep(id, titleRes, StageResult.SKIPPED, "");
        out.add(step);
        if (listener != null) listener.onStepDone(step, index, total);
        return index + 1;
    }

    private static StageStep stageRoot(boolean root) {
        if (root) {
            return new StageStep("root", R.string.stage_step_root, StageResult.OK,
                    "su available");
        }
        return new StageStep("root", R.string.stage_step_root, StageResult.SKIPPED,
                "no root — system install steps will be skipped");
    }

    private static StageStep stageTlsJni(Context ctx, boolean root) {
        File systemSo = new File("/system/lib/libconscrypt_jni.so");
        if (systemSo.isFile() && systemSo.length() > 1000) {
            return new StageStep("tls", R.string.stage_step_tls, StageResult.OK, "already present");
        }
        if (!root) {
            return new StageStep("tls", R.string.stage_step_tls, StageResult.SKIPPED,
                    "needs root to install system JNI");
        }
        File extracted = extractNativeLib(ctx, "libconscrypt_jni.so");
        if (extracted == null) {
            // APK may load Conscrypt from the app package without system copy
            return new StageStep("tls", R.string.stage_step_tls, StageResult.SKIPPED,
                    "in-app Conscrypt only (system lib not staged)");
        }
        boolean ok = SuHelper.run("mount -o remount,rw /system 2>/dev/null; "
                + "cp " + SuHelper.shellQuote(extracted.getAbsolutePath())
                + " /system/lib/libconscrypt_jni.so && chmod 644 /system/lib/libconscrypt_jni.so");
        if (ok && systemSo.isFile()) {
            return new StageStep("tls", R.string.stage_step_tls, StageResult.OK, "installed");
        }
        return new StageStep("tls", R.string.stage_step_tls, StageResult.FAILED,
                "copy to /system/lib failed");
    }

    private static StageStep stageCerts(Context ctx, boolean root) {
        File probe = new File("/system/etc/security/cacerts/6187b673.0");
        if (probe.isFile()) {
            return new StageStep("certs", R.string.stage_step_certs, StageResult.OK,
                    "modern roots present");
        }
        if (!root) {
            return new StageStep("certs", R.string.stage_step_certs, StageResult.SKIPPED,
                    "needs root (in-app TLS still uses bundled certs)");
        }
        int installed = 0;
        File cacheDir = new File(ctx.getCacheDir(), "stage_certs");
        // noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        for (String[] pair : CERT_MAP) {
            File tmp = new File(cacheDir, pair[1]);
            if (!copyAsset(ctx, pair[0], tmp)) continue;
            boolean ok = SuHelper.run("mount -o remount,rw /system 2>/dev/null; "
                    + "mkdir -p /system/etc/security/cacerts && cp "
                    + SuHelper.shellQuote(tmp.getAbsolutePath())
                    + " /system/etc/security/cacerts/" + pair[1]
                    + " && chmod 644 /system/etc/security/cacerts/" + pair[1]);
            if (ok) installed++;
        }
        if (installed > 0) {
            return new StageStep("certs", R.string.stage_step_certs, StageResult.OK,
                    "installed " + installed + " root(s)");
        }
        return new StageStep("certs", R.string.stage_step_certs, StageResult.FAILED,
                "could not write cacerts");
    }

    private static StageStep stageFolders() {
        String[] names = { "Music", "Podcasts", "Audiobooks", "Themes" };
        String[] roots = { "/storage/sdcard0", "/storage/sdcard1" };
        int made = 0;
        for (String root : roots) {
            File r = new File(root);
            if (!r.isDirectory()) continue;
            for (String n : names) {
                File d = new File(r, n);
                if (d.isDirectory() || d.mkdirs()) made++;
            }
        }
        if (made > 0) {
            return new StageStep("folders", R.string.stage_step_folders, StageResult.OK,
                    "library folders ready");
        }
        return new StageStep("folders", R.string.stage_step_folders, StageResult.SKIPPED,
                "storage not writable yet");
    }

    private static StageStep stageSolarScripts(Context ctx, boolean root) {
        if (!root) {
            return new StageStep("scripts", R.string.stage_step_scripts, StageResult.SKIPPED,
                    "needs root");
        }
        File tmp = new File(ctx.getCacheDir(), "update-system-apk.sh");
        if (!copyAsset(ctx, "scripts/update-system-apk.sh", tmp)) {
            return new StageStep("scripts", R.string.stage_step_scripts, StageResult.SKIPPED,
                    "script asset missing");
        }
        boolean ok = SuHelper.run("mount -o remount,rw /system 2>/dev/null; "
                + "mkdir -p /system/etc/solar && cp "
                + SuHelper.shellQuote(tmp.getAbsolutePath())
                + " /system/etc/solar/update-system-apk.sh && chmod 755 /system/etc/solar/update-system-apk.sh");
        if (ok) {
            return new StageStep("scripts", R.string.stage_step_scripts, StageResult.OK,
                    "Solar system scripts");
        }
        return new StageStep("scripts", R.string.stage_step_scripts, StageResult.FAILED,
                "could not install scripts");
    }

    private static StageStep stageHelperApks(Context ctx, boolean root) {
        String[] assets = {
                "stage/keyboard.apk",
                "stage/quickmenu.apk",
                "stage/com.solar.keyboard.apk",
                "stage/com.solar.quickmenu.apk"
        };
        int found = 0;
        int installed = 0;
        for (String asset : assets) {
            File tmp = new File(ctx.getCacheDir(), new File(asset).getName());
            if (!copyAsset(ctx, asset, tmp)) continue;
            found++;
            if (!root) continue;
            // Prefer pm install for data install; system copy if pm fails
            boolean ok = SuHelper.run("pm install -r " + SuHelper.shellQuote(tmp.getAbsolutePath()));
            if (!ok) {
                String destName = asset.contains("keyboard")
                        ? "com.solar.keyboard.apk" : "com.solar.quickmenu.apk";
                ok = SuHelper.run("mount -o remount,rw /system 2>/dev/null; "
                        + "cp " + SuHelper.shellQuote(tmp.getAbsolutePath())
                        + " /system/app/" + destName + " && chmod 644 /system/app/" + destName);
            }
            if (ok) installed++;
        }
        if (found == 0) {
            return new StageStep("helpers", R.string.stage_step_helpers, StageResult.SKIPPED,
                    "helper APKs not bundled (ROM may already include them)");
        }
        if (!root) {
            return new StageStep("helpers", R.string.stage_step_helpers, StageResult.SKIPPED,
                    "found " + found + " helper(s) — needs root to install");
        }
        if (installed > 0) {
            return new StageStep("helpers", R.string.stage_step_helpers, StageResult.OK,
                    "installed " + installed + " helper(s)");
        }
        return new StageStep("helpers", R.string.stage_step_helpers, StageResult.FAILED,
                "helper install failed");
    }

    private static StageStep stageSuBinary(Context ctx, boolean root) {
        // If su already works, nothing to do
        if (root) {
            return new StageStep("su", R.string.stage_step_su, StageResult.OK,
                    "su already available");
        }
        File tmp = new File(ctx.getCacheDir(), "solar-stage-su");
        if (!copyAsset(ctx, "stage/su", tmp) && !copyAsset(ctx, "stage/su.bin", tmp)) {
            return new StageStep("su", R.string.stage_step_su, StageResult.SKIPPED,
                    "no bundled su binary");
        }
        // Without existing root we cannot install su — report clearly
        return new StageStep("su", R.string.stage_step_su, StageResult.SKIPPED,
                "bundled su present but device has no root to install it");
    }

    private static StageStep stageXposedModules(Context ctx, boolean root) {
        File xposedDir = new File(ctx.getCacheDir(), "stage_xposed");
        // noinspection ResultOfMethodCallIgnored
        xposedDir.mkdirs();
        boolean framework = new File("/data/data/de.robv.android.xposed.installer").isDirectory()
                || packageInstalled(ctx, "de.robv.android.xposed.installer")
                || packageInstalled(ctx, "org.meowcat.edxposed.manager")
                || packageInstalled(ctx, "org.lsposed.manager");
        String[] candidates = {
                "stage/xposed/input.apk",
                "stage/xposed/keycode.apk",
                "stage/xposed/solar-input.apk"
        };
        int found = 0;
        int installed = 0;
        for (String asset : candidates) {
            File tmp = new File(xposedDir, new File(asset).getName());
            if (!copyAsset(ctx, asset, tmp)) continue;
            found++;
            if (!root) continue;
            if (SuHelper.run("pm install -r " + SuHelper.shellQuote(tmp.getAbsolutePath()))) {
                installed++;
            }
        }
        if (found == 0) {
            return new StageStep("xposed", R.string.stage_step_xposed, StageResult.SKIPPED,
                    "no Xposed modules bundled");
        }
        if (!framework) {
            return new StageStep("xposed", R.string.stage_step_xposed, StageResult.SKIPPED,
                    "modules found — enable after installing Xposed/LSPosed");
        }
        if (!root) {
            return new StageStep("xposed", R.string.stage_step_xposed, StageResult.SKIPPED,
                    "needs root to install modules");
        }
        if (installed > 0) {
            return new StageStep("xposed", R.string.stage_step_xposed, StageResult.OK,
                    "installed " + installed + " — enable in Xposed");
        }
        return new StageStep("xposed", R.string.stage_step_xposed, StageResult.FAILED,
                "module install failed");
    }

    private static StageStep stageDefaultHome(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ComponentName home = new ComponentName(ctx, com.solar.launcher.MainActivity.class);
            pm.clearPackagePreferredActivities(ctx.getPackageName());
            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_HOME);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            pm.addPreferredActivity(filter, IntentFilter.MATCH_CATEGORY_EMPTY,
                    new ComponentName[] { home }, home);
            return new StageStep("home", R.string.stage_step_home, StageResult.OK,
                    "Solar set as preferred home");
        } catch (Exception e) {
            Log.w(TAG, "default home: " + e.getMessage());
            return new StageStep("home", R.string.stage_step_home, StageResult.SKIPPED,
                    "could not set preferred home (OK on some firmwares)");
        }
    }

    private static boolean packageInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static File extractNativeLib(Context ctx, String name) {
        try {
            // App nativeLibraryDir after install
            String dir = ctx.getApplicationInfo().nativeLibraryDir;
            if (dir != null) {
                File f = new File(dir, name);
                if (f.isFile() && f.length() > 1000) return f;
            }
        } catch (Exception ignored) {}
        // Try unpack from APK lib path via ClassLoader
        try {
            InputStream in = SolarDeviceStaging.class.getClassLoader()
                    .getResourceAsStream("lib/armeabi-v7a/" + name);
            if (in == null) {
                in = SolarDeviceStaging.class.getClassLoader()
                        .getResourceAsStream("lib/armeabi/" + name);
            }
            if (in == null) return null;
            File out = new File(ctx.getCacheDir(), name);
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            in.close();
            return out.length() > 1000 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean copyAsset(Context ctx, String assetPath, File out) {
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = ctx.getAssets().open(assetPath);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
            return out.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    /** Test helper — cert map size. */
    static int certMapSize() {
        return CERT_MAP.length;
    }
}
