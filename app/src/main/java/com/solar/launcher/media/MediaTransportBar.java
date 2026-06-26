package com.solar.launcher.media;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.solar.launcher.R;
import com.solar.launcher.theme.ThemeManager;

/**
 * Shared bottom transport strip — scrub timeline, volume-in-track pulse, optional hold-Back hint.
 * ponytail: one include per screen (player + video); bind via root view to avoid duplicate IDs.
 */
public final class MediaTransportBar {
    private static final long OVERLAY_FADE_MS = 200L;
    private static final long VIDEO_OVERLAY_DISMISS_MS = 3000L;

    private final Context ctx;
    private final View root;
    private final LinearLayout scrubRow;
    private final TextView timeCurrent;
    private final TextView timeTotal;
    private final ImageView volumeIcon;
    private final FrameLayout progressTrack;
    private final ProgressBar progressBar;
    private final View scrubMarker;
    private final ProgressBar volumeProgress;
    private final TextView hint;

    private Handler dismissHandler;
    private Runnable hideVolumeRunnable;
    private Runnable hideOverlayRunnable;
    private long volumeDismissMs = 2000L;
    private long overlayDismissMs = VIDEO_OVERLAY_DISMISS_MS;
    private boolean videoOverlayMode;
    private boolean volumeModeActive;
    private boolean volumeHintVisible;
    /** When false, never fade in the hold-Back-for-Options line (user opened context menu once). */
    private boolean holdBackHintEnabled = true;

    public MediaTransportBar(Context context, View transportRoot) {
        ctx = context;
        root = transportRoot;
        scrubRow = transportRoot.findViewById(R.id.transport_scrub_row);
        timeCurrent = transportRoot.findViewById(R.id.transport_time_current);
        timeTotal = transportRoot.findViewById(R.id.transport_time_total);
        volumeIcon = transportRoot.findViewById(R.id.transport_volume_icon);
        progressTrack = transportRoot.findViewById(R.id.transport_progress_track);
        progressBar = transportRoot.findViewById(R.id.transport_progress);
        scrubMarker = transportRoot.findViewById(R.id.transport_scrub_marker);
        volumeProgress = transportRoot.findViewById(R.id.transport_volume_progress);
        hint = transportRoot.findViewById(R.id.transport_hint);
        styleScrubMarker();
    }

    public View root() {
        return root;
    }

    public ProgressBar progressBar() {
        return progressBar;
    }

    public FrameLayout progressTrack() {
        return progressTrack;
    }

    public View scrubMarker() {
        return scrubMarker;
    }

    public TextView timeCurrent() {
        return timeCurrent;
    }

    public TextView timeTotal() {
        return timeTotal;
    }

    public void setDismissHandler(Handler handler, long dismissMs) {
        dismissHandler = handler;
        volumeDismissMs = dismissMs;
    }

    public void setVideoOverlayDismissMs(long dismissMs) {
        overlayDismissMs = dismissMs;
    }

    /** iPod-style video overlay: hidden until user adjusts volume, scrubs, or holds skip. */
    public void setVideoOverlayMode(boolean enabled) {
        videoOverlayMode = enabled;
        if (!enabled) {
            cancelOverlayHide();
            if (root != null) {
                root.animate().cancel();
                root.setAlpha(1f);
            }
        }
    }

    public boolean isVideoOverlayMode() {
        return videoOverlayMode;
    }

    /** Hint is shown only during volume adjustment (fade in/out). */
    public void setHintVisible(boolean visible) {
        if (hint == null) return;
        if (!visible) {
            hint.animate().cancel();
            hint.setVisibility(View.GONE);
            hint.setAlpha(1f);
        }
    }

    /** Suppress hold-Back hint after the user has opened the global context menu once. */
    public void setHoldBackHintEnabled(boolean enabled) {
        holdBackHintEnabled = enabled;
        if (!enabled) {
            volumeHintVisible = false;
            setHintVisible(false);
        }
    }

    public boolean isVolumeModeActive() {
        return volumeModeActive;
    }

