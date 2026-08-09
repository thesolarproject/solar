package com.solar.launcher.stem.analysis;

import android.content.Context;

import com.solar.launcher.stem.LalalClient;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background analysis for freshly prepared stem batches (Get Stems / Save Song + Stems /
 * quiet prefetch queues). Each completed track's stems are analysed once published, so the
 * NEXT Stem session opens with real BPM/beat/key instead of the duration heuristic —
 * without waiting for a live session to begin.
 * Technical: dedicated single-thread lane (a 60 s windowed decode must never stall the prep
 * executor), keyed by {@link StemAnalysisCache} so already-analysed tracks are skipped and
 * cached results persist across remounts. Never throws — failures just leave the cache cold.
 * 2026-08-01
 */
public final class StemBatchAnalyzer {
    /** Serial analysis lane — one windowed decode at a time, off the prep queue. */
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean NO_CANCEL = new AtomicBoolean(false);

    private StemBatchAnalyzer() {}

    /**
     * Enqueue analysis of a freshly published stem set. Fire-and-forget; never throws.
     */
    public static void enqueue(Context ctx, File track, boolean premix,
            List<LalalClient.StemFile> stems) {
        if (ctx == null || track == null || stems == null || stems.isEmpty()) return;
        if (StemAnalysisCache.lookup(ctx, track) != null) return; // already analysed
        final Context app = ctx.getApplicationContext();
        final List<LalalClient.StemFile> copy =
                new java.util.ArrayList<LalalClient.StemFile>(stems);
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // Idempotency re-check: a second enqueue of the same track may be queued
                    // behind the first before it stores — skip so we never double-analyse.
                    // 2026-08-01
                    if (StemAnalysisCache.lookup(app, track) != null) return;
                    StemAnalysisCore.Result a = StemTrackAnalyzer.analyze(app, track, copy, NO_CANCEL);
                    if (a != null && a.hasBpm()) {
                        StemAnalysisCache.store(app, track, a);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Enqueue analysis of a track whose stems live on disk (user sidecar, durable vault or
     * app cache). Used when the publish returned a null/partial list (e.g. already-prepared
     * fast path). Fire-and-forget; never throws.
     */
    public static void enqueueResolved(Context ctx, File track, boolean premix) {
        if (ctx == null || track == null || !track.isFile()) return;
        if (StemAnalysisCache.lookup(ctx, track) != null) return; // already analysed
        List<LalalClient.StemFile> stems = null;
        try {
            File user = LalalClient.userStemsDir(track);
            if (user != null && LalalClient.userStemsReady(track)) {
                stems = LalalClient.loadUserStems(track, premix);
            }
            if ((stems == null || stems.isEmpty()) && ctx != null) {
                File durable = LalalClient.durableStemDir(ctx, track, premix);
                if (durable != null && (LalalClient.cacheReady(durable)
                        || LalalClient.cacheReadyFlexible(durable))) {
                    stems = LalalClient.loadCached(durable, premix);
                }
            }
            if ((stems == null || stems.isEmpty()) && ctx != null) {
                File cache = LalalClient.stemCacheDir(ctx.getCacheDir(), track, premix);
                if (cache != null && (LalalClient.cacheReady(cache)
                        || LalalClient.cacheReadyFlexible(cache))) {
                    stems = LalalClient.loadCached(cache, premix);
                }
            }
        } catch (Exception ignored) {}
        enqueue(ctx, track, premix, stems);
    }
}
