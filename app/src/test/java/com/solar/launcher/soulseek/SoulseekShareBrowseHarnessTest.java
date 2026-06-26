package com.solar.launcher.soulseek;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * End-to-end share-list encoding: Reach index → zlib → Nicotine+ parser (local pynicotine).
 */
public class SoulseekShareBrowseHarnessTest {

    @Test
    public void nicotineParsesShareListWithoutNatAnnounce() throws Exception {
        File music = new File(System.getProperty("java.io.tmpdir"), "reach_share_harness_music");
        deleteTree(music);
        File trackDir = new File(music, "Artist");
        trackDir.mkdirs();
        File track = new File(trackDir, "song.mp3");
        FileOutputStream fos = new FileOutputStream(track);
        fos.write(new byte[256]);
        fos.close();

        SoulseekShareIndex idx = new SoulseekShareIndex();
        idx.scan("ReachTestUser", music, null);

        SoulseekSharePolicy policy = new SoulseekSharePolicy();
        policy.setReachMasterEnabled(true);
        policy.setUserEnabled(true);
        policy.update(true, false, false);
        if (policy.announceShares()) throw new AssertionError("no server announce without NAT");
        if (!policy.serveSharesToPeer()) throw new AssertionError("should serve browse");

        byte[] raw = idx.buildShareListUncompressed();
        byte[] zlib = SoulseekShareIndex.zlibCompress(raw);
        List<SoulseekWire.BrowseFolder> reachParsed = SoulseekWire.parseShareList(zlib);
        int reachFiles = countFiles(reachParsed);
        if (reachFiles != 1) throw new AssertionError("reach parse files=" + reachFiles);

        File zlibFile = File.createTempFile("reach_shares_", ".zlib");
        try {
            FileOutputStream zout = new FileOutputStream(zlibFile);
            zout.write(zlib);
            zout.close();
            int nicotineFiles = runNicotineParser(zlibFile);
            if (nicotineFiles != 1) {
                throw new AssertionError("nicotine parse files=" + nicotineFiles);
            }
        } finally {
            zlibFile.delete();
            deleteTree(music);
        }
    }

    private static int runNicotineParser(File zlibFile) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "python3", findNicotineScript(), zlibFile.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        InputStream in = proc.getInputStream();
        StringBuilder out = new StringBuilder();
        byte[] buf = new byte[256];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.append(new String(buf, 0, n, "UTF-8"));
        }
        int code = proc.waitFor();
        if (code != 0) {
            throw new AssertionError("nicotine parser exit " + code + ": " + out);
        }
        return Integer.parseInt(out.toString().trim());
    }

    private static String findNicotineScript() throws Exception {
        java.net.URL url = SoulseekShareBrowseHarnessTest.class
                .getResource("/nicotine_parse_shares.py");
        if (url == null) throw new AssertionError("nicotine_parse_shares.py missing");
        return new File(url.toURI()).getAbsolutePath();
    }

    private static int countFiles(List<SoulseekWire.BrowseFolder> folders) {
        int n = 0;
        if (folders == null) return 0;
        for (SoulseekWire.BrowseFolder f : folders) {
            if (f.files != null) n += f.files.size();
        }
        return n;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
