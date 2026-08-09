package com.solar.launcher.stem;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.solar.launcher.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen FIFO stem preparation UI.
 *
 * A queue item is isolated from the next item: one failed Lalal task is reported
 * and skipped instead of aborting the whole batch. This is important for large
 * Get Stems sessions and also prevents a transient network failure from hiding
 * the successful stems already prepared.
 */
public class StemPrepHost {
    private final Activity activity;
    private final View root;
    private final TextView tvSubtitle;
    private final ProgressBar pbProgress;
    private final TextView tvPercent;
    private final List<File> tracks;
    private final Callbacks callbacks;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private volatile boolean cancelled;

    public interface Callbacks {
        void onBatchFinished();
        void onBatchError(String error);

        /** Called after the queue drains, including when some items failed. */
        void onBatchFinishedWithErrors(String error);
    }

    public StemPrepHost(Activity activity, View root, List<File> tracks, Callbacks callbacks) {
        this.activity = activity;
        this.root = root;
        this.tracks = tracks;
        this.callbacks = callbacks;
        tvSubtitle = root.findViewById(R.id.stem_prep_subtitle);
        pbProgress = root.findViewById(R.id.stem_prep_progress);
        tvPercent = root.findViewById(R.id.stem_prep_percent);
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* block clicks while preparing */ }
        });
    }

    public void start() {
        final StemPrepQueue queue = new StemPrepQueue(tracks);
        if (queue.isEmpty()) {
            callbacks.onBatchFinished();
            return;
        }
        cancelled = false;
        io = Executors.newSingleThreadExecutor();
        io.execute(new Runnable() {
            @Override public void run() {
                final ArrayList<String> failures = new ArrayList<String>();
                try {
                    SharedPreferences prefs = activity.getSharedPreferences(
                            LalalAccount.PREFS_NAME, Context.MODE_PRIVATE);
                    String key = LalalAccount.effectiveKey(prefs);
                    final boolean premix = LalalAccount.isPremixExperimental(prefs);
                    if (key == null || key.length() < 8) {
                        postError("No Lalal.ai API key configured.");
                        return;
                    }
                    LalalClient client = new LalalClient(key);
                    int total = queue.size();
                    int completed = 0;
                    File track;
                    while (!cancelled && (track = queue.poll()) != null) {
                        final File current = track;
                        final String name = current.getName();
                        final int itemNumber = completed + failures.size() + 1;
                        final String baseStatus = "Song " + itemNumber + " of " + total + " · " + name;
                        postUpdate(baseStatus, 0);
                        // Foreground Save/Get Stems and quiet prefetch share one extraction lane.
                        synchronized (StemPrepLock.global()) {
                            if (cancelled) break;
                            try {
                                if (LalalClient.trackStemsReady(
                                        activity, current, premix, activity.getCacheDir())) {
                                    completed++;
                                    postUpdate(baseStatus + "\nAlready prepared", 100);
                                    // Batch: warm the analysis cache for this already-ready track
                                    // so the next session opens with real BPM/beat/key. 2026-08-01
                                    com.solar.launcher.stem.analysis.StemBatchAnalyzer
                                            .enqueueResolved(activity, current, premix);
                                    continue;
                                }
                                File workDir = LalalClient.workStemDir(activity, current, premix);
                                if (workDir == null) throw new IllegalStateException(
                                        "Stem work dir unavailable");
                                workDir.mkdirs();
                                List<LalalClient.StemFile> result = client.separateToMp3(
                                        current, workDir, LalalClient.userStemsDir(current), premix,
                                        new LalalClient.Progress() {
                                    @Override public void onProgress(final String phase,
                                            final int percent, final String detail) {
                                        if (cancelled) return;
                                        handler.post(new Runnable() {
                                            @Override public void run() {
                                                tvSubtitle.setText(baseStatus + "\n"
                                                        + (detail != null ? detail : phase));
                                                pbProgress.setProgress(clamp(percent));
                                                tvPercent.setText(clamp(percent) + "%");
                                            }
                                        });
                                    }
                                });
                                if (result == null || result.size() < 4) {
                                    throw new IllegalStateException("Lalal returned incomplete stems");
                                }
                                java.util.List<LalalClient.StemFile> published =
                                        publishCompleted(current, workDir, result, premix);
                                if (!LalalClient.trackStemsReady(
                                        activity, current, premix, activity.getCacheDir())) {
                                    throw new IllegalStateException("Published stems were not found");
                                }
                                completed++;
                                postUpdate(baseStatus + "\nSaved", 100);
                                // Batch: analyse each freshly prepared track in the background so
                                // the NEXT session has real tempo/beat/key, not the heuristic.
                                // publishCompleted throws when published is null/<4, so this list
                                // is always non-empty here. Never blocks the prep lane. 2026-08-01
                                com.solar.launcher.stem.analysis.StemBatchAnalyzer.enqueue(
                                        activity, current, premix, published);
                            } catch (Exception error) {
                                failures.add(name);
                                // Never let a partial download be mistaken for a later success.
                                LalalClient.clearDirQuiet(LalalClient.workStemDir(
                                        activity, current, premix));
                                postUpdate(baseStatus + "\nFailed — continuing", 0);
                            }
                        }
                    }
                    if (cancelled) return;
                    final int done = completed;
                    final String summary = failures.isEmpty() ? null
                            : done + " prepared; failed: " + join(failures);
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (cancelled) return;
                            if (summary == null) callbacks.onBatchFinished();
                            else callbacks.onBatchFinishedWithErrors(summary);
                        }
                    });
                } catch (final Exception error) {
                    postError(error.getMessage() != null ? error.getMessage() : "Extraction failed");
                } finally {
                    if (io != null) io.shutdown();
                }
            }
        });
    }

    /**
     * Publish immediately using the same durable-root policy as the rest of Solar.
     * Returns the durable (published) stem set so callers can enqueue background analysis.
     * 2026-08-01
     */
    private List<LalalClient.StemFile> publishCompleted(File track, File workDir,
            List<LalalClient.StemFile> result, boolean premix) throws Exception {
        File cache = activity.getCacheDir();
        List<LalalClient.StemFile> published = StemDeferredPublish.publishAfterPlayback(
                activity, track, workDir, premix, cache);
        if (published == null || published.size() < 4) {
            throw new IllegalStateException("Stem publish incomplete");
        }
        return published;
    }

    public void cancel() {
        cancelled = true;
        handler.removeCallbacksAndMessages(null);
        if (io != null) io.shutdownNow();
    }

    private void postUpdate(final String text, final int percent) {
        handler.post(new Runnable() {
            @Override public void run() {
                if (cancelled) return;
                tvSubtitle.setText(text);
                pbProgress.setProgress(clamp(percent));
                tvPercent.setText(clamp(percent) + "%");
            }
        });
    }

    private void postError(final String error) {
        handler.post(new Runnable() {
            @Override public void run() {
                if (!cancelled) callbacks.onBatchError(error);
            }
        });
    }

    private static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(values.get(i));
        }
        return out.toString();
    }
}
