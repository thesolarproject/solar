package com.solar.launcher.soulseek;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SoulseekPartialStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void exactPeerAndPathResumeTheSamePartial() throws Exception {
        File dir = temporary.newFolder("music");
        SoulseekPartialStore.Entry first =
                SoulseekPartialStore.select(dir, "Albums\\Track.mp3", "peer", 100);
        writeBytes(first.partialFile, 37);

        SoulseekPartialStore.Entry resumed =
                SoulseekPartialStore.select(dir, "Albums\\Track.mp3", "PEER", 100);
        assertEquals(first.partialFile, resumed.partialFile);
        assertEquals(37, SoulseekPartialStore.prepareResume(resumed));
    }

    @Test
    public void differentSourceNeverAppendsToSameBasename() throws Exception {
        File dir = temporary.newFolder("music");
        SoulseekPartialStore.Entry first =
                SoulseekPartialStore.select(dir, "A\\Track.mp3", "peer-a", 100);
        writeBytes(first.partialFile, 37);

        SoulseekPartialStore.Entry second =
                SoulseekPartialStore.select(dir, "B\\Track.mp3", "peer-b", 100);
        assertNotEquals(first.partialFile, second.partialFile);
        assertEquals("Track_1.mp3.part", second.partialFile.getName());
    }

    @Test
    public void changedAdvertisedSizeDoesNotResumeOldBytes() throws Exception {
        File dir = temporary.newFolder("music");
        SoulseekPartialStore.Entry first =
                SoulseekPartialStore.select(dir, "Track.mp3", "peer", 100);
        writeBytes(first.partialFile, 37);

        SoulseekPartialStore.Entry second =
                SoulseekPartialStore.select(dir, "Track.mp3", "peer", 200);
        assertNotEquals(first.partialFile, second.partialFile);
    }

    @Test
    public void oversizedPartialIsResetBeforeOffsetRequest() throws Exception {
        File dir = temporary.newFolder("music");
        SoulseekPartialStore.Entry entry =
                SoulseekPartialStore.select(dir, "Track.mp3", "peer", 10);
        writeBytes(entry.partialFile, 20);
        assertEquals(0, SoulseekPartialStore.prepareResume(entry));
        assertEquals(0, entry.partialFile.length());
    }

    @Test
    public void finishRequiresExactLengthAndPublishesAtomically() throws Exception {
        File dir = temporary.newFolder("music");
        SoulseekPartialStore.Entry entry =
                SoulseekPartialStore.select(dir, "Track.mp3", "peer", 10);
        writeBytes(entry.partialFile, 10);

        File complete = SoulseekPartialStore.finish(entry);
        assertEquals("Track.mp3", complete.getName());
        assertTrue(complete.isFile());
        assertFalse(entry.partialFile.exists());
        assertFalse(entry.metadataFile.exists());
    }

    @Test
    public void storageReservationUsesOverflowSafeComparison() {
        assertTrue(SoulseekPartialStore.hasEnoughSpace(20, 10, 10));
        assertFalse(SoulseekPartialStore.hasEnoughSpace(19, 10, 10));
        assertFalse(SoulseekPartialStore.hasEnoughSpace(
                Long.MAX_VALUE, Long.MAX_VALUE, 1));
        assertTrue(SoulseekPartialStore.hasEnoughSpace(0, Long.MAX_VALUE, 1));
    }

    private static void writeBytes(File file, int count) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            for (int i = 0; i < count; i++) out.write(i);
        } finally {
            out.close();
        }
    }
}
