package com.solar.launcher.stem;

import com.solar.launcher.PlayQueue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Stem/Mix share Music’s {@link PlayQueue} spine — clear+seed, live window, Next-up, hold-replace.
 * Layman: jam songs live in the same play queue as Now Playing; finished slots pull the next row.
 * Technical: Stem live=2 · Mix live=3; footer Add is adapter-only (not a queue index).
 * Was: jam tracks only in StemSession/MixSession slots. Reversal: ignore these helpers; keep slot-only lists.
 * 2026-07-21
 */
public final class StemMixQueuePolicy {
    /** StemFM-style live pair. 2026-07-21 */
    public static final int STEM_LIVE_WINDOW = 2;
    /**
     * Mix live decks (two discs). 2026-07-21 Stems/Mix sanity
     * Was: 3 (triple faders). Reversal: MIX_LIVE_WINDOW = 3.
     */
    public static final int MIX_LIVE_WINDOW = 2;

    private StemMixQueuePolicy() {}

    /**
     * Clear play queue and seed jam track files (session start).
     * Layman: wipe the old queue and drop in the songs you’re about to jam.
     * 2026-07-21
     */
    public static void clearAndSeed(PlayQueue queue, List<File> tracks) {
        if (queue == null) return;
        queue.clear();
        if (tracks == null) return;
        for (int i = 0; i < tracks.size(); i++) {
            File f = tracks.get(i);
            if (f != null && f.isFile()) {
                queue.append(PlayQueue.QueueItem.music(f));
            }
        }
        if (!queue.isEmpty()) queue.setIndex(0);
    }

