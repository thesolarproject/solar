package com.solar.launcher.soulseek;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SoulseekClientTest {

    private static SoulseekClient.Result r(String user, String file, boolean live, boolean slot, int speed) {
        return new SoulseekClient.Result(user, file, 3 * 1024 * 1024, 192, 180, live, slot, speed);
    }

    @Test
    public void freeSlotRanksAboveFastNoSlot() {
        SoulseekClient.Result slotted = r("a", "slow.mp3", true, true, 50_000);
        SoulseekClient.Result fast = r("b", "fast.mp3", true, false, 800_000);
        assertTrue(SoulseekClient.Result.compareByDownloadReliability(slotted, fast) < 0);
    }

    @Test
    public void highSpeedRanksAboveLowSpeedWhenBothSlotted() {
        SoulseekClient.Result fast = r("a", "a.mp3", true, true, 600_000);
        SoulseekClient.Result slow = r("b", "b.mp3", true, true, 80_000);
        assertTrue(SoulseekClient.Result.compareByDownloadReliability(fast, slow) < 0);
    }

    @Test
    public void livePeerRanksAboveDistributedWhenSlotAndSpeedEqual() {
        SoulseekClient.Result live = r("a", "a.mp3", true, true, 200_000);
        SoulseekClient.Result distrib = r("b", "b.mp3", false, true, 200_000);
        assertTrue(SoulseekClient.Result.compareByDownloadReliability(live, distrib) < 0);
    }

    @Test
    public void slowUnknownPeersRankLast() {
        SoulseekClient.Result good = r("a", "a.mp3", true, true, 200_000);
        SoulseekClient.Result slow = r("b", "b.mp3", false, false, 0);
        assertTrue(SoulseekClient.Result.compareByDownloadReliability(good, slow) < 0);
        assertTrue(slow.isLikelySlowDownload());
        assertFalse(good.isLikelySlowDownload());
    }

    @Test
    public void highSpeedNoSlotIsNotMarkedSlow() {
        SoulseekClient.Result fast = r("a", "a.mp3", true, false, 800_000);
        assertFalse(fast.isLikelySlowDownload());
    }

    @Test
    public void sortPutsBestDownloadFirst() {
        List<SoulseekClient.Result> list = new ArrayList<SoulseekClient.Result>();
        list.add(r("z", "z.mp3", false, false, 0));
        list.add(r("best", "best.mp3", true, true, 500_000));
        list.add(r("mid", "mid.mp3", true, true, 100_000));
        Collections.sort(list, new Comparator<SoulseekClient.Result>() {
            @Override
            public int compare(SoulseekClient.Result a, SoulseekClient.Result b) {
                return SoulseekClient.Result.compareByDownloadReliability(a, b);
            }
        });
        assertEquals("best", list.get(0).username);
        assertEquals("mid", list.get(1).username);
        assertEquals("z", list.get(2).username);
    }

    @Test
    public void mp3ScoresAboveFlacForSamePeer() {
        int mp3 = SoulseekClient.Result.computeQualityScore("track.mp3", 3 * 1024 * 1024, true, true, 500_000);
        int flac = SoulseekClient.Result.computeQualityScore("track.flac", 3 * 1024 * 1024, true, true, 500_000);
        assertTrue(mp3 > flac);
    }

    @Test
    public void freeSlotScoresAboveNoSlot() {
        int slot = SoulseekClient.Result.computeQualityScore("a.m4a", 2 * 1024 * 1024, true, true, 200_000);
        int noslot = SoulseekClient.Result.computeQualityScore("a.m4a", 2 * 1024 * 1024, true, false, 200_000);
        assertTrue(slot > noslot);
    }

    @Test
    public void livePeerScoresAboveUnknown() {
        int live = SoulseekClient.Result.computeQualityScore("a.m4a", 2 * 1024 * 1024, true, true, 0);
        int unknown = SoulseekClient.Result.computeQualityScore("a.m4a", 2 * 1024 * 1024, false, true, 0);
        assertTrue(live > unknown);
    }

    @Test
    public void qualityStarsReflectSlotAndSpeed() {
        assertEquals("[***]", r("a", "a.mp3", true, true, 300_000).qualityStars());
        assertEquals("[** ]", r("b", "b.mp3", true, true, 0).qualityStars());
        assertEquals("[** ]", r("c", "c.mp3", true, false, 600_000).qualityStars());
        assertEquals("[*  ]", r("d", "d.mp3", false, false, 0).qualityStars());
    }

    @Test
    public void isFlacFile_detectsExtension() {
        assertTrue(SoulseekClient.Result.isFlacFile("music/track.FLAC"));
        assertFalse(SoulseekClient.Result.isFlacFile("music/track.mp3"));
    }

    @Test
    public void basenameForSaveStripsWindowsPath() {
        String base = SoulseekClient.basenameForSave(
                "@@puclh\\AllMusic\\Melvins\\Album\\Melvins - Song.mp3");
        assertEquals("Melvins - Song.mp3", base);
    }

    @Test
    public void uploadDeniedSignalsDownloadFailure() throws Exception {
        SoulseekClient client = new SoulseekClient("u", "p",
                new File(System.getProperty("java.io.tmpdir")), null, null);
        client.armDownloadForTest("peer1");
        assertFalse(client.hasDownloadFailureForTest());
        client.signalUploadDeniedForTest("peer1", "not shared");
        assertTrue(client.hasDownloadFailureForTest());
        try {
            client.checkDownloadFailure();
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Upload denied"));
        }
    }

    @Test
    public void cancelSearchDoesNotThrow() {
        SoulseekClient client = new SoulseekClient("u", "p",
                new File(System.getProperty("java.io.tmpdir")), null, null);
        client.cancelSearch();
        assertFalse(client.isTransferActive());
    }

    @Test
    public void shouldFirePartialReady_atTwentyPercent() {
        assertFalse(SoulseekClient.shouldFirePartialReady(0, 1000, false));
        assertTrue(SoulseekClient.shouldFirePartialReady(200, 1000, false));
        assertFalse(SoulseekClient.shouldFirePartialReady(200, 1000, true));
        assertFalse(SoulseekClient.shouldFirePartialReady(100, 0, false));
    }

    @Test
    public void ipToHostMatchesNicotineByteOrder() {
        assertEquals("1.2.3.4", SoulseekWire.ipToHost(0x01020304));
    }
}
