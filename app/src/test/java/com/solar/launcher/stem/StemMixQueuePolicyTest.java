package com.solar.launcher.stem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.solar.launcher.PlayQueue;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified Stem/Mix queue spine — clear+seed, advance, hold-replace, footer index.
 * 2026-07-21
 */
public class StemMixQueuePolicyTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = File.createTempFile("stemmixq", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
    }

    private File track(String name) throws Exception {
        File f = new File(dir, name);
        FileOutputStream out = new FileOutputStream(f);
        out.write(1);
        out.close();
        return f;
    }

    @Test
    public void clearAndSeedWipesAndFills() throws Exception {
        PlayQueue q = new PlayQueue();
        q.append(PlayQueue.QueueItem.music(track("old.mp3")));
        List<File> jam = new ArrayList<File>();
        jam.add(track("a.mp3"));
        jam.add(track("b.mp3"));
        jam.add(track("c.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        assertEquals(3, q.size());
        assertEquals(0, q.index());
        assertEquals("a.mp3", q.items().get(0).file.getName());
    }

    @Test
    public void nextUpAndAdvanceSwap() throws Exception {
        PlayQueue q = new PlayQueue();
        List<File> jam = new ArrayList<File>();
        jam.add(track("live0.mp3"));
        jam.add(track("live1.mp3"));
        jam.add(track("next.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        assertEquals(2, StemMixQueuePolicy.nextUpIndex(q, StemMixQueuePolicy.STEM_LIVE_WINDOW));
        String label = StemMixQueuePolicy.nextUpLabel(q, 2);
        assertTrue(label.length() > 0);
        // DJ chain: seed (slot 0) finishes → survivor becomes the new seed at
        // index 0, incoming joins as partner at index 1, finished rotates back.
        assertTrue(StemMixQueuePolicy.applyAdvanceOrder(q, 0, 2, 2));
        assertEquals("live1.mp3", q.items().get(0).file.getName());
        assertEquals("next.mp3", q.items().get(1).file.getName());
    }

    @Test
    public void holdReplaceMovesPickIntoLive() throws Exception {
        PlayQueue q = new PlayQueue();
        List<File> jam = new ArrayList<File>();
        jam.add(track("a.mp3"));
        jam.add(track("b.mp3"));
        jam.add(track("c.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        assertTrue(StemMixQueuePolicy.applyHoldReplaceOrder(q, 1, 2, 2));
        assertEquals("c.mp3", q.items().get(1).file.getName());
    }

    @Test
    public void footerIndexingContract() {
        assertEquals(4, StemMixQueuePolicy.adapterCountWithFooter(3));
        assertTrue(StemMixQueuePolicy.isFooterIndex(3, 3));
        assertFalse(StemMixQueuePolicy.isFooterIndex(2, 3));
        assertEquals(2, StemMixQueuePolicy.clampTrackFocus(99, 3));
        assertFalse(StemMixQueuePolicy.footerVisible(true));
        assertTrue(StemMixQueuePolicy.footerVisible(false));
    }

    @Test
    public void softReplaceReadiness() {
        assertTrue(StemMixQueuePolicy.canSoftReplaceNow(true, true));
        assertFalse(StemMixQueuePolicy.canSoftReplaceNow(true, false));
    }

    @Test
    public void keepJamAliveUnderBrowseGates() {
        // Queue Add or mid-jam replace keep mixers; idle browse does not. 2026-07-21
        assertTrue(StemMixQueuePolicy.keepJamAliveUnderBrowse(true, false, false));
        assertTrue(StemMixQueuePolicy.keepJamAliveUnderBrowse(false, true, false));
        assertTrue(StemMixQueuePolicy.keepJamAliveUnderBrowse(false, false, true));
        assertFalse(StemMixQueuePolicy.keepJamAliveUnderBrowse(false, false, false));
        assertTrue(StemMixQueuePolicy.queueAppendMustNotInterruptPlayback());
    }

    /** Has Stems only on Stem queue-Add — Mix / idle append skip. 2026-07-21 */
    @Test
    public void offerHasStemsOnlyForStemQueueAppend() {
        assertTrue(StemMixQueuePolicy.offerHasStemsInQueueAppend(true, true));
        assertFalse(StemMixQueuePolicy.offerHasStemsInQueueAppend(true, false));
        assertFalse(StemMixQueuePolicy.offerHasStemsInQueueAppend(false, true));
        assertFalse(StemMixQueuePolicy.offerHasStemsInQueueAppend(false, false));
    }

    @Test
    public void shouldPairRepeatClosedJam() {
        // 0–2 → soft-restart; 3+ → Next-up advance path. 2026-07-21
        assertTrue(StemMixQueuePolicy.shouldPairRepeat(0));
        assertTrue(StemMixQueuePolicy.shouldPairRepeat(1));
        assertTrue(StemMixQueuePolicy.shouldPairRepeat(2));
        assertFalse(StemMixQueuePolicy.shouldPairRepeat(3));
        assertFalse(StemMixQueuePolicy.shouldPairRepeat(4));
        assertTrue(StemMixQueuePolicy.preferSoftRestartOverAdvance(2));
        assertFalse(StemMixQueuePolicy.preferSoftRestartOverAdvance(3));
        // Either seat soft-restarts in a pair; self-loop when advance misses. 2026-07-21
        assertTrue(StemMixQueuePolicy.pairSoftRestartsEitherSeat(2, 0));
        assertTrue(StemMixQueuePolicy.pairSoftRestartsEitherSeat(2, 1));
        assertFalse(StemMixQueuePolicy.pairSoftRestartsEitherSeat(3, 0));
        assertTrue(StemMixQueuePolicy.softRestartFinishedSeat(2, true));
        assertTrue(StemMixQueuePolicy.softRestartFinishedSeat(5, false));
        assertFalse(StemMixQueuePolicy.softRestartFinishedSeat(5, true));
        // MIX_LIVE_WINDOW=2 (Stems/Mix sanity). Was: window 3 → repeat at size≤3. 2026-07-21
        assertTrue(StemMixQueuePolicy.shouldLiveWindowRepeat(2, StemMixQueuePolicy.MIX_LIVE_WINDOW));
        assertFalse(StemMixQueuePolicy.shouldLiveWindowRepeat(3, StemMixQueuePolicy.MIX_LIVE_WINDOW));
        assertEquals(2, StemMixQueuePolicy.MIX_LIVE_WINDOW);
        assertEquals(2, StemMixQueuePolicy.liveWindow(true));
    }

    /** Queue OK owns jam; refuse unstemmed; prepared-only append; Mix Prev/Next stamp. 2026-07-21 */
    @Test
    public void queueOkAndPreparedGates() throws Exception {
        assertTrue(StemMixQueuePolicy.queueOkOwnsJam(true));
        assertFalse(StemMixQueuePolicy.queueOkOwnsJam(false));
        assertTrue(StemMixQueuePolicy.refuseUnstemmedMidStem(false));
        assertFalse(StemMixQueuePolicy.refuseUnstemmedMidStem(true));
        assertTrue(StemMixQueuePolicy.forcePreparedOnlyQueueAppend(true, true));
        assertFalse(StemMixQueuePolicy.forcePreparedOnlyQueueAppend(true, false));
        assertFalse(StemMixQueuePolicy.mayInsertMidStemJam(false));
        assertTrue(StemMixQueuePolicy.mayInsertMidStemJam(true));
        assertEquals(0, StemMixQueuePolicy.mixDeckIndexFromPrevNext(true, false));
        assertEquals(1, StemMixQueuePolicy.mixDeckIndexFromPrevNext(false, true));
        assertEquals(-1, StemMixQueuePolicy.mixDeckIndexFromPrevNext(false, false));

        PlayQueue q = new PlayQueue();
        File a = track("a.mp3");
        File b = track("b.mp3");
        File c = track("c.mp3");
        List<File> jam = new ArrayList<File>();
        jam.add(a);
        jam.add(b);
        jam.add(c);
        StemMixQueuePolicy.clearAndSeed(q, jam);
        assertTrue(StemMixQueuePolicy.stampMixDeckAndBringForward(
                q, 0, 2, StemMixQueuePolicy.MIX_LIVE_WINDOW));
        assertEquals("c.mp3", q.items().get(0).file.getName());
    }

    @Test
    public void handoffBranchPairVsAdvance() throws Exception {
        PlayQueue pair = new PlayQueue();
        List<File> two = new ArrayList<File>();
        two.add(track("a.mp3"));
        two.add(track("b.mp3"));
        StemMixQueuePolicy.clearAndSeed(pair, two);
        assertTrue(StemMixQueuePolicy.preferSoftRestartOverAdvance(pair.size()));
        assertEquals(-1, StemMixQueuePolicy.advanceSourceIndex(
                pair, StemMixQueuePolicy.STEM_LIVE_WINDOW));

        PlayQueue overflow = new PlayQueue();
        List<File> three = new ArrayList<File>();
        three.add(track("a.mp3"));
        three.add(track("b.mp3"));
        three.add(track("c.mp3"));
        StemMixQueuePolicy.clearAndSeed(overflow, three);
        assertFalse(StemMixQueuePolicy.preferSoftRestartOverAdvance(overflow.size()));
        assertEquals(2, StemMixQueuePolicy.advanceSourceIndex(
                overflow, StemMixQueuePolicy.STEM_LIVE_WINDOW));
    }

    /** Bring-forward when pick already queued; miss stays −1. 2026-07-21 */
    @Test
    public void bringForwardIfAlreadyQueued() throws Exception {
        PlayQueue q = new PlayQueue();
        File a = track("a.mp3");
        File b = track("b.mp3");
        File c = track("c.mp3");
        List<File> jam = new ArrayList<File>();
        jam.add(a);
        jam.add(b);
        jam.add(c);
        StemMixQueuePolicy.clearAndSeed(q, jam);
        assertEquals(2, StemMixQueuePolicy.indexOfMusicFile(q, c));
        assertEquals(1, StemMixQueuePolicy.bringForwardIfQueued(
                q, 1, c, StemMixQueuePolicy.STEM_LIVE_WINDOW));
        assertEquals("c.mp3", q.items().get(1).file.getName());
        assertEquals(-1, StemMixQueuePolicy.bringForwardIfQueued(
                q, 0, track("missing.mp3"), StemMixQueuePolicy.STEM_LIVE_WINDOW));
    }

    /** Prep-aware Next-up prefers ready overflow; alert when reorder fires. 2026-07-21 */
    @Test
    public void advancePreferReadyAndAlert() throws Exception {
        PlayQueue q = new PlayQueue();
        List<File> jam = new ArrayList<File>();
        jam.add(track("live0.mp3"));
        jam.add(track("live1.mp3"));
        jam.add(track("prep.mp3"));
        jam.add(track("ready.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        boolean[] ready = new boolean[] { true, true, false, true };
        int def = StemMixQueuePolicy.advanceSourceIndex(q, StemMixQueuePolicy.STEM_LIVE_WINDOW);
        assertEquals(2, def);
        int pref = StemMixQueuePolicy.advanceSourcePreferReady(
                q, StemMixQueuePolicy.STEM_LIVE_WINDOW, ready);
        assertEquals(3, pref);
        assertTrue(StemMixQueuePolicy.prepAwareReordered(def, pref));
        assertFalse(StemMixQueuePolicy.prepAwareReordered(2, 2));
        // All unready → fall open to FIFO next. 2026-07-21
        boolean[] none = new boolean[] { true, true, false, false };
        assertEquals(2, StemMixQueuePolicy.advanceSourcePreferReady(
                q, StemMixQueuePolicy.STEM_LIVE_WINDOW, none));
    }

    /** Queue position titles + wait-menu / hybrid prep helpers. 2026-07-21 */
    @Test
    public void queuePositionTitlesAndWaitMenu() throws Exception {
        assertEquals("(3) Hello", StemMixQueuePolicy.titleWithQueuePosition("Hello", 3));
        assertEquals("Hello", StemMixQueuePolicy.titleWithQueuePosition("Hello", 0));
        assertEquals("(2)", StemMixQueuePolicy.titleWithQueuePosition("", 2));

        PlayQueue q = new PlayQueue();
        File a = track("a.mp3");
        File b = track("b.mp3");
        File c = track("c.mp3");
        List<File> jam = new ArrayList<File>();
        jam.add(a);
        jam.add(b);
        jam.add(c);
        StemMixQueuePolicy.clearAndSeed(q, jam);
        assertEquals(1, StemMixQueuePolicy.oneBasedQueuePosition(q, a));
        assertEquals(3, StemMixQueuePolicy.oneBasedQueuePosition(q, c));

        assertTrue(StemMixQueuePolicy.offerStemWaitChoice(2, 1));
        assertFalse(StemMixQueuePolicy.offerStemWaitChoice(0, 2));
        assertFalse(StemMixQueuePolicy.offerStemWaitChoice(3, 0));
        assertTrue(StemMixQueuePolicy.offerWithStemsCatalogRow(true));
        assertFalse(StemMixQueuePolicy.offerWithStemsCatalogRow(false));

        List<String> names = new ArrayList<String>();
        names.add("(1) Ready A");
        names.add("(4) Ready B");
        String[] withCat = StemMixQueuePolicy.buildStemWaitMenuRows(names, "Songs with stems");
        assertEquals(3, withCat.length);
        assertEquals("Songs with stems", withCat[2]);
        String[] noCat = StemMixQueuePolicy.buildStemWaitMenuRows(names, null);
        assertEquals(2, noCat.length);

        List<File> live = StemMixQueuePolicy.liveWindowFiles(jam, 2);
        assertEquals(2, live.size());
        assertEquals(2, StemMixQueuePolicy.hybridPrepGateCount(5));
        assertEquals(1, StemMixQueuePolicy.hybridPrepGateCount(1));
        assertTrue(StemMixQueuePolicy.shouldBackgroundPrepOverflow(5, 2));
        assertFalse(StemMixQueuePolicy.shouldBackgroundPrepOverflow(2, 2));
    }

    /** Advance miss soft-restarts; overflow queue loops via swap. 2026-07-21 */
    @Test
    public void advanceMissAndQueueLoopPolicy() throws Exception {
        assertTrue(StemMixQueuePolicy.softRestartWhenAdvanceMisses(false));
        assertFalse(StemMixQueuePolicy.softRestartWhenAdvanceMisses(true));
        assertTrue(StemMixQueuePolicy.queueAdvanceLoopsViaSwap(3, 2));
        assertFalse(StemMixQueuePolicy.queueAdvanceLoopsViaSwap(2, 2));
        PlayQueue q = new PlayQueue();
        List<File> jam = new ArrayList<File>();
        jam.add(track("a.mp3"));
        jam.add(track("b.mp3"));
        jam.add(track("c.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        // Cycle through overflow twice — chain keeps Next-up forever: each seed
        // finish promotes the survivor and pulls the waiting track in. 2026-08-01
        assertTrue(StemMixQueuePolicy.applyAdvanceOrder(q, 0, 2, 2));
        assertEquals("b.mp3", q.items().get(0).file.getName());
        assertEquals("c.mp3", q.items().get(1).file.getName());
        assertTrue(StemMixQueuePolicy.applyAdvanceOrder(q, 0, 2, 2));
        assertEquals("c.mp3", q.items().get(0).file.getName());
        assertEquals("a.mp3", q.items().get(1).file.getName());
    }

    /** DJ chain rule: end of pair 1 becomes the start of pair 2. 2026-08-01 */
    @Test
    public void seedFinishChainRulePromotesSurvivor() throws Exception {
        PlayQueue q = new PlayQueue();
        List<File> jam = new ArrayList<File>();
        jam.add(track("seed.mp3"));
        jam.add(track("survivor.mp3"));
        jam.add(track("next1.mp3"));
        jam.add(track("next2.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        // Seed (slot 0) finishes → [seed, survivor, next1, next2] becomes
        // [survivor, next1, next2, seed]: the end track of pair 1 (survivor)
        // transitions into the start of pair 2; finished seed rotates to back.
        assertTrue(StemMixQueuePolicy.applyAdvanceOrder(q, 0, 2, 2));
        assertEquals("survivor.mp3", q.items().get(0).file.getName());
        assertEquals("next1.mp3", q.items().get(1).file.getName());
        assertEquals("next2.mp3", q.items().get(2).file.getName());
        assertEquals("seed.mp3", q.items().get(3).file.getName());
        // Partner (slot 1) finishes → seed stays at 0, incoming joins at 1.
        assertTrue(StemMixQueuePolicy.applyAdvanceOrder(q, 1, 2, 2));
        assertEquals("survivor.mp3", q.items().get(0).file.getName());
        assertEquals("next2.mp3", q.items().get(1).file.getName());
        assertEquals("next1.mp3", q.items().get(2).file.getName());
    }

    /** StemFM tempo-match error — harmonic lock treats half/double-time as clean. 2026-08-01 */
    @Test
    public void tempoMatchErrorHarmonicLock() {
        // Same BPM → 0 error.
        assertEquals(0f, StemMixQueuePolicy.tempoMatchError(120f, 120f), 0.0001f);
        // Half-time groove (60 vs 120) → clean match too.
        assertEquals(0f, StemMixQueuePolicy.tempoMatchError(120f, 60f), 0.0001f);
        // Double-time (240 vs 120) → clean match too.
        assertEquals(0f, StemMixQueuePolicy.tempoMatchError(120f, 240f), 0.0001f);
        // Mismatched grooves score larger than a clean pair.
        float close = StemMixQueuePolicy.tempoMatchError(120f, 128f);
        float far = StemMixQueuePolicy.tempoMatchError(120f, 95f);
        assertTrue(close < far);
        // Degenerate inputs clamp to DEFAULT_BPM rather than NaN. 2026-08-01
        float d1 = StemMixQueuePolicy.tempoMatchError(0f, 120f);
        assertTrue(Float.isFinite(d1));
        float d2 = StemMixQueuePolicy.tempoMatchError(120f, 0f);
        assertTrue(Float.isFinite(d2));
    }

    /** StemFM transition: of next two upcoming, closest tempo/beat match plays first. 2026-08-01 */
    @Test
    public void advanceClosestTempoPicksBestOfNextTwo() throws Exception {
        PlayQueue q = new PlayQueue();
        List<File> jam = new ArrayList<File>();
        jam.add(track("live0.mp3"));
        jam.add(track("live1.mp3"));
        jam.add(track("next1.mp3"));
        jam.add(track("next2.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        boolean[] ready = new boolean[] { true, true, true, true };
        // Finished song ≈ 120 BPM; next1 ≈ 95 BPM (far), next2 ≈ 120 BPM (close) → next2.
        float[] bpm = new float[] { 0f, 0f, 95f, 120f };
        int pick = StemMixQueuePolicy.advanceSourceClosestTempo(
                q, StemMixQueuePolicy.STEM_LIVE_WINDOW, ready, 120f, bpm);
        assertEquals(3, pick);
        // Swap: next1 close, next2 far → next1.
        float[] bpm2 = new float[] { 0f, 0f, 120f, 95f };
        assertEquals(2, StemMixQueuePolicy.advanceSourceClosestTempo(
                q, StemMixQueuePolicy.STEM_LIVE_WINDOW, ready, 120f, bpm2));
    }

    /** Tempo pick only looks at the next two; unready candidate never wins. 2026-08-01 */
    @Test
    public void advanceClosestTempoSkipsUnreadyAndFarDown() throws Exception {
        PlayQueue q = new PlayQueue();
        List<File> jam = new ArrayList<File>();
        jam.add(track("live0.mp3"));
        jam.add(track("live1.mp3"));
        jam.add(track("next1.mp3"));
        jam.add(track("next2.mp3"));
        jam.add(track("far.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        // next1 (idx2) not ready, next2 (idx3) ready & close → idx3.
        boolean[] ready = new boolean[] { true, true, false, true, true };
        float[] bpm = new float[] { 0f, 0f, 120f, 120f, 95f };
        assertEquals(3, StemMixQueuePolicy.advanceSourceClosestTempo(
                q, StemMixQueuePolicy.STEM_LIVE_WINDOW, ready, 120f, bpm));
        // No tempo data at all → FIFO fallback (plain Next-up head).
        // ready=null is the true-FIFO path (advanceSourcePreferReady returns next).
        int fifo = StemMixQueuePolicy.advanceSourceClosestTempo(
                q, StemMixQueuePolicy.STEM_LIVE_WINDOW, null, 120f, null);
        assertEquals(2, fifo);
        // Unknown candidate BPMs → FIFO fallback too.
        float[] unknown = new float[] { 0f, 0f, 0f, 0f, 0f };
        assertEquals(2, StemMixQueuePolicy.advanceSourceClosestTempo(
                q, StemMixQueuePolicy.STEM_LIVE_WINDOW, null, 120f, unknown));
        // With prep-aware ready (next1 unready, next2 ready) the fallback prefers
        // the first ready row — same as the existing prep-aware FIFO scan.
        assertEquals(3, StemMixQueuePolicy.advanceSourceClosestTempo(
                q, StemMixQueuePolicy.STEM_LIVE_WINDOW, ready, 120f, null));
    }

    /** Closed pair (no overflow) → tempo pick returns −1 like FIFO advance. 2026-08-01 */
    @Test
    public void advanceClosestTempoNoOverflow() throws Exception {
        PlayQueue q = new PlayQueue();
        List<File> jam = new ArrayList<File>();
        jam.add(track("a.mp3"));
        jam.add(track("b.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        assertEquals(-1, StemMixQueuePolicy.advanceSourceClosestTempo(
                q, StemMixQueuePolicy.STEM_LIVE_WINDOW,
                new boolean[] { true, true }, 120f, new float[] { 0f, 0f }));
    }

    /** Queue Mix-row adapter math — track i at 2i, Mix at 2i+1, footer at 2N−1. 2026-08-01 */
    @Test
    public void mixRowAdapterMapping() {
        // Visibility: hidden during moves / playlist edit; shown when idle. 2026-08-01
        assertFalse(StemMixQueuePolicy.mixRowsVisible(true, false));
        assertFalse(StemMixQueuePolicy.mixRowsVisible(false, true));
        assertTrue(StemMixQueuePolicy.mixRowsVisible(false, false));
        boolean[] full = StemMixQueuePolicy.allPairsMask(3);
        assertEquals(0, StemMixQueuePolicy.mixRowCount(null));
        assertEquals(2, StemMixQueuePolicy.mixRowCount(full));
        // Adapter count: 3 tracks + 2 mix rows + footer = 6; without footer = 5. 2026-08-01
        assertEquals(6, StemMixQueuePolicy.adapterCountWithMix(3, true, full));
        assertEquals(5, StemMixQueuePolicy.adapterCountWithMix(3, false, full));
        assertEquals(3, StemMixQueuePolicy.adapterCountWithMix(3, false, null));
        // Track → adapter 2i; adapter → track i. 2026-08-01
        assertEquals(0, StemMixQueuePolicy.trackToAdapter(0, full));
        assertEquals(2, StemMixQueuePolicy.trackToAdapter(1, full));
        assertEquals(4, StemMixQueuePolicy.trackToAdapter(2, full));
        assertEquals(1, StemMixQueuePolicy.adapterToTrack(2, full));
        assertEquals(1, StemMixQueuePolicy.adapterToTrack(1, null));
        // Mix rows are the odd adapters below 2N−1. 2026-08-01
        assertTrue(StemMixQueuePolicy.isMixAdapter(1, 3, full));
        assertTrue(StemMixQueuePolicy.isMixAdapter(3, 3, full));
        assertFalse(StemMixQueuePolicy.isMixAdapter(0, 3, full));
        assertFalse(StemMixQueuePolicy.isMixAdapter(5, 3, full));
        assertFalse(StemMixQueuePolicy.isMixAdapter(1, 3, null));
        assertFalse(StemMixQueuePolicy.isMixAdapter(1, 1, full));
        // Footer moves to 2N−1 when mixes visible; stays at N otherwise. 2026-08-01
        assertTrue(StemMixQueuePolicy.isFooterAdapter(5, 3, true, full));
        assertFalse(StemMixQueuePolicy.isFooterAdapter(3, 3, true, full));
        assertTrue(StemMixQueuePolicy.isFooterAdapter(3, 3, true, null));
        assertFalse(StemMixQueuePolicy.isFooterAdapter(5, 3, true, null));
        assertFalse(StemMixQueuePolicy.isFooterAdapter(5, 3, false, full));
    }

    /**
     * Sparse Mix mask — only the pair (0,1) is stem-ready; track 2 has no stems, so no
     * Mix divider sits between 1 and 2 and adapter math skips it. 2026-08-01
     */
    @Test
    public void mixRowSparseMaskSkipsUnstemmedPairs() {
        boolean[] sparse = new boolean[] { true, false };
        assertEquals(1, StemMixQueuePolicy.mixRowCount(sparse));
        // 3 tracks + 1 mix row, no footer = 4 rows.
        assertEquals(4, StemMixQueuePolicy.adapterCountWithMix(3, false, sparse));
        // trackToAdapter: t0=0, t1=2 (one Mix before), t2=3 (still one before).
        assertEquals(0, StemMixQueuePolicy.trackToAdapter(0, sparse));
        assertEquals(2, StemMixQueuePolicy.trackToAdapter(1, sparse));
        assertEquals(3, StemMixQueuePolicy.trackToAdapter(2, sparse));
        // adapterToTrack: adapter 1 is the Mix row of pair (0,1) → lower track 0.
        assertEquals(0, StemMixQueuePolicy.adapterToTrack(1, sparse));
        assertEquals(1, StemMixQueuePolicy.adapterToTrack(2, sparse));
        assertEquals(2, StemMixQueuePolicy.adapterToTrack(3, sparse));
        // Only adapter 1 is a Mix row; adapter 2 (track 1) and 3 (track 2) are not.
        assertTrue(StemMixQueuePolicy.isMixAdapter(1, 3, sparse));
        assertFalse(StemMixQueuePolicy.isMixAdapter(2, 3, sparse));
        assertFalse(StemMixQueuePolicy.isMixAdapter(3, 3, sparse));
        // Footer sits at the last adapter (4 rows → footer at 4).
        assertTrue(StemMixQueuePolicy.isFooterAdapter(4, 3, true, sparse));
        assertFalse(StemMixQueuePolicy.isFooterAdapter(3, 3, true, sparse));
    }

    /** liftPairToLiveFront — selected pair becomes live 0,1; rest keeps order. 2026-08-01 */
    @Test
    public void liftPairToLiveFrontOrdersPairFirst() throws Exception {
        PlayQueue q = new PlayQueue();
        List<File> jam = new ArrayList<File>();
        jam.add(track("a.mp3"));
        jam.add(track("b.mp3"));
        jam.add(track("c.mp3"));
        jam.add(track("d.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        // Lift pair (b,c) = tracks 1,2 to the front. 2026-08-01
        assertTrue(StemMixQueuePolicy.liftPairToLiveFront(q, 1, 2));
        assertEquals("b.mp3", q.items().get(0).file.getName());
        assertEquals("c.mp3", q.items().get(1).file.getName());
        assertEquals("a.mp3", q.items().get(2).file.getName());
        assertEquals("d.mp3", q.items().get(3).file.getName());
        // Reverse order args still ordered b,c (min first). 2026-08-01
        PlayQueue q2 = new PlayQueue();
        StemMixQueuePolicy.clearAndSeed(q2, jam);
        assertTrue(StemMixQueuePolicy.liftPairToLiveFront(q2, 3, 0));
        assertEquals("a.mp3", q2.items().get(0).file.getName());
        assertEquals("d.mp3", q2.items().get(1).file.getName());
        // Invalid indices → no-op. 2026-08-01
        assertFalse(StemMixQueuePolicy.liftPairToLiveFront(q2, 0, 0));
        assertFalse(StemMixQueuePolicy.liftPairToLiveFront(q2, -1, 1));
        assertFalse(StemMixQueuePolicy.liftPairToLiveFront(q2, 0, 99));
        assertEquals(4, q2.size());
    }

    /** liftPickToSeedFront — picked queue row becomes the live seed (index 0). 2026-08-01 */
    @Test
    public void liftPickToSeedFrontLiftsPickToFront() throws Exception {
        PlayQueue q = new PlayQueue();
        List<File> jam = new ArrayList<File>();
        jam.add(track("a.mp3"));
        jam.add(track("b.mp3"));
        jam.add(track("c.mp3"));
        jam.add(track("d.mp3"));
        StemMixQueuePolicy.clearAndSeed(q, jam);
        assertTrue(StemMixQueuePolicy.liftPickToSeedFront(q, 2));
        assertEquals("c.mp3", q.items().get(0).file.getName());
        assertEquals("a.mp3", q.items().get(1).file.getName());
        assertEquals("b.mp3", q.items().get(2).file.getName());
        assertEquals("d.mp3", q.items().get(3).file.getName());
        assertEquals(0, q.index());
        // Already at front → no-op success, size unchanged.
        assertTrue(StemMixQueuePolicy.liftPickToSeedFront(q, 0));
        // Invalid indices / null queue → no-op false.
        assertFalse(StemMixQueuePolicy.liftPickToSeedFront(q, -1));
        assertFalse(StemMixQueuePolicy.liftPickToSeedFront(q, 99));
        assertFalse(StemMixQueuePolicy.liftPickToSeedFront(null, 0));
        assertEquals(4, q.size());
    }
}