    /**
     * Clear+seed from a sparse slot array (Mix assign / Stem pick).
     * Layman: only filled pads become queue rows, in pad order.
     * 2026-07-21
     */
    public static void clearAndSeedSlots(PlayQueue queue, File[] slots) {
        List<File> files = new ArrayList<File>();
        if (slots != null) {
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != null && slots[i].isFile()) files.add(slots[i]);
            }
        }
        clearAndSeed(queue, files);
    }

    /** How many live slots this mode owns (2 Stem / 3 Mix). 2026-07-21 */
    public static int liveWindow(boolean mixMode) {
        return mixMode ? MIX_LIVE_WINDOW : STEM_LIVE_WINDOW;
    }

    /**
     * First queue index past the live window — the Next-up row (−1 if none).
     * Layman: song waiting in line after the ones currently on pads.
     * 2026-07-21
     */
    public static int nextUpIndex(PlayQueue queue, int liveWindow) {
        if (queue == null || liveWindow < 1) return -1;
        if (queue.size() <= liveWindow) return -1;
        return liveWindow;
    }

    /**
     * Display name for Next-up (empty when queue has no waiting track).
     * 2026-07-21
     */
    public static String nextUpLabel(PlayQueue queue, int liveWindow) {
        int i = nextUpIndex(queue, liveWindow);
        if (i < 0) return "";
        PlayQueue.QueueItem item = queue.items().get(i);
        return displayName(item);
    }

    /**
     * Pair/triple advance: queue index that should soft-replace a finished live slot.
     * Layman: when a pad’s song ends, pull the next waiting track into that pad.
     * Technical: nextUpIndex while size &gt; liveWindow; else −1 (pair-repeat or survivor).
     * 2026-07-21
     */
    public static int advanceSourceIndex(PlayQueue queue, int liveWindow) {
        return nextUpIndex(queue, liveWindow);
    }

    /**
     * Next-up advance preferring stem-ready overflow tracks (prep-aware).
     * Layman: skip songs still cooking when a ready one is waiting further down.
     * Technical: scan from nextUpIndex; first stemsReady[i]==true wins; else default next.
     * Was: always advanceSourceIndex (FIFO). Reversal: return advanceSourceIndex only.
     * @param stemsReady per-queue-index readiness (null → FIFO)
     * 2026-07-21
     */
    public static int advanceSourcePreferReady(PlayQueue queue, int liveWindow,
            boolean[] stemsReady) {
        int next = nextUpIndex(queue, liveWindow);
        if (next < 0) return -1;
        if (stemsReady == null || queue == null) return next;
        for (int i = next; i < queue.size(); i++) {
            if (i < stemsReady.length && stemsReady[i]) return i;
        }
        return next;
    }

    /**
     * True when prep-aware pick jumped past the plain Next-up head.
     * Layman: Up Next dial should flash red when readiness reordered the line.
     * 2026-07-21
     */
    public static boolean prepAwareReordered(int defaultNextIndex, int chosenIndex) {
        return defaultNextIndex >= 0 && chosenIndex >= 0 && chosenIndex != defaultNextIndex;
    }

    /**
     * StemFM-style Next-up: of the next two upcoming songs, play the one whose
     * tempo/beat grid best matches the song that just finished.
     * Layman: when a pad's song ends, peek at the next two in line and pull in
     * whichever grooves closest to the one that just ended — not just the first row.
     * Technical: scan candidate indexes [nextUpIndex, nextUpIndex+1]; prefer
     * stems-ready candidates (existing prep-aware rule); score each eligible
     * candidate by {@link #tempoMatchError} — DJ harmonic lock means a
     * half/double-time groove scores as a clean match. Unknown BPMs (≤ 30) are
     * skipped. Falls back to plain FIFO {@link #advanceSourcePreferReady} when
     * no candidate has usable tempo data or the window has only one row.
     * 2026-08-01
     */
    public static int advanceSourceClosestTempo(PlayQueue queue, int liveWindow,
            boolean[] stemsReady, float finishedBpm, float[] candidateBpm) {
        int next = nextUpIndex(queue, liveWindow);
        if (next < 0) return -1;
        if (queue == null || queue.size() <= next) return -1;
        int last = Math.min(next + 1, queue.size() - 1);
        float fBpm = finishedBpm > 30f ? finishedBpm : StemBpm.DEFAULT_BPM;
        int best = -1;
        float bestErr = Float.MAX_VALUE;
        for (int i = next; i <= last; i++) {
            boolean readyOk = stemsReady == null
                    || (i < stemsReady.length && stemsReady[i]);
            if (!readyOk) continue;
            float cBpm = candidateBpm != null && i < candidateBpm.length
                    ? candidateBpm[i] : 0f;
            if (cBpm <= 30f) continue;
            float err = tempoMatchError(fBpm, cBpm);
            if (err < bestErr) {
                bestErr = err;
                best = i;
            }
        }
        if (best >= 0) return best;
        return advanceSourcePreferReady(queue, liveWindow, stemsReady);
    }

    /**
     * Tempo-match error of a candidate vs the finished song (lower = better).
     * Uses DJ harmonic lock so a half/double-time groove scores as a clean match.
     * Layman: 120 vs 120 → 0 error; 120 vs 60 (half-time) → 0 error; 120 vs 95 → larger.
     * Technical: |harmonicRateToMatch(finished, candidate) − 1|.
     * 2026-08-01
     */
    public static float tempoMatchError(float finishedBpm, float candidateBpm) {
        float f = finishedBpm > 30f ? finishedBpm : StemBpm.DEFAULT_BPM;
        float c = candidateBpm > 30f ? candidateBpm : StemBpm.DEFAULT_BPM;
        return Math.abs(StemBpm.harmonicRateToMatch(f, c) - 1f);
    }

    /**
     * Closed Stem pair (or single): finished song soft-restarts — no Next-up advance.
     * Layman: with only one or two songs queued, a finished track loops so unequal lengths keep mixing.
     * Technical: queueSize ≤ {@link #STEM_LIVE_WINDOW} → seek+fade on existing mixer, not softReplace.
     * Was: size≤2 fell through to survivor-only handoff (killed the short song). Reversal: always false.
     * 2026-07-21
     */
    public static boolean shouldPairRepeat(int queueSize) {
        return queueSize <= STEM_LIVE_WINDOW;
    }

    /**
     * Mix/Stem: restart ended live deck when queue has no overflow past the live window.
     * Layman: nothing waiting in line → that pad’s song starts over; partner keeps going.
     * 2026-07-21
     */
    public static boolean shouldLiveWindowRepeat(int queueSize, int liveWindow) {
        if (liveWindow < 1) return queueSize <= 0;
        return queueSize <= liveWindow;
    }

    /**
     * Song-end branch: soft-restart vs queue advance (Stem host + tests).
     * Layman: pick “loop this song” when the jam is a closed pair; else pull Next-up.
     * Technical: preferSoftRestart when shouldPairRepeat; else advance iff nextUpIndex ≥ 0.
     * 2026-07-21
     */
    public static boolean preferSoftRestartOverAdvance(int queueSize) {
        return shouldPairRepeat(queueSize);
    }

    /**
     * Soft-restart the finished live seat when the jam is a closed pair/single,
     * or when Next-up advance failed / nothing waiting.
     * Layman: that song fades back into itself; partner keeps playing.
     * Technical: shouldPairRepeat OR !advanceGotFile — never silence/hard-cut dead end.
     * Was: only pair-repeat; advance miss could mute. Reversal: return shouldPairRepeat only.
     * 2026-07-21
     */
    public static boolean softRestartFinishedSeat(int queueSize, boolean advanceReturnedFile) {
        if (preferSoftRestartOverAdvance(queueSize)) return true;
        return !advanceReturnedFile;
    }

    /**
     * Both pair seats use the same soft-restart rule (finished index does not matter).
     * Layman: song A or B ending both loop the same way in a 1–2 track jam.
     * 2026-07-21
     */
    public static boolean pairSoftRestartsEitherSeat(int queueSize, int finishedSongIndex) {
        if (finishedSongIndex < 0) return false;
        return preferSoftRestartOverAdvance(queueSize);
    }

    /**
     * After advancing slot {@code liveSlot} with queue item at {@code sourceIndex},
     * move that item into the live window and shift the finished track toward Next-up.
     * Layman: the new song takes the pad’s seat; the old one goes to the waiting line.
     * Technical: swap/move so indices [0..liveWindow) stay the live set; persist via caller.
     * 2026-07-21
     */
    public static boolean applyAdvanceOrder(PlayQueue queue, int liveSlot, int sourceIndex,
            int liveWindow) {
        if (queue == null) return false;
        if (liveSlot < 0 || liveSlot >= liveWindow) return false;
        if (sourceIndex < liveWindow || sourceIndex >= queue.size()) return false;
        // DJ chain rule: the END track of pair 1 becomes the START track of pair 2
        // ([t0,t1] → [t1,t2] → [t2,t3]…). When the SEED (slot 0) finishes, the
        // survivor keeps playing and becomes the new seed — the incoming source
        // joins as the new partner at index 1 and the finished seed rotates to the
        // back of the queue (keeps the Next-up loop forever). Partner-finish keeps
        // the plain swap (seed stays put, incoming replaces the partner seat).
        // Was: swap(liveSlot, sourceIndex) inverted the chain on seed finish.
        // Reversal: seed-finish rotate; partner-finish swap. 2026-08-01
        if (liveSlot == 0 && liveWindow >= 2 && queue.size() > 1) {
            java.util.List<PlayQueue.QueueItem> items = queue.items();
            PlayQueue.QueueItem finished = items.get(0);
            PlayQueue.QueueItem incoming = items.get(sourceIndex);
            queue.removeAt(sourceIndex);
            queue.removeAt(0); // finished seed leaves the live window
            // Survivors (old 1..liveWindow-1) slid left; seat the source at the
            // last live index, then rotate the finished seed to the back.
            java.util.List<PlayQueue.QueueItem> live = queue.items();
            live.add(Math.min(liveWindow - 1, live.size()), incoming);
            live.add(finished);
            queue.setIndex(0);
            return true;
        }
        // Partner (or deeper seat) finished: swap keeps the seed as index 0.
        queue.swap(liveSlot, sourceIndex);
        return true;
    }

    /**
     * Hold-replace: move chosen queue track into a live slot; displaced track becomes Next-up head.
     * Layman: you pick another queued song for this pad; the old pad song waits next.
     * Technical: if pick already in live window, swap; else move pick → liveSlot then order Next-up.
     * Was: library browse reassign only. Reversal: drop; keep onRequestStemSongReassign browse.
     * 2026-07-21
     */
    public static boolean applyHoldReplaceOrder(PlayQueue queue, int liveSlot, int pickIndex,
            int liveWindow) {
        if (queue == null) return false;
        int size = queue.size();
        if (liveSlot < 0 || liveSlot >= liveWindow || liveSlot >= size) return false;
        if (pickIndex < 0 || pickIndex >= size) return false;
        if (pickIndex == liveSlot) return true;
        if (pickIndex < liveWindow) {
            // Swap two live pads. 2026-07-21
            queue.swap(liveSlot, pickIndex);
            return true;
        }
        // Move pick into live seat; former live item shifts toward Next-up. 2026-07-21
        queue.move(pickIndex, liveSlot);
        return true;
    }

    /**
     * Adapter row count including footer Add song (queueSize + 1, or 1 when empty).
     * Layman: last row is always “Add song”, not a track.
     * 2026-07-21
     */
    public static int adapterCountWithFooter(int queueSize) {
        if (queueSize < 0) queueSize = 0;
        return queueSize + 1;
    }

    /** True when adapter index is the Add song footer (not a PlayQueue index). 2026-07-21 */
    public static boolean isFooterIndex(int adapterIndex, int queueSize) {
        if (queueSize < 0) queueSize = 0;
        return adapterIndex == queueSize;
    }

    /**
     * Clamp focus to a real track index; never land on footer during moves.
     * Layman: while rearranging, highlight stays on songs only.
     * 2026-07-21
     */
    public static int clampTrackFocus(int focus, int queueSize) {
        if (queueSize <= 0) return 0;
        if (focus < 0) return 0;
        if (focus >= queueSize) return queueSize - 1;
        return focus;
    }

    /**
     * During ribbon move, footer must hide (non-selectable).
     * Layman: hide Add while you’re dragging tracks so you don’t pick it by mistake.
     * 2026-07-21
     */
    public static boolean footerVisible(boolean moveActive) {
        return !moveActive;
    }

    /** Human title for a queue row. 2026-07-21 */
    public static String displayName(PlayQueue.QueueItem item) {
        if (item == null) return "";
        if (item.kind == PlayQueue.ItemKind.MUSIC_FILE && item.file != null) {
            return StemControls.stripTrackDisplayName(item.file.getName());
        }
        String meta = item.streamMeta();
        return meta != null ? meta : "";
    }

    /**
     * Queue index of a music file (−1 if absent). Path match.
     * Layman: find whether this song is already waiting in the play queue.
     * 2026-07-21
     */
    public static int indexOfMusicFile(PlayQueue queue, File file) {
        if (queue == null || file == null) return -1;
        String path = file.getAbsolutePath();
        if (path == null) return -1;
        java.util.List<PlayQueue.QueueItem> items = queue.items();
        for (int i = 0; i < items.size(); i++) {
            PlayQueue.QueueItem it = items.get(i);
            if (it == null || it.file == null) continue;
            if (path.equals(it.file.getAbsolutePath())) return i;
        }
        return -1;
    }

    /**
     * Hold-replace pick: if file already queued, bring into liveSlot; else leave queue alone.
     * Layman: picking a queued song pulls it onto the pad seat without duplicating the row.
     * Technical: indexOf + {@link #applyHoldReplaceOrder}; returns pick index after move, or −1.
     * Was: always browse softReplace without queue reorder. Reversal: return −1 always.
     * 2026-07-21
     */
    public static int bringForwardIfQueued(PlayQueue queue, int liveSlot, File file,
            int liveWindow) {
        int pick = indexOfMusicFile(queue, file);
        if (pick < 0) return -1;
        if (!applyHoldReplaceOrder(queue, liveSlot, pick, liveWindow)) return -1;
        // After move/swap, liveSlot holds the pick. 2026-07-21
        return liveSlot;
    }

    /**
     * Soft-replace readiness: file present and (for Stem) stems ready flag from caller.
     * Layman: ready songs mix in now; others wait in line while they prep.
     * 2026-07-21
     */
    public static boolean canSoftReplaceNow(boolean fileReady, boolean stemsOrDeckReady) {
        return fileReady && stemsOrDeckReady;
    }

    /**
     * Keep Stem/Mix mixers attached while the library is open for queue Add or mid-jam replace.
     * Layman: picking another song must not kill the jam under the browser.
     * Was: any leave of STATE_STEM/MIX always detached hosts. Reversal: return false always.
     * 2026-07-21
     */
    public static boolean keepJamAliveUnderBrowse(boolean queueAppendBrowse,
            boolean stemSoftReassign, boolean mixDeckReassign) {
        return queueAppendBrowse || stemSoftReassign || mixDeckReassign;
    }

    /**
     * Queue Add pick must only append — never clear/seed/stop engines.
     * Layman: adding a waiting song leaves what’s playing alone.
     * Tech: callers use appendToMusicQueue; never clearAndSeed / stopCompeting.
     * 2026-07-21
     */
    public static boolean queueAppendMustNotInterruptPlayback() {
        return true;
    }

    /**
     * Stem queue-Add browse offers Has Stems; Mix / NP append does not.
     * Layman: only Stem cares about songs that already have pad files ready.
     * Was: Has Stems only in stemPickMode. Reversal: return false always.
     * 2026-07-21
     */
    public static boolean offerHasStemsInQueueAppend(boolean queueAppendBrowse,
            boolean returnToStem) {
        return queueAppendBrowse && returnToStem;
    }

    /**
     * Face / arch title with 1-based queue position: "(3) Song Name".
     * Layman: show where this track sits in the play queue on the remix face.
     * Was: bare display name. Reversal: return displayName only.
     * 2026-07-21
     */
    public static String titleWithQueuePosition(String displayName, int oneBasedPos) {
        String name = displayName != null ? displayName : "";
        if (oneBasedPos < 1) return name;
        if (name.length() == 0) return "(" + oneBasedPos + ")";
        return "(" + oneBasedPos + ") " + name;
    }

    /**
     * 1-based play-queue position for a file (−1 if not queued).
     * Layman: which number badge this song should wear on the Stem face.
     * 2026-07-21
     */
    public static int oneBasedQueuePosition(PlayQueue queue, File file) {
        int i = indexOfMusicFile(queue, file);
        return i < 0 ? -1 : i + 1;
    }

    /**
     * Session-start wait menu: offer choice when some queue songs already have stems
     * and the live pair still needs prep (would block).
     * Layman: if pads are ready on some tracks but not others, ask which to start with.
     * Was: always cook first two with no choice. Reversal: return false.
     * 2026-07-21
     */
    public static boolean offerStemWaitChoice(int queueStemmedCount, int liveNeedPrepCount) {
        return queueStemmedCount > 0 && liveNeedPrepCount > 0;
    }

    /**
     * “Songs with stems” / Has Stems catalog row — only when the library has any.
     * Layman: hide the browse row when there’s nothing stemmed on disk.
     * Was: always show. Reversal: return true always.
     * 2026-07-21
     */
    public static boolean offerWithStemsCatalogRow(boolean libraryHasStemmedTracks) {
        return libraryHasStemmedTracks;
    }

    /**
     * Build Stem start wait-menu labels: one per ready queue song + optional catalog row.
     * Layman: pick a ready track to mash now, or browse all stemmed songs.
     * Technical: stemmedNames in queue order; catalogLabel null/empty → omit last row.
     * 2026-07-21
     */
    public static String[] buildStemWaitMenuRows(List<String> stemmedDisplayNames,
            String withStemsCatalogLabel) {
        int n = stemmedDisplayNames != null ? stemmedDisplayNames.size() : 0;
        boolean cat = withStemsCatalogLabel != null && withStemsCatalogLabel.length() > 0;
        String[] out = new String[n + (cat ? 1 : 0)];
        for (int i = 0; i < n; i++) {
            String s = stemmedDisplayNames.get(i);
            out[i] = s != null ? s : "";
        }
        if (cat) out[n] = withStemsCatalogLabel;
        return out;
    }

    /**
     * First {@code liveWindow} files from an unlimited queue list (live mash pair).
     * Layman: only two songs play on pads; the rest wait in the numbered queue.
     * Was: truncate openStemPlayer at MAX_SONGS before seeding. Reversal: that trim.
     * 2026-07-21
     */
    public static List<File> liveWindowFiles(List<File> allQueued, int liveWindow) {
        List<File> out = new ArrayList<File>();
        if (allQueued == null || liveWindow < 1) return out;
        for (int i = 0; i < allQueued.size() && out.size() < liveWindow; i++) {
            File f = allQueued.get(i);
            if (f != null && f.isFile()) out.add(f);
        }
        return out;
    }

    /**
     * Hybrid start: begin mixers only after this many live tracks are prepared.
     * Layman: wait until both pad songs are ready, then keep cooking the rest quietly.
     * 2026-07-21
     */
    public static int hybridPrepGateCount(int liveTrackCount) {
        if (liveTrackCount < 1) return 0;
        if (liveTrackCount == 1) return 1;
        return STEM_LIVE_WINDOW;
    }

    /**
     * True when overflow queue tracks should keep preparing after live jam starts.
     * Layman: more songs in line → cook their stems in the background.
     * 2026-07-21
     */
    public static boolean shouldBackgroundPrepOverflow(int queueSize, int liveWindow) {
        return queueSize > liveWindow && liveWindow > 0;
    }

    /**
     * Queue OK during Stem/Mix must soft-replace the focused pad/deck — never NP takeover.
     * Layman: tapping a queued song mid-jam swaps that pad, not Now Playing.
     * Was: playUnifiedQueueItemAt always prepareMusicTrack. Reversal: return false.
     * 2026-07-21 Stems/Mix sanity
     */
    public static boolean queueOkOwnsJam(boolean stemOrMixSessionActive) {
        return stemOrMixSessionActive;
    }

    /**
     * Mid-Stem soft-replace / queue OK: refuse tracks without prepared pads.
     * Layman: only songs that already have stems can jump into a live jam.
     * Was: softReplaceSong cooked Lalal mid-jam. Reversal: return false always.
     * 2026-07-21 Stems/Mix sanity
     */
    public static boolean refuseUnstemmedMidStem(boolean stemsReadyOnDisk) {
        return !stemsReadyOnDisk;
    }

    /**
     * Mid-Stem queue Add footer must open prepared-only (Has Stems) browse.
     * Layman: adding mid-jam only offers songs that already have pads.
     * Was: full library root. Reversal: return false.
     * 2026-07-21 Stems/Mix sanity
     */
    public static boolean forcePreparedOnlyQueueAppend(boolean queueAppendBrowse,
            boolean returnToStem) {
        return queueAppendBrowse && returnToStem;
    }

    /**
     * Mix queue: Prev = deck 0, Next = deck 1 (1-based stamp later).
     * Layman: while the queue is open, Prev/Next pick which floating disc owns the song.
     * @return 0 for Prev, 1 for Next, −1 if neither
     * 2026-07-21 Stems/Mix sanity
     */
    public static int mixDeckIndexFromPrevNext(boolean isPrev, boolean isNext) {
        if (isPrev) return 0;
        if (isNext) return 1;
        return -1;
    }

    /**
     * Stamp queue pick onto a Mix deck seat and park it as Next-up head when past live window.
     * Layman: assign this queued song to disc 1 or 2 and pull it forward in line.
     * Technical: applyHoldReplaceOrder(liveSlot=deck, pickIndex); caller fades deck + refreshes titles.
     * Was: no queue Prev/Next deck stamp. Reversal: return false.
     * 2026-07-21 Stems/Mix sanity
     */
    public static boolean stampMixDeckAndBringForward(PlayQueue queue, int deckIndex,
            int pickIndex, int liveWindow) {
        if (queue == null) return false;
        if (deckIndex < 0 || deckIndex >= liveWindow) return false;
        return applyHoldReplaceOrder(queue, deckIndex, pickIndex, liveWindow);
    }

    /**
     * Append/replace mid-Stem: file must already be stem-ready.
     * Layman: don’t start a cloud split while pads are already rocking.
     * 2026-07-21 Stems/Mix sanity
     */
    public static boolean mayInsertMidStemJam(boolean stemsReady) {
        return stemsReady;
    }

    /**
     * Song-end: when Next-up advance fails, soft-restart the finished seat (never hard silence).
     * Layman: if the waiting song can’t load, that pad restarts its own track with a fade.
     * Was: survivor-only handoff (killed the short song). Reversal: return false.
     * 2026-07-21
     */
    public static boolean softRestartWhenAdvanceMisses(boolean advanceReturnedFile) {
        return !advanceReturnedFile;
    }

    /**
     * Overflow queue always has a Next-up seat to soft-replace into (swap loops the line).
     * Layman: with 3+ songs, ending a pad pulls the next waiting row — forever cycling.
     * Technical: size &gt; liveWindow → advanceSourceIndex ≥ liveWindow.
     * 2026-07-21
     */
    public static boolean queueAdvanceLoopsViaSwap(int queueSize, int liveWindow) {
        return queueSize > liveWindow && liveWindow > 0;
    }

    // ---- Queue “Mix” rows (between stem-capable track pairs) — 2026-08-01 ----
    // The in-app context-menu queue interleaves a selectable “Mix” divider between an
    // adjacent track pair ONLY when both tracks have ready stems (mask[i] = pair i,i+1
    // mixable). Tracks without stems show no Mix divider and are skipped by queue-launched
    // stem performances. Mix rows are hidden during move sessions (and never in playlist
    // edit) so the move ribbon keeps its 1:1 adapter↔queue index mapping.

    /**
     * Interleave Mix dividers while idle: never during moves or playlist edit.
     * Layman: the Mix word lives between songs until you start dragging rows.
     * 2026-08-01
     */
    public static boolean mixRowsVisible(boolean moveActive, boolean playlistEdit) {
        return !moveActive && !playlistEdit;
    }

    /**
     * All-pairs mask: a Mix row between every adjacent pair (uniform interleave).
     * Layman: every neighboring song pair shows Mix — the legacy layout.
     * 2026-08-01
     */
    public static boolean[] allPairsMask(int queueSize) {
        boolean[] m = new boolean[Math.max(0, queueSize - 1)];
        for (int i = 0; i < m.length; i++) m[i] = true;
        return m;
    }

    /**
     * Number of interleaved Mix rows for a per-pair mask (null → none).
     * Layman: count of neighboring pairs that are both stem-ready.
     * 2026-08-01
     */
    public static int mixRowCount(boolean[] pairMask) {
        if (pairMask == null) return 0;
        int c = 0;
        for (int i = 0; i < pairMask.length; i++) if (pairMask[i]) c++;
        return c;
    }

    /**
     * Adapter row count including optional footer. A Mix row is interleaved after
     * track i exactly when pairMask[i] is true; footer (when visible) is the last row.
     * Without a mask it is the plain N(+1) layout so move/ribbon code stays 1:1.
     * 2026-08-01
     */
    public static int adapterCountWithMix(int queueSize, boolean footerVisible,
            boolean[] pairMask) {
        if (queueSize < 0) queueSize = 0;
        int base = queueSize + mixRowCount(pairMask);
        return footerVisible ? base + 1 : base;
    }

    /**
     * Adapter index for a track: trackIdx plus every Mix row placed before it.
     * Layman: rows shuffle right past each stem-ready divider pair.
     * 2026-08-01
     */
    public static int trackToAdapter(int trackIdx, boolean[] pairMask) {
        if (trackIdx <= 0) return 0;
        int mixBefore = 0;
        if (pairMask != null) {
            for (int i = 0; i < trackIdx && i < pairMask.length; i++) {
                if (pairMask[i]) mixBefore++;
            }
        }
        return trackIdx + mixBefore;
    }

    /**
     * Queue (track) index for an adapter row (only valid for track rows). Mix-row
     * adapters resolve to the lower track of their pair.
     * 2026-08-01
     */
    public static int adapterToTrack(int adapterIdx, boolean[] pairMask) {
        if (adapterIdx <= 0) return 0;
        int track = 0;
        int adapter = 0;
        int guard = 0;
        while (guard++ < 1024) {
            // Track `track` row occupies `adapter`.
            if (adapter == adapterIdx) return track;
            adapter++;
            // Mix row after this track (pair track, track+1) when mask true.
            if (pairMask != null && track < pairMask.length && pairMask[track]) {
                if (adapter == adapterIdx) return track;
                adapter++;
            }
            track++;
            // `adapter` now points at the NEXT track's row; `track` is its index.
            if (adapter == adapterIdx) return Math.max(0, track);
            if (adapter > adapterIdx) return Math.max(0, track - 1);
        }
        return 0;
    }

    /**
     * True when an adapter row is an interleaved Mix divider for a stem-ready pair.
     * Layman: a row between two songs that blends them on the pads.
     * 2026-08-01
     */
    public static boolean isMixAdapter(int adapterIdx, int queueSize, boolean[] pairMask) {
        if (adapterIdx < 1 || pairMask == null || queueSize < 2) return false;
        int n = Math.min(queueSize - 1, pairMask.length);
        for (int i = 0; i < n; i++) {
            if (!pairMask[i]) continue;
            if (trackToAdapter(i, pairMask) + 1 == adapterIdx) return true;
        }
        return false;
    }

    /** Adapter-aware footer (Add song) row check. 2026-08-01 */
    public static boolean isFooterAdapter(int adapterIdx, int queueSize, boolean footerVisible,
            boolean[] pairMask) {
        if (!footerVisible) return false;
        return adapterIdx == adapterCountWithMix(queueSize, true, pairMask) - 1;
    }

    /**
     * Reorder the queue so the given track pair sits at the live front (indices 0,1)
     * with the rest of the queue following unchanged.
     * Layman: picking Mix moves those two songs onto the pads; everything else stays Next-up.
     * 2026-08-01
     */
    public static boolean liftPairToLiveFront(PlayQueue q, int lower, int upper) {
        if (q == null) return false;
        int size = q.size();
        if (lower < 0 || upper < 0 || lower >= size || upper >= size || lower == upper) return false;
        int lo = Math.min(lower, upper);
        int hi = Math.max(lower, upper);
        PlayQueue.QueueItem a = q.items().get(lo);
        PlayQueue.QueueItem b = q.items().get(hi);
        q.removeAt(hi);
        q.removeAt(lo);
        List<PlayQueue.QueueItem> items = q.items();
        items.add(0, a);
        items.add(1, b);
        q.setIndex(0);
        return true;
    }

    /**
     * Lift a single picked queue row to the live seed (index 0); the rest of the
     * queue follows unchanged so the previous seed slides to index 1.
     * Layman: picking a song from the queue makes it the new dominant seed track.
     * Technical: removeAt(pick) then insert at 0, setIndex 0. No-op when already 0.
     * 2026-08-01
     */
    public static boolean liftPickToSeedFront(PlayQueue q, int pick) {
        if (q == null) return false;
        int size = q.size();
        if (pick < 0 || pick >= size) return false;
        if (pick == 0) return true;
        PlayQueue.QueueItem a = q.items().get(pick);
        q.removeAt(pick);
        q.items().add(0, a);
        q.setIndex(0);
        return true;
    }
}