    public void setVisible(boolean visible) {
        if (root != null) {
            root.animate().cancel();
            root.setAlpha(1f);
            root.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /** Theme panel chrome for video overlay strip (outer rounded panel + stroke). */
    public void styleVideoChrome() {
        if (root != null) {
            root.setBackground(ThemeManager.buildContextMenuPanelDrawable(ctx));
        }
        applyTheme();
    }

    /** Theme font + hint/time colors for player and video transport bars. */
    public void applyTheme() {
        int textColor =
                ThemeManager.ensureReadableOnBackground(
                        ThemeManager.getDialogTextColor(), ThemeManager.getContextMenuPanelColor());
        int hintColor =
                ThemeManager.ensureReadableOnBackground(
                        ThemeManager.getHintTextColor(), ThemeManager.getContextMenuPanelColor());
        android.graphics.Typeface font = ThemeManager.getCustomFont();
        if (hint != null) {
            ThemeManager.applyThemedTextStyle(hint, hintColor);
            if (font != null) hint.setTypeface(font);
        }
        if (timeCurrent != null) {
            ThemeManager.applyThemedTextStyle(timeCurrent, textColor);
            if (font != null) timeCurrent.setTypeface(font, android.graphics.Typeface.BOLD);
        }
        if (timeTotal != null) {
            ThemeManager.applyThemedTextStyle(timeTotal, textColor);
            if (font != null) timeTotal.setTypeface(font, android.graphics.Typeface.BOLD);
        }
        if (volumeIcon != null) {
            volumeIcon.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        applyProgressTheme();
    }

    public void applyProgressTheme() {
        int fg = ThemeManager.getProgressColor();
        int bg = ThemeManager.getProgressBackgroundColor();
        if (progressBar != null) {
            android.graphics.drawable.Drawable d = progressBar.getProgressDrawable();
            if (d != null) {
                d.setColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            android.graphics.drawable.Drawable bgD = progressBar.getIndeterminateDrawable();
            if (bgD != null) {
                bgD.setColorFilter(bg, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
        applyVolumeProgressTheme();
        styleScrubMarker();
    }

    private void applyVolumeProgressTheme() {
        if (volumeProgress == null) return;
        int fg = ThemeManager.getProgressColor();
        int bg = ThemeManager.getProgressBackgroundColor();
        android.graphics.drawable.Drawable d = volumeProgress.getProgressDrawable();
        if (d != null) {
            d.setColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        android.graphics.drawable.Drawable bgD = volumeProgress.getIndeterminateDrawable();
        if (bgD != null) {
            bgD.setColorFilter(bg, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    private void styleScrubMarker() {
        if (scrubMarker == null) return;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ThemeManager.getProgressColor());
        dot.setStroke(2, ThemeManager.getProgressBackgroundColor());
        scrubMarker.setBackground(dot);
    }

    /** Briefly show volume in the scrub track slot (music Now Playing). */
    public void showVolumePulse(int current, int max) {
        showVolumeInTrack(current, max, false);
    }

    /** Volume replaces scrub track; on video also pulses the auto-hide overlay. */
    public void showVolumeInTrack(int current, int max) {
        showVolumeInTrack(current, max, true);
    }

    private void showVolumeInTrack(int current, int max, boolean pulseOverlay) {
        if (volumeProgress == null) return;
        if (max < 1) max = 1;
        volumeProgress.setMax(max);
        volumeProgress.setProgress(Math.max(0, Math.min(current, max)));
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (scrubMarker != null) scrubMarker.setVisibility(View.GONE);
        volumeProgress.setVisibility(View.VISIBLE);
        applyVolumeProgressTheme();
        setVolumeLabelsVisible(true, current, max);
        ensureVolumeHintVisible();
        if (pulseOverlay && videoOverlayMode) {
            pulseVideoOverlay();
        }
        scheduleVolumeRestore();
    }

    public void showScrubTrack() {
        if (volumeProgress != null) volumeProgress.setVisibility(View.GONE);
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (volumeModeActive) {
            setVolumeLabelsVisible(false, 0, 1);
            fadeVolumeHintOut();
        }
    }

    /** Left slot = volume icon; right slot = % — same 77dp gutters as scrub timestamps. */
    private void setVolumeLabelsVisible(boolean volumeMode, int current, int max) {
        volumeModeActive = volumeMode;
        if (volumeMode) {
            if (timeCurrent != null) timeCurrent.setVisibility(View.GONE);
            if (timeTotal != null) {
                int pct = max > 0 ? Math.round(100f * current / (float) max) : 0;
                timeTotal.setText(pct + "%");
                timeTotal.setVisibility(View.VISIBLE);
            }
            if (volumeIcon != null) {
                volumeIcon.setImageResource(volumeIconRes(current, max));
                volumeIcon.setVisibility(View.VISIBLE);
            }
        } else {
            if (volumeIcon != null) volumeIcon.setVisibility(View.GONE);
            if (timeCurrent != null) timeCurrent.setVisibility(View.VISIBLE);
            if (timeTotal != null) timeTotal.setVisibility(View.VISIBLE);
        }
    }

    private static int volumeIconRes(int current, int max) {
        if (current <= 0) return R.drawable.ic_volume_mute;
        if (max <= 0) return R.drawable.ic_volume_high;
        float pct = (float) current / (float) max;
        if (pct <= 0.33f) return R.drawable.ic_volume_low;
        if (pct <= 0.66f) return R.drawable.ic_volume_mid;
        return R.drawable.ic_volume_high;
    }

    /** Fade in once per volume session; stay visible while the wheel keeps adjusting. */
    private void ensureVolumeHintVisible() {
        if (!holdBackHintEnabled || hint == null) return;
        hint.animate().cancel();
        if (volumeHintVisible) {
            if (hint.getVisibility() != View.VISIBLE) {
                hint.setVisibility(View.VISIBLE);
            }
            if (hint.getAlpha() < 1f) {
                hint.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
            }
            return;
        }
        volumeHintVisible = true;
        hint.setVisibility(View.VISIBLE);
        hint.setAlpha(0f);
        hint.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
    }

    private void fadeVolumeHintOut() {
        if (hint == null || !volumeHintVisible) return;
        volumeHintVisible = false;
        hint.animate().cancel();
        hint.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .withEndAction(
                        new Runnable() {
                            @Override
                            public void run() {
                                if (hint != null) {
                                    hint.setVisibility(View.GONE);
                                    hint.setAlpha(1f);
                                }
                            }
                        })
                .start();
    }

    private void scheduleVolumeRestore() {
        if (dismissHandler == null) return;
        if (hideVolumeRunnable != null) dismissHandler.removeCallbacks(hideVolumeRunnable);
        hideVolumeRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        showScrubTrack();
                    }
                };
        dismissHandler.postDelayed(hideVolumeRunnable, volumeDismissMs);
    }

    public void hideVolumePulse() {
        showScrubTrack();
        if (dismissHandler != null && hideVolumeRunnable != null) {
            dismissHandler.removeCallbacks(hideVolumeRunnable);
        }
    }

    /** Fade in video transport overlay and reset idle dismiss timer. */
    public void pulseVideoOverlay() {
        if (!videoOverlayMode || root == null) return;
        cancelOverlayHide();
        root.setVisibility(View.VISIBLE);
        root.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
        scheduleVideoOverlayHide();
    }

    public void scheduleVideoOverlayHide() {
        if (!videoOverlayMode || dismissHandler == null) return;
        if (hideOverlayRunnable != null) dismissHandler.removeCallbacks(hideOverlayRunnable);
        hideOverlayRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        fadeOutOverlay();
                    }
                };
        dismissHandler.postDelayed(hideOverlayRunnable, overlayDismissMs);
    }

    private void fadeOutOverlay() {
        if (root == null) return;
        root.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .withEndAction(
                        new Runnable() {
                            @Override
                            public void run() {
                                if (root != null) {
                                    root.setVisibility(View.GONE);
                                    root.setAlpha(1f);
                                }
                                showScrubTrack();
                            }
                        })
                .start();
    }

    private void cancelOverlayHide() {
        if (dismissHandler != null && hideOverlayRunnable != null) {
            dismissHandler.removeCallbacks(hideOverlayRunnable);
            hideOverlayRunnable = null;
        }
    }

    public void updateScrubMarker(long positionMs, long durationMs, boolean scrubActive) {
        if (scrubMarker == null || progressBar == null) return;
        if (!scrubActive || durationMs <= 0) {
            scrubMarker.setVisibility(View.GONE);
            return;
        }
        int trackW = progressBar.getWidth();
        if (trackW <= 0) {
            progressBar.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            updateScrubMarker(positionMs, durationMs, true);
                        }
                    });
            return;
        }
        float frac = (float) positionMs / (float) durationMs;
        float density = ctx.getResources().getDisplayMetrics().density;
        int markerW = scrubMarker.getWidth() > 0 ? scrubMarker.getWidth() : (int) (10 * density);
        int x = (int) (frac * trackW) - markerW / 2;
        x = Math.max(0, Math.min(x, trackW - markerW));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scrubMarker.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(markerW, markerW);
        }
        lp.width = markerW;
        lp.height = markerW;
        lp.leftMargin = x;
        scrubMarker.setLayoutParams(lp);
        scrubMarker.setVisibility(View.VISIBLE);
    }
}
