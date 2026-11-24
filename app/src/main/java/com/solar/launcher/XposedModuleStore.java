package com.solar.launcher;

import android.content.Context;

import com.solar.launcher.platform.PlatformAssetExtractor;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-07-05 — Root I/O for modules.list, enabled_modules.xml, and user-disable persistence.
 * Layman: reads/writes which Xposed hooks are on; one root cat per snapshot, not per module.
 * Technical: batch-parse enabled_modules.xml; cache 5s to avoid UI-thread shell storms.
 * Reversal: delete Store; ensurer owns shell blocks again (loses user-disable persistence).
 */
public final class XposedModuleStore {

    public static final String XPOSED_DATA = "/data/data/de.robv.android.xposed.installer";
    static final String MODULES_LIST = XPOSED_DATA + "/conf/modules.list";
    static final String ENABLED_PREFS = XPOSED_DATA + "/shared_prefs/enabled_modules.xml";
    /** One package per line — init + ensurer skip forced enable when listed. */
    public static final String USER_DISABLED_FILE = "/data/local/solar/xposed-user-disabled";

    private static final String PKG_POWERMENU_TEST = "com.solar.launcher.xposed.powermenu";
    private static final Pattern ENABLED_INT = Pattern.compile(
            "<int\\s+name=\"([^\"]+)\"\\s+value=\"(-?\\d+)\"\\s*/>");
    private static final long ENABLED_CACHE_MS = 5000L;

    private static volatile Map<String, Boolean> sEnabledCache;
    private static volatile long sEnabledCacheAt;

    /** Set during platform prep so enable path can find extracted module APKs. */
    private static volatile Context sResolveContext;

    private XposedModuleStore() {}

    /** Bind app context for bundled module path resolution during prep/repair. */
    public static void bindResolveContext(Context ctx) {
        sResolveContext = ctx != null ? ctx.getApplicationContext() : null;
    }

    /** Parse override file text — pure Java for unit tests. */
    public static List<String> parseUserDisabledLines(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        BufferedReader br = new BufferedReader(new StringReader(text));
        String line;
        try {
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.add(line);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Parse enabled_modules.xml text — pure Java for unit tests. */
    public static Map<String, Boolean> parseEnabledModulesXml(String xml) {
        Map<String, Boolean> out = new HashMap<String, Boolean>();
        if (xml == null || xml.isEmpty()) return out;
        Matcher m = ENABLED_INT.matcher(xml);
        while (m.find()) {
            String pkg = m.group(1);
            if (pkg == null || pkg.isEmpty()) continue;
            out.put(pkg, Boolean.valueOf(!"0".equals(m.group(2))));
        }
        return out;
    }

    /** Packages the user intentionally disabled (survives boot repair). */
    public static Set<String> readUserDisabledPackages() {
        String raw = RootShell.runCapture("cat " + USER_DISABLED_FILE + " 2>/dev/null");
        return new HashSet<String>(parseUserDisabledLines(raw));
    }

    /** True when boot init/ensurer must not force-enable this package. */
    public static boolean isUserDisabled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return readUserDisabledPackages().contains(pkg);
    }

    /** All module enabled flags — one root cat, parsed in Java (2026-07-05). */
    public static Map<String, Boolean> readAllEnabledFlags() {
        long now = System.currentTimeMillis();
        Map<String, Boolean> cached = sEnabledCache;
        if (cached != null && now - sEnabledCacheAt < ENABLED_CACHE_MS) {
            return new HashMap<String, Boolean>(cached);
        }
        String xml = RootShell.runCapture("cat " + ENABLED_PREFS + " 2>/dev/null");
        Map<String, Boolean> parsed = parseEnabledModulesXml(xml);
        sEnabledCache = parsed;
        sEnabledCacheAt = now;
        return new HashMap<String, Boolean>(parsed);
    }

    /** Drop cached enabled_modules parse — call after Apply writes prefs. */
    public static void invalidateEnabledCache() {
        sEnabledCache = null;
        sEnabledCacheAt = 0L;
    }

    /** Read enabled flag — uses batched parse, not one cat per package. */
    public static boolean isModuleEnabled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Boolean v = readAllEnabledFlags().get(pkg);
        return v != null && v.booleanValue();
    }

    /** Snapshot current enabled state for Debug UI staging — installed hooks only. */
    public static Map<String, Boolean> readEnabledSnapshot(Context ctx) {
        Map<String, Boolean> flags = readAllEnabledFlags();
        Map<String, Boolean> out = new HashMap<String, Boolean>();
        for (XposedModuleCatalog.ModuleRow row : XposedModuleCatalog.allRows(ctx)) {
            if (row.forceDisabled) {
                out.put(row.packageName, Boolean.FALSE);
            } else {
                Boolean on = flags.get(row.packageName);
                out.put(row.packageName, Boolean.valueOf(on != null && on.booleanValue()));
            }
        }
        return out;
    }

