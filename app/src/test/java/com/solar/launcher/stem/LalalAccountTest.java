package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import org.junit.Test;

/** 2026-07-18 — Stem mapping + demo key + user .stems sidecar. */
public class LalalAccountTest {

    @Test
    public void bundledDemoKeyIsPresent() {
        assertTrue(LalalAccount.hasBundledDemoKey());
        assertTrue(LalalAccount.bundledDemoKey().length() >= 8);
    }

    @Test
    public void stemLabelsMatchZones() {
        assertEquals("Vocals", LalalClient.STEM_LABELS[0]);
        assertEquals("Drums", LalalClient.STEM_LABELS[1]);
        assertEquals("Bass", LalalClient.STEM_LABELS[2]);
        assertEquals("Melody", LalalClient.STEM_LABELS[3]);
        assertEquals("vocals", LalalClient.STEM_IDS[0]);
        assertEquals("drum", LalalClient.STEM_IDS[1]);
        assertEquals("bass", LalalClient.STEM_IDS[2]);
        assertEquals(3, LalalClient.STEM_IDS.length);
        assertEquals("piano", LalalClient.OTHER_IDS[0]);
        assertEquals(0, LalalClient.zoneForId("vocals"));
        assertEquals(1, LalalClient.zoneForId("drum"));
        assertEquals(1, LalalClient.zoneForId("drums"));
        assertEquals(2, LalalClient.zoneForId("bass"));
        assertEquals(3, LalalClient.zoneForId("piano"));
        assertEquals(3, LalalClient.zoneForId("melody"));
        assertEquals(3, LalalClient.zoneForId("electric_guitar"));
        assertEquals(3, LalalClient.zoneForId("no_multistem"));
        assertEquals(LalalClient.CORE_IDS.length, LalalClient.MULTISTEM_IDS.length);
        assertTrue(LalalClient.MULTISTEM_IDS.length <= LalalClient.MULTISTEM_MAX);
        assertEquals(LalalClient.MULTISTEM_IDS.length, LalalClient.BATCH_A.length);
        assertEquals(0, LalalClient.BATCH_B.length);
        // OpenAPI multistem enum — these must NOT appear in MULTISTEM_IDS.
        for (int i = 0; i < LalalClient.MULTISTEM_IDS.length; i++) {
            String id = LalalClient.MULTISTEM_IDS[i];
            assertFalse("synthesizer".equals(id));
            assertFalse("strings".equals(id));
            assertFalse("wind".equals(id));
        }
        assertEquals("v6", LalalClient.CACHE_LAYOUT);
        assertFalse(LalalAccount.isPremixExperimental(null));
    }

    @Test
    public void userStemsDirUsesTrackBaseName() {
        File track = new File("/storage/sdcard1/Music/My Song.mp3");
        File dir = LalalClient.userStemsDir(track);
        assertEquals("My Song.stems", dir.getName());
        assertTrue(dir.getAbsolutePath().replace('\\', '/')
                .endsWith("/storage/sdcard1/Music/My Song.stems"));
    }

    @Test
    public void userStemsReadyNeedsCorePlusMelody() throws Exception {
        File tmp = File.createTempFile("stemdoc", "dir");
        assertTrue(tmp.delete());
        assertTrue(tmp.mkdirs());
        File track = new File(tmp, "Tune.mp3");
        assertTrue(track.createNewFile());
        File stems = LalalClient.userStemsDir(track);
        assertTrue(stems.mkdirs());
        assertFalse(LalalClient.userStemsReady(track));
        writeTinyMp3(new File(stems, "vocals.mp3"));
        writeTinyMp3(new File(stems, "drums.mp3"));
        writeTinyMp3(new File(stems, "bass.mp3"));
        assertFalse(LalalClient.userStemsReady(track));
        writeTinyMp3(new File(stems, "melody.mp3"));
        assertTrue(LalalClient.userStemsReady(track));
        List<LalalClient.StemFile> loaded = LalalClient.loadUserStems(track);
        assertEquals(4, loaded.size());
        File[] kids = stems.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) kids[i].delete();
        }
        stems.delete();
        track.delete();
        tmp.delete();
    }

    @Test
    public void settingsStatusWithoutPrefsIsNotConfigured() {
        String s = LalalAccount.settingsStatusLabel(null, "Not configured (demo key)", "API key set");
        assertEquals("Not configured (demo key)", s);
        assertFalse(LalalAccount.isUserConfigured(null));
        assertTrue(LalalAccount.hasUsableKey(null));
    }

    private static void writeTinyMp3(File f) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(new byte[200]);
        out.close();
    }
}
