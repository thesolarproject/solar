package com.solar.launcher.theme;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

/** 2026-07-05 — Dual-storage sidecar publish helper unit checks. */
public class SidecarPublishHelperTest {

    @Test
    public void readFirstSidecar_nullContextReturnsNull() {
        if (SidecarPublishHelper.readFirstSidecar(null, "missing.json") != null) {
            throw new AssertionError("null ctx should not find sidecar");
        }
    }

    @Test
    public void sidecarDirs_nullContextStillListsStorageRoots() {
        java.util.List<File> dirs = SidecarPublishHelper.sidecarDirs(null);
        if (dirs.isEmpty()) {
            throw new AssertionError("expected at least primary storage sidecar dir");
        }
        for (File dir : dirs) {
            String path = dir.getAbsolutePath().replace('\\', '/');
            if (!".solar".equals(dir.getName())
                    && !path.endsWith("/data/local/tmp/solar-theme")) {
                throw new AssertionError("each dir should end with .solar: " + dir);
            }
        }
    }

    @Test
    public void copyFile_roundTrip() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "solar-sidecar-copy-test");
        tmp.mkdirs();
        File source = new File(tmp, "source.bin");
        File dest = new File(tmp, "dest.bin");
        byte[] payload = new byte[] {0x01, 0x02, 0x03};
        FileOutputStream fos = new FileOutputStream(source);
        fos.write(payload);
        fos.close();
        SidecarPublishHelper.copyFile(source, dest);
        if (dest.length() != payload.length) {
            throw new AssertionError("dest size mismatch");
        }
        source.delete();
        dest.delete();
        tmp.delete();
    }
}