    /** Snapshot without Context — registry rows only (unit tests). */
    public static Map<String, Boolean> readEnabledSnapshot() {
        Map<String, Boolean> flags = readAllEnabledFlags();
        Map<String, Boolean> out = new HashMap<String, Boolean>();
        for (XposedModuleRegistry.Entry entry : XposedModuleRegistry.allModules()) {
            if (entry.forceDisabled) {
                out.put(entry.packageName, Boolean.FALSE);
            } else {
                Boolean on = flags.get(entry.packageName);
                out.put(entry.packageName, Boolean.valueOf(on != null && on.booleanValue()));
            }
        }
        return out;
    }

    /** Write override file + modules.list/prefs from staged toggles; returns false without root. */
    public static boolean applyStagedSelections(Context ctx, Map<String, Boolean> staged) {
        if (staged == null || !RootShell.canRun()) return false;

        Set<String> userDisabled = new LinkedHashSet<String>();
        for (Map.Entry<String, Boolean> e : staged.entrySet()) {
            XposedModuleCatalog.ModuleRow row = XposedModuleCatalog.findRow(ctx, e.getKey());
            if (row != null && row.forceDisabled) continue;
            boolean enable = e.getValue() != null && e.getValue().booleanValue();
            if (enable) {
                if (!setModuleEnabled(e.getKey())) return false;
            } else {
                if (!setModuleDisabled(e.getKey())) return false;
                if (row != null && row.required) {
                    userDisabled.add(e.getKey());
                } else if (XposedModuleCatalog.isRequiredProductionPackage(e.getKey())) {
                    userDisabled.add(e.getKey());
                }
            }
        }

        setModuleDisabled(PKG_POWERMENU_TEST);
        invalidateEnabledCache();
        return writeUserDisabledPackages(userDisabled);
    }

    /** Write staged toggles — registry-only fallback when Context unavailable. */
    public static boolean applyStagedSelections(Map<String, Boolean> staged) {
        return applyStagedSelections(null, staged);
    }

    /** Persist user override list for 99XposedInit.sh (shell-readable, no SharedPreferences). */
    public static boolean writeUserDisabledPackages(Set<String> packages) {
        if (!RootShell.canRun()) return false;
        StringBuilder sb = new StringBuilder();
        if (packages != null) {
            for (String pkg : packages) {
                if (pkg == null || pkg.isEmpty()) continue;
                sb.append(pkg).append('\n');
            }
        }
        String body = sb.toString();
        String sh = ""
                + "mkdir -p /data/local/solar; "
                + "printf '%s' '" + body.replace("'", "'\\''") + "' > " + USER_DISABLED_FILE + "; "
                + "chmod 644 " + USER_DISABLED_FILE;
        return RootShell.run(sh);
    }

