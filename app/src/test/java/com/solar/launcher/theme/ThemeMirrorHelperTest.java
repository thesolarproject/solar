package com.solar.launcher.theme;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

/** 2026-07-05 — Dual-volume theme mirror path logic and stale-skip checks. */
public class ThemeMirrorHelperTest {

    @Test
    public void mirroredThemeDir_buildsActiveThemeSubpath() {
        File sidecar = new File("/storage/sdcard1/.solar");
        File dir = ThemeMirrorHelper.mirroredThemeDir(sidecar, "Melody");
        if (!dir.getPath().replace('\\', '/').endsWith(".solar/active-theme/Melody")) {
            throw new AssertionError("unexpected mirror path: " + dir);
        }
    }

    @Test
    public void mirrorSidecarRoots_nullContextStillListsStorageRoots() {
        java.util.List<File> roots = ThemeMirrorHelper.mirrorSidecarRoots(null);
        if (roots.isEmpty()) {
            throw new AssertionError("expected at least one sidecar root");
        }
        for (File root : roots) {
            String path = root.getAbsolutePath().replace('\\', '/');
            if (!".solar".equals(root.getName())
                    && !path.endsWith("/data/local/tmp/solar-theme")) {
                throw new AssertionError("expected .solar dir: " + root);
            }
        }
    }

    @Test
    public void shouldSkipMirror_whenDestConfigNewerThanSource() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "solar-mirror-skip-test");
        tmp.mkdirs();
        File source = new File(tmp, "source");
        File dest = new File(tmp, "dest");
        source.mkdirs();
        dest.mkdirs();
        File sourceCfg = new File(source, "config.json");
        File destCfg = new File(dest, "config.json");
        writeFile(sourceCfg, "{}");
        Thread.sleep(5L);
        writeFile(destCfg, "{}");
        if (!ThemeMirrorHelper.shouldSkipMirror(source, dest)) {
            throw new AssertionError("dest should be considered fresh");
        }
        deleteTree(tmp);
    }

    @Test
    public void shouldSkipMirror_falseWhenDestMissing() {
        File source = new File("/tmp/solar-mirror-missing-src");
        File dest = new File("/tmp/solar-mirror-missing-dest");
        if (ThemeMirrorHelper.shouldSkipMirror(source, dest)) {
            throw new AssertionError("missing dest should not skip");
        }
    }

    private static void writeFile(File file, String text) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(text.getBytes("UTF-8"));
        fos.close();
    }

    private static void deleteTree(File root) {
        if (root == null || !root.exists()) return;
        if (root.isDirectory()) {
            String[] children = root.list();
            if (children != null) {
                for (String child : children) {
                    deleteTree(new File(root, child));
                }
            }
        }
        root.delete();
    }
}
