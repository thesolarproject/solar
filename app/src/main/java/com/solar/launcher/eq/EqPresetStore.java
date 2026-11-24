package com.solar.launcher.eq;

import android.content.Context;
import android.content.SharedPreferences;

import com.solar.launcher.DeviceFeatures;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 2026-07-15 — Persist active EQ curve + named presets; scan SD for importable files.
 * Layman: remember your EQ knobs and find presets on the card.
 * Reversal: clear SharedPreferences keys eq_* and delete Solar/EQ/.
 */
public final class EqPresetStore {

    public static final String PREFS = "solar_eq";
    private static final String KEY_ENABLED = "eq_enabled";
    private static final String KEY_PREAMP = "eq_preamp";
    private static final String KEY_GAINS = "eq_gains";
    private static final String KEY_ACTIVE_NAME = "eq_active_name";

    private EqPresetStore() {}

    public static EqBandModel loadActive(Context ctx) {
        EqBandModel model = new EqBandModel();
        if (ctx == null) return model;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        model.setEnabled(p.getBoolean(KEY_ENABLED, true));
        model.setPreampDb(p.getFloat(KEY_PREAMP, 0f));
        String gains = p.getString(KEY_GAINS, null);
        if (gains != null && gains.length() > 0) {
            String[] parts = gains.split(",");
            float[] g = new float[EqBandModel.BAND_COUNT];
            for (int i = 0; i < EqBandModel.BAND_COUNT && i < parts.length; i++) {
                try {
                    g[i] = Float.parseFloat(parts[i].trim());
                } catch (NumberFormatException e) {
                    g[i] = 0f;
                }
            }
            model.setGains(g);
        }
        return model;
    }

    public static void saveActive(Context ctx, EqBandModel model) {
        if (ctx == null || model == null) return;
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < EqBandModel.BAND_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.2f", model.getGainDb(i)));
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, model.isEnabled())
                .putFloat(KEY_PREAMP, model.getPreampDb())
                .putString(KEY_GAINS, sb.toString())
                .commit();
    }

    public static String getActiveName(Context ctx) {
        if (ctx == null) return null;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_NAME, null);
    }

    public static void setActiveName(Context ctx, String name) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE_NAME, name)
                .commit();
    }

    /**
     * Solar/EQ folder for new saves — follows Primary storage pref.
     * 2026-07-15 — Was primary-only; imports still scan all volumes via {@link #scanImportDirs()}.
     */
    public static File getSolarEqDir() {
        File root = DeviceFeatures.getNewMediaRoot(null);
        File dir = new File(root, "Solar/EQ");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getSolarEqDir(Context ctx) {
        File root = DeviceFeatures.getNewMediaRoot(ctx);
        File dir = new File(root, "Solar/EQ");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Save model as Rockbox .cfg under Solar/EQ/{safeName}.cfg. */
    public static File saveNamedPreset(EqBandModel model, String name) throws Exception {
        return saveNamedPreset(null, model, name);
    }

    public static File saveNamedPreset(Context ctx, EqBandModel model, String name) throws Exception {
        String safe = sanitizeName(name);
        File out = new File(getSolarEqDir(ctx), safe + ".cfg");
        String cfg = EqPresetImporter.toRockboxCfg(model);
        writeUtf8(out, cfg);
        return out;
    }

    /** Folders to scan for .cfg / FixedBandEQ / GraphicEQ files. */
    public static List<File> scanImportDirs() {
        List<File> dirs = new ArrayList<File>();
        List<File> roots = DeviceFeatures.getBrowsableStorageRoots();
        if (roots == null) {
            roots = new ArrayList<File>();
            roots.add(DeviceFeatures.getPrimaryStorageRoot());
        }
        for (File root : roots) {
            if (root == null) continue;
            addIfDir(dirs, new File(root, "Solar/EQ"));
            addIfDir(dirs, new File(root, "Music/EQ"));
        }
        return dirs;
    }

    /** All importable preset files, sorted by name. */
    public static List<File> listImportableFiles() {
        List<File> out = new ArrayList<File>();
        for (File dir : scanImportDirs()) {
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (File f : kids) {
                if (!f.isFile()) continue;
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".cfg") || n.endsWith(".txt")) out.add(f);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    private static void addIfDir(List<File> dirs, File d) {
        if (d != null && d.isDirectory() && !dirs.contains(d)) dirs.add(d);
    }

    private static String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) return "preset";
        String s = name.trim().replaceAll("[^A-Za-z0-9._\\- ]", "_");
        if (s.length() > 48) s = s.substring(0, 48);
        return s;
    }

    private static void writeUtf8(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        try {
            w.write(text);
        } finally {
            try {
                w.close();
            } catch (Exception ignored) {
            }
        }
    }
}
