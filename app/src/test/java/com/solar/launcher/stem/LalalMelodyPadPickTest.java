package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 2026-07-20 — Classico-style collapse: named acoustic vs residual Melody pick.
 */
public class LalalMelodyPadPickTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Acoustic+vocals track shape: a complete guitar must beat a tiny residual
     * fragment when only one Melody pad can be retained.
     */
    @Test
    public void classicoShapePrefersStrongestMelodyCandidate() throws Exception {
        File dir = tmp.newFolder("classico_stems");
        List<LalalClient.StemFile> raw = new ArrayList<LalalClient.StemFile>();
        raw.add(stem(dir, "vocals", 0, 800_000));
        raw.add(stem(dir, "drum", 1, 1200)); // near-empty pad
        raw.add(stem(dir, "bass", 2, 1200));
        raw.add(stem(dir, "piano", 3, 1200));
        raw.add(stem(dir, "electric_guitar", 3, 1200));
        raw.add(stem(dir, "acoustic_guitar", 3, 900_000)); // the real guitar
        raw.add(stem(dir, "no_multistem", 3, 5000)); // leftover scrap
        List<LalalClient.StemFile> out = LalalClient.collapseToOnePadPerZone(raw);
        assertEquals(4, out.size());
        String picked = null;
        long pickedBytes = -1;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).zone == 3) {
                picked = out.get(i).id;
                pickedBytes = out.get(i).file.length();
            }
        }
        assertEquals("acoustic_guitar", picked);
        assertEquals(900_000L, pickedBytes);
    }

    private static LalalClient.StemFile stem(File dir, String id, int zone, int bytes)
            throws Exception {
        File f = new File(dir, id + ".mp3");
        byte[] buf = new byte[Math.max(100, bytes)];
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(buf);
        } finally {
            out.close();
        }
        return new LalalClient.StemFile(id, id, f, zone);
    }
}