    /** Enable one module — safe map merge matching 99XposedInit.sh / ensurer Jul 5 fix. */
    static boolean setModuleEnabled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String apkPath = resolveModuleApkPath(pkg);
        if (apkPath == null || apkPath.isEmpty()) return false;
        String sh = ""
                + "LIST='" + MODULES_LIST + "'; "
                + "PREFS='" + ENABLED_PREFS + "'; "
                + "PKG='" + pkg + "'; "
                + "APK='" + apkPath + "'; "
                + "grep -v \"$PKG\" \"$LIST\" 2>/dev/null | grep -v \"^$APK$\" > \"${LIST}.tmp\" "
                + "&& mv \"${LIST}.tmp\" \"$LIST\" || echo -n > \"$LIST\"; "
                + "grep -qxF \"$APK\" \"$LIST\" 2>/dev/null || echo \"$APK\" >> \"$LIST\"; "
                + "mkdir -p \"$(dirname \"$PREFS\")\"; "
                + "if ! grep -q '<?xml' \"$PREFS\" 2>/dev/null || ! grep -q '<map>' \"$PREFS\" 2>/dev/null "
                + "|| ! grep -q '^</map>' \"$PREFS\" 2>/dev/null; then "
                + "  { echo \"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\"; echo '<map>'; "
                + "    grep '<int name=' \"$PREFS\" 2>/dev/null || true; echo '</map>'; } > \"${PREFS}.repair\"; "
                + "  mv \"${PREFS}.repair\" \"$PREFS\"; "
                + "fi; "
                + "if grep -q \"name=\\\"$PKG\\\"\" \"$PREFS\" 2>/dev/null; then "
                + "  sed -i \"s|<int name=\\\"$PKG\\\" value=\\\"[^\\\"]*\\\"|<int name=\\\"$PKG\\\" value=\\\"1\\\"|\" \"$PREFS\"; "
                + "else "
                + "  grep -v '^</map>$' \"$PREFS\" > \"${PREFS}.tmp\"; "
                + "  echo \"    <int name=\\\"$PKG\\\" value=\\\"1\\\" />\" >> \"${PREFS}.tmp\"; "
                + "  echo '</map>' >> \"${PREFS}.tmp\"; "
                + "  mv \"${PREFS}.tmp\" \"$PREFS\"; "
                + "fi";
        return RootShell.run(sh);
    }

    /** Disable one module in prefs + drop from modules.list. */
    static boolean setModuleDisabled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String sh = ""
                + "PREFS='" + ENABLED_PREFS + "'; "
                + "LIST='" + MODULES_LIST + "'; "
                + "PKG='" + pkg + "'; "
                + "sed -i \"s|<int name=\\\"$PKG\\\" value=\\\"1\\\"|<int name=\\\"$PKG\\\" value=\\\"0\\\"|\" \"$PREFS\" 2>/dev/null; "
                + "grep -v \"$PKG\" \"$LIST\" 2>/dev/null > \"${LIST}.tmp\" "
                + "&& mv \"${LIST}.tmp\" \"$LIST\" || true";
        return RootShell.run(sh);
    }

    /** Prefer live pm path, then /system/app baked APK, then bundled extract cache. */
    static String resolveModuleApkPath(String pkg) {
        return resolveModuleApkPath(pkg, sResolveContext);
    }

    /** Resolve hook APK path — optional ctx enables platform-prep cache fallback. */
    static String resolveModuleApkPath(String pkg, Context ctx) {
        // 2026-07-06 — modules.list must reference /system/app for Solar helpers (not /data/app drift).
        String system = systemApkPathForPackage(pkg);
        if (system != null && new File(system).isFile()) {
            return system;
        }
        String pm = RootShell.runCapture("pm path " + pkg + " 2>/dev/null | sed -n '1s/package://p'");
        if (pm != null) {
            pm = pm.trim();
            if (pm.length() > 0 && pm.startsWith("/system/") && new File(pm).isFile()) {
                return pm;
            }
        }
        if (ctx != null) {
            String cached = cachedExtractPathForPackage(ctx, pkg);
            if (cached != null) return cached;
        }
        if (pm != null && pm.length() > 0 && new File(pm).isFile()) {
            return pm;
        }
        return system;
    }

    /** Baked /system/app path for production Solar hook packages. */
    static String systemApkPathForPackage(String pkg) {
        if ("com.solar.launcher.xposed.bridge.y2".equals(pkg)
                && new File("/system/app/SolarContextBridgeY2.apk").isFile()) {
            return "/system/app/SolarContextBridgeY2.apk";
        }
        if ("com.solar.launcher.xposed.bridge.y1".equals(pkg)
                && new File("/system/app/SolarContextBridgeY1.apk").isFile()) {
            return "/system/app/SolarContextBridgeY1.apk";
        }
        if ("com.solar.launcher.xposed.themefont".equals(pkg)
                && new File("/system/app/SolarThemeFont.apk").isFile()) {
            return "/system/app/SolarThemeFont.apk";
        }
        if ("com.solar.launcher.xposed.bridge.y2".equals(pkg)
                && new File("/system/app/SolarContextBridge.apk").isFile()) {
            return "/system/app/SolarContextBridge.apk";
        }
        return null;
    }

    /** Map package to bundled asset cache file after platform prep extract. */
    private static String cachedExtractPathForPackage(Context ctx, String pkg) {
        if ("com.solar.launcher.xposed.bridge.y1".equals(pkg)) {
            return PlatformAssetExtractor.cachedModuleApkPath(ctx, "xposed/SolarContextBridgeY1.apk");
        }
        if ("com.solar.launcher.xposed.bridge.y2".equals(pkg)) {
            return PlatformAssetExtractor.cachedModuleApkPath(ctx, "xposed/SolarContextBridgeY2.apk");
        }
        if ("com.solar.launcher.xposed.themefont".equals(pkg)) {
            return PlatformAssetExtractor.cachedModuleApkPath(ctx, "xposed/SolarThemeFont.apk");
        }
        return null;
    }

    /** Fix Installer conf ownership after root writes. */
    public static void fixInstallerOwnership() {
        RootShell.run("chown -R $(grep '^de.robv.android.xposed.installer ' /data/system/packages.list "
                + "| awk '{print $2}') " + XPOSED_DATA + " 2>/dev/null; "
                + "chmod 664 " + MODULES_LIST + " 2>/dev/null; "
                + "chmod 660 " + ENABLED_PREFS + " 2>/dev/null");
    }
}
