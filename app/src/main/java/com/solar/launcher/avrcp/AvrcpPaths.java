package com.solar.launcher.avrcp;

import java.io.File;
import java.io.RandomAccessFile;

/** Koensayr legacy paths — native trampolines mmap these; do not retarget without re-patching JNI. */
final class AvrcpPaths {
    static final String FILES_DIR = "/data/data/com.innioasis.y1/files";
    static final String TRACK_INFO = FILES_DIR + "/y1-track-info";
    static final String PAPP_SET = FILES_DIR + "/y1-papp-set";

    private AvrcpPaths() {}

    static void ensureFilesDir() {
        try {
            File dir = new File(FILES_DIR);
            if (!dir.isDirectory()) {
                Process p = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
                os.writeBytes("mkdir -p " + FILES_DIR + "\n");
                os.writeBytes("chmod 771 /data/data/com.innioasis.y1 " + FILES_DIR + "\n");
                os.writeBytes("exit\n");
                os.flush();
                p.waitFor();
            }
            File papp = new File(PAPP_SET);
            if (!papp.exists()) {
                RandomAccessFile raf = new RandomAccessFile(papp, "rw");
                raf.setLength(2);
                raf.close();
            }
            papp.setReadable(true, false);
            papp.setWritable(true, true);
        } catch (Throwable ignored) {}
    }

    static void writePappSet(byte repeatAvrcp, byte shuffleAvrcp) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(PAPP_SET);
            out.write(new byte[] { repeatAvrcp, shuffleAvrcp });
            out.close();
        } catch (Throwable ignored) {}
    }
}
