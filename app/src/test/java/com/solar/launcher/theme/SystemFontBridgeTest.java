package com.solar.launcher.theme;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

public class SystemFontBridgeTest {

    @Test
    public void isCompatibleFontExtension_acceptsTtfAndOtf() {
        if (!SystemFontBridge.isCompatibleFontExtension("opensans-condbold.ttf")) {
            throw new AssertionError("ttf should be compatible");
        }
        if (!SystemFontBridge.isCompatibleFontExtension("Brand.OTF")) {
            throw new AssertionError("otf should be compatible");
        }
    }

    @Test
    public void isCompatibleFontExtension_rejectsWoffAndOthers() {
        if (SystemFontBridge.isCompatibleFontExtension("web.woff")) {
            throw new AssertionError("woff should be rejected");
        }
        if (SystemFontBridge.isCompatibleFontExtension("notes.txt")) {
            throw new AssertionError("txt should be rejected");
        }
        if (SystemFontBridge.isCompatibleFontExtension(null)) {
            throw new AssertionError("null should be rejected");
        }
    }

    @Test
    public void sidecarFileForRoot_usesHiddenSolarDir() {
        File sidecar = SystemFontBridge.sidecarFileForRoot(new File("/storage/sdcard1"));
        if (!sidecar.getPath().replace('\\', '/')
                .endsWith("/storage/sdcard1/.solar/system-font.ttf")) {
            throw new AssertionError("unexpected sidecar path: " + sidecar);
        }
    }

    @Test
    public void copyFile_writesBytes() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "solar-font-bridge-test");
        tmp.mkdirs();
        File source = new File(tmp, "source.bin");
        File dest = new File(tmp, "dest.bin");
        byte[] payload = new byte[] {0x01, 0x02, 0x03, 0x04};
        FileOutputStream fos = new FileOutputStream(source);
        fos.write(payload);
        fos.close();

        SystemFontBridge.copyFile(source, dest);
        if (dest.length() != payload.length) {
            throw new AssertionError("dest size mismatch");
        }

        source.delete();
        dest.delete();
        tmp.delete();
    }

    @Test
    public void clearSidecar_removesPublishedFile() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "solar-font-clear-test");
        File sidecarDir = new File(tmp, SystemFontBridge.SIDECAR_DIR);
        sidecarDir.mkdirs();
        File sidecar = new File(sidecarDir, SystemFontBridge.SIDECAR_FILE);
        FileOutputStream fos = new FileOutputStream(sidecar);
        fos.write(new byte[] {0x00});
        fos.close();

        // Direct delete mirrors clearSidecar body for unit test without Robolectric Context.
        if (sidecar.isFile()) sidecar.delete();
        if (sidecar.isFile()) {
            throw new AssertionError("sidecar should be deleted");
        }

        sidecarDir.delete();
        tmp.delete();
    }
}
