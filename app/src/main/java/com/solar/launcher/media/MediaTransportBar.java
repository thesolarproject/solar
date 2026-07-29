package com.solar.launcher.media;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.solar.launcher.DeviceFeatures;
import com.solar.launcher.R;
import com.solar.launcher.theme.ThemeManager;
import com.solar.launcher.ui.HardwareButtonGlyph;

/**
 * Shared bottom transport strip — scrub timeline, volume-in-track pulse, optional hold-Back hint.
 * Layman: the thin bar under Now Playing / video with time, scrub, and the “hold for Options” tip.
 * Technical: one include per screen (player + video); bind via root view to avoid duplicate IDs.
 * 2026-07-18 — Options tip uses Back glyph (+ “Power” text on Y2). Was: plain string resources.
 * 2026-07-20 — Tip band height via dimens (66=34+32); live hold fades + min on-screen time.
 * Reversal: restore hint.setText(R.string.context_hold_back_*) and drop HardwareButtonGlyph call;
 * instant show/hide live tip; 48dp transport.
 */
public final class MediaTransportBar {
    private static final long OVERLAY_FADE_MS = TransportHintChromePolicy.LIVE_HOLD_FADE_MS;
    private static final long VIDEO_OVERLAY_DISMISS_MS = 3000L;

    private final Context ctx;
    private final View root;
    private final LinearLayout scrubRow;
    private final TextView timeCurrent;
    private final TextView timeTotal;
    private final TextView scrubFreq;
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
    private boolean videoOverlayPersistent;
    private boolean volumeModeActive;
    private boolean volumeHintVisible;
    private boolean fmNormalModeActive = false;
    private int lastVolCurrent = 0;
    private int lastVolMax = 1;
    /**
     * 2026-07-18 — Which tip the volume pulse may show.
     * Was: boolean holdBackHintEnabled only. Now: Options → Flow → none ladder.
     */
    public enum HintMode {
        NONE,
        /** 2026-07-20 — First-play: ↻ Volume Up / ↺ Volume Down. */
        VOLUME_ARROWS,
        HOLD_BACK_OPTIONS,
        HOLD_PLAY_FLOW
    }

    private HintMode hintMode = HintMode.HOLD_BACK_OPTIONS;
    /** Fired once per volume session when Flow tip first fades in. */
    public interface OnFlowHintPresentedListener {
        void onFlowHintPresented();
    }

    private OnFlowHintPresentedListener flowHintPresentedListener;
    /**
     * 2026-07-20 — Fired when volume-arrow tip is shown (session or volume pulse).
     * Layman: remember “I already taught volume arrows.”
     */
    public interface OnVolumeTipPresentedListener {
        void onVolumeTipPresented();
    }

    private OnVolumeTipPresentedListener volumeTipPresentedListener;
    /** 2026-07-20 — Auto-dismiss for first-play session tip (not live hold). */
    private Runnable sessionTipHideRunnable;
    private boolean sessionTipActive;
    /** 2026-07-18 — Ear icon at Hearing Safety display max (keep scrolling for more). */
    private boolean lastVolShowEar;
    /** 2026-07-18 — NP live “keep holding…” tip while Back/PP is down (not volume pulse). */
    private boolean liveHoldHintActive;
    /** 2026-07-20 — When live tip became visible (elapsedRealtime); for min on-screen time. */
    private long liveHoldHintShownAtElapsed;
    /** 2026-07-20 — Deferred fade-out after short Play/Pause taps. */
    private Runnable liveHoldHideRunnable;
    /** 2026-07-20 — Bumps on show/cancel so fade endAction cannot GONE a re-shown tip. */
    private int liveHoldFadeGen;

    public MediaTransportBar(Context context, View transportRoot) {
        ctx = context;
        root = transportRoot;
        scrubRow = transportRoot.findViewById(R.id.transport_scrub_row);
        timeCurrent = transportRoot.findViewById(R.id.transport_time_current);
        timeTotal = transportRoot.findViewById(R.id.transport_time_total);
        scrubFreq = transportRoot.findViewById(R.id.transport_scrub_freq);
        volumeIcon = transportRoot.findViewById(R.id.transport_volume_icon);
        progressTrack = transportRoot.findViewById(R.id.transport_progress_track);
        progressBar = transportRoot.findViewById(R.id.transport_progress);
        scrubMarker = transportRoot.findViewById(R.id.transport_scrub_marker);
        volumeProgress = transportRoot.findViewById(R.id.transport_volume_progress);
        hint = transportRoot.findViewById(R.id.transport_hint);
        configureHintMarquee();
        refreshHintText();
        styleScrubMarker();
    }

    /**
     * 2026-07-18 — Larger tip + horizontal marquee for long future copy.
     * Layman: tip text can scroll sideways when it is longer than the screen.
     * Tech: singleLine + MARQUEE + selected while visible (API 17).
     * Reversal: drop marquee; leave static 12sp centered label.
     */
    private void configureHintMarquee() {
        if (hint == null) return;
        hint.setSingleLine(true);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        hint.setMarqueeRepeatLimit(-1);
        hint.setHorizontallyScrolling(true);
        hint.setFocusable(true);
        hint.setFocusableInTouchMode(true);
        try {
            float sp = ctx.getResources().getDimension(R.dimen.y1_transport_hint_text_size);
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp);
        } catch (Exception ignored) {
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
        }
    }

    /**
     * 2026-07-18 — Rebuild tip spannable for current HintMode (theme tint).
     * 2026-07-20 — VOLUME_ARROWS + Press-Back Options copy (was hold-only Options/Flow).
     * Layman: Options Back glyph or Flow Play/Pause glyph above the scrub bar.
     */
    private void refreshHintText() {
        if (hint == null) return;
        // 2026-07-20 — Lock glyph height to tip TextView font metrics (was tipSizePx only).
        int sizePx = HardwareButtonGlyph.sizePxMatchingTextView(hint);
        if (hintMode == HintMode.VOLUME_ARROWS) {
            HardwareButtonGlyph.bindGlyphText(hint,
                    HardwareButtonGlyph.volumeUpDownHint(ctx, sizePx));
        } else if (hintMode == HintMode.HOLD_PLAY_FLOW) {
            HardwareButtonGlyph.bindGlyphText(hint,
                    HardwareButtonGlyph.holdPlayPauseOpenFlow(ctx, sizePx));
        } else if (hintMode == HintMode.HOLD_BACK_OPTIONS) {
            HardwareButtonGlyph.bindGlyphText(hint,
                    HardwareButtonGlyph.keepHoldingForOptions(ctx, DeviceFeatures.isY2(), sizePx));
        }
        // NONE: leave text; visibility stays gated by ensureVolumeHintVisible
    }

    /** @deprecated use {@link #setHintMode(HintMode)} — kept for older call sites. */
    public void setHoldBackHintEnabled(boolean enabled) {
        setHintMode(enabled ? HintMode.HOLD_BACK_OPTIONS : HintMode.NONE);
    }

    /**
     * 2026-07-18 — Options / Flow / none tip policy from MainActivity.
     * Reversal: setHoldBackHintEnabled(boolean) only.
     */
    public void setHintMode(HintMode mode) {
        hintMode = mode != null ? mode : HintMode.NONE;
        refreshHintText();
        if (hintMode == HintMode.NONE) {
            volumeHintVisible = false;
            setHintVisible(false);
        }
    }

    public HintMode getHintMode() {
        return hintMode;
    }

    public void setOnFlowHintPresentedListener(OnFlowHintPresentedListener listener) {
        flowHintPresentedListener = listener;
    }

    /** 2026-07-20 — Prefs: mark volume tip seen after first show. */
    public void setOnVolumeTipPresentedListener(OnVolumeTipPresentedListener listener) {
        volumeTipPresentedListener = listener;
    }

    /**
     * 2026-07-20 — First-play / session tip: fade in current HintMode, then auto fade-out.
     * Layman: calm loading-style line above the scrub when you open Now Playing.
     * Tech: same hint TextView as volume pulse; does not arm volume mode.
     * Reversal: no-op; tips only on volume adjust / live hold.
     */
    public void showSessionTip() {
        if (hint == null || hintMode == HintMode.NONE) return;
        if (liveHoldHintActive) return;
        cancelSessionTipHide();
        sessionTipActive = true;
        refreshHintText();
        hint.animate().cancel();
        configureHintMarquee();
        hint.setVisibility(View.VISIBLE);
        hint.setSelected(true);
        hint.setAlpha(0f);
        notifyTipPresentedOnce();
        long fade = NowPlayingTipPolicy.SESSION_TIP_FADE_MS;
        hint.animate().alpha(1f).setDuration(fade).start();
        if (dismissHandler != null) {
            sessionTipHideRunnable = new Runnable() {
                @Override
                public void run() {
                    fadeSessionTipOut();
                }
            };
            dismissHandler.postDelayed(sessionTipHideRunnable,
                    NowPlayingTipPolicy.SESSION_TIP_VISIBLE_MS);
        }
    }

    /** 2026-07-20 — Fade session tip; leave live-hold alone. */
    private void fadeSessionTipOut() {
        if (hint == null || !sessionTipActive) return;
        if (liveHoldHintActive || volumeModeActive) {
            sessionTipActive = false;
            return;
        }
        sessionTipActive = false;
        hint.animate().cancel();
        hint.animate().alpha(0f).setDuration(NowPlayingTipPolicy.SESSION_TIP_FADE_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (liveHoldHintActive || volumeModeActive || volumeHintVisible) return;
                        hint.setVisibility(View.GONE);
                        hint.setAlpha(1f);
                        hint.setSelected(false);
                    }
                }).start();
    }

    private void cancelSessionTipHide() {
        if (dismissHandler != null && sessionTipHideRunnable != null) {
            dismissHandler.removeCallbacks(sessionTipHideRunnable);
        }
        sessionTipHideRunnable = null;
    }

    /** 2026-07-20 — One-shot prefs callbacks when a tip becomes visible. */
    private void notifyTipPresentedOnce() {
        if (hintMode == HintMode.VOLUME_ARROWS && volumeTipPresentedListener != null) {
            try {
                volumeTipPresentedListener.onVolumeTipPresented();
            } catch (Throwable ignored) {}
        }
        if (hintMode == HintMode.HOLD_PLAY_FLOW && flowHintPresentedListener != null) {
            // Flow tip session show does not exhaust remaining — dismiss is open-from-NP.
        }
    }

    /** @deprecated name kept; now refreshes any active tip mode. */
    private void refreshHoldBackHint() {
        refreshHintText();
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

    /** Enables video-specific volume/scrub behavior for this transport strip. */
    public void setVideoOverlayMode(boolean enabled) {
        videoOverlayMode = enabled;
        if (!enabled) {
            videoOverlayPersistent = false;
            cancelOverlayHide();
            if (root != null) {
                root.animate().cancel();
                root.setAlpha(1f);
            }
        }
    }

    /** Keep elapsed time, duration, and progress visible throughout video playback. */
    public void setVideoOverlayPersistent(boolean persistent) {
        videoOverlayPersistent = persistent;
        if (persistent) {
            cancelOverlayHide();
            setVisible(true);
            showScrubTrack();
        }
    }

    public boolean isVideoOverlayMode() {
        return videoOverlayMode;
    }

    /** Hint is shown only during volume adjustment (fade in/out). */
    public void setHintVisible(boolean visible) {
        if (hint == null) return;
        if (!visible) {
            if (liveHoldHintActive) return; // live hold owns the tip until hideLiveHoldHint
            hint.animate().cancel();
            hint.setSelected(false);
            hint.setVisibility(View.GONE);
            hint.setAlpha(1f);
        }
    }

    /**
     * 2026-07-18 — Show keep-holding tip immediately on NP (Back / Play-Pause down).
     * Layman: as soon as you press, tell you to keep holding for Options or Flow.
     * Tech: forces hint VISIBLE above scrub; does not arm volume pulse.
     * 2026-07-20 — Fade in; cancel pending short-tap hide. Was: alpha=1 instant.
     * Reversal: no-op; only volume-pulse tips remain. Instant alpha=1 / GONE hide.
     */
    public void showLiveHoldHint(CharSequence text) {
        if (hint == null || text == null) return;
        cancelLiveHoldHide();
        liveHoldFadeGen++; // invalidate any in-flight fade-out endAction
        boolean alreadyUp = liveHoldHintActive && hint.getVisibility() == View.VISIBLE
                && hint.getAlpha() >= 0.99f;
        liveHoldHintActive = true;
        liveHoldHintShownAtElapsed = SystemClock.elapsedRealtime();
        hint.animate().cancel();
        configureHintMarquee();
        HardwareButtonGlyph.bindGlyphText(hint, text);
        hint.setVisibility(View.VISIBLE);
        hint.setSelected(true);
        if (alreadyUp) {
            hint.setAlpha(1f);
        } else {
            // Soft fade so short taps still feel intentional, not a flash.
            hint.setAlpha(0f);
            hint.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
        }
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("len", text.length());
            d.put("hintH", hint.getHeight());
            d.put("rootH", root != null ? root.getHeight() : -1);
            d.put("textSizePx", hint.getTextSize());
            d.put("alreadyUp", alreadyUp);
            com.solar.launcher.Debug0f5debLog.log(ctx, "MediaTransportBar.showLiveHoldHint",
                    "live hold tip on", "NP-LIVE", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    /**
     * 2026-07-18 — Hide live hold tip; restore policy tip text (still hidden until volume).
     * 2026-07-20 — Wait out min on-screen time, then fade out (short PP taps stay readable).
     * Was: instant GONE. Reversal: drop deferral + fade; GONE immediately.
     */
    public void hideLiveHoldHint() {
        if (hint == null) return;
        if (!liveHoldHintActive) return;
        cancelLiveHoldHide();
        long delay = TransportHintChromePolicy.hideDelayMs(
                liveHoldHintShownAtElapsed,
                SystemClock.elapsedRealtime(),
                TransportHintChromePolicy.LIVE_HOLD_MIN_VISIBLE_MS);
        liveHoldHideRunnable = new Runnable() {
            @Override
            public void run() {
                liveHoldHideRunnable = null;
                fadeLiveHoldHintOutNow();
            }
        };
        if (delay <= 0L) {
            liveHoldHideRunnable.run();
        } else if (hint != null) {
            hint.postDelayed(liveHoldHideRunnable, delay);
        }
    }

    /** 2026-07-20 — Drop scheduled live-tip hide (new press or leave NP). */
    private void cancelLiveHoldHide() {
        if (liveHoldHideRunnable != null && hint != null) {
            hint.removeCallbacks(liveHoldHideRunnable);
        }
        liveHoldHideRunnable = null;
    }

    /**
     * 2026-07-20 — Fade live tip away, then restore volume-policy copy (still GONE).
     * Layman: tip gently disappears after you let go.
     */
    private void fadeLiveHoldHintOutNow() {
        if (hint == null) return;
        if (!liveHoldHintActive) return;
        liveHoldHintActive = false;
        liveHoldHintShownAtElapsed = 0L;
        final int gen = ++liveHoldFadeGen;
        hint.animate().cancel();
        hint.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .withEndAction(
                        new Runnable() {
                            @Override
                            public void run() {
                                if (hint == null) return;
                                // Show/cancel bumped gen — leave the tip alone.
                                if (gen != liveHoldFadeGen) return;
                                if (liveHoldHintActive) return;
                                hint.setSelected(false);
                                hint.setVisibility(View.GONE);
                                hint.setAlpha(1f);
                                refreshHintText();
                            }
                        })
                .start();
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("mode", hintMode != null ? hintMode.name() : "");
            d.put("gen", gen);
            com.solar.launcher.Debug0f5debLog.log(ctx, "MediaTransportBar.fadeLiveHoldHintOutNow",
                    "live hold tip fade out", "NP-LIVE", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    public boolean isLiveHoldHintActive() {
        return liveHoldHintActive;
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
            // 2026-07-18 — Re-tint glyph after theme paint (SRC_IN colour baked into ImageSpan).
            refreshHoldBackHint();
        }
        if (timeCurrent != null) {
            ThemeManager.applyThemedTextStyle(timeCurrent, textColor);
            if (font != null) timeCurrent.setTypeface(font, android.graphics.Typeface.BOLD);
        }
        if (timeTotal != null) {
            ThemeManager.applyThemedTextStyle(timeTotal, textColor);
            if (font != null) timeTotal.setTypeface(font, android.graphics.Typeface.BOLD);
        }
        if (scrubFreq != null) {
            ThemeManager.applyThemedTextStyle(scrubFreq, textColor);
            if (font != null) scrubFreq.setTypeface(font, android.graphics.Typeface.BOLD);
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
        showVolumeInTrack(current, max, false, false);
    }

    /**
     * 2026-07-18 — NP volume pulse with optional ear at Hearing Safety max.
     * Layman: ear means keep scrolling right for more loudness.
     */
    public void showVolumePulse(int current, int max, boolean showEar) {
        showVolumeInTrack(current, max, false, showEar);
    }

    /** Volume replaces scrub track; on video also pulses the auto-hide overlay. */
    public void showVolumeInTrack(int current, int max) {
        showVolumeInTrack(current, max, true, false);
    }

    /** 2026-07-18 — Video / NP volume with ear cue when Hearing Safety caps. */
    public void showVolumeInTrack(int current, int max, boolean showEar) {
        showVolumeInTrack(current, max, true, showEar);
    }

    /**
     * 2026-07-18 — Hearing Safety temp unlock: animate bar from 100% → ~80% as scale opens.
     * Layman: ear fades, bar drops a bit, more room opens above for louder levels.
     * Technical: same HW index; display re-mapped after temp unlock; ValueAnimator on progress.
     */
    public void showVolumeUnlockAnimation(int fromDisplay, int toDisplay, int max) {
        if (volumeProgress == null) return;
        if (max < 1) max = 1;
        fromDisplay = Math.max(0, Math.min(fromDisplay, max));
        toDisplay = Math.max(0, Math.min(toDisplay, max));
        lastVolCurrent = toDisplay;
        lastVolMax = max;
        lastVolShowEar = false;
        if (progressBar != null && progressBar.getVisibility() != View.GONE) {
            progressBar.setVisibility(View.GONE);
        }
        if (scrubMarker != null && scrubMarker.getVisibility() != View.GONE) {
            scrubMarker.setVisibility(View.GONE);
        }
        volumeProgress.setVisibility(View.VISIBLE);
        volumeProgress.setMax(max);
        volumeProgress.setProgress(fromDisplay);
        applyVolumeProgressTheme();
        setVolumeLabelsVisible(true, fromDisplay, max, true);
        ensureVolumeHintVisible();
        // Hint: keep scrolling / volume-up for more headroom (clears after pulse).
        if (hint != null && hintMode != HintMode.NONE) {
            try {
                hint.setText(R.string.hearing_safety_unlock_hint);
            } catch (Throwable ignored) {}
        }
        final int start = fromDisplay;
        final int end = toDisplay;
        final int animMax = max;
        volumeProgress.animate().cancel();
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(start, end);
        va.setDuration(280L);
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                int v = (Integer) animation.getAnimatedValue();
                if (volumeProgress != null) volumeProgress.setProgress(v);
                if (timeTotal != null) {
                    timeTotal.setText(formatVolumePercent(v, animMax) + "%");
                }
                if (volumeIcon != null) {
                    float t = animation.getAnimatedFraction();
                    // Ear fades out over unlock animation.
                    volumeIcon.setAlpha(1f - t);
                    if (t >= 1f) {
                        volumeIcon.setAlpha(1f);
                        volumeIcon.setImageResource(volumeIconRes(end, animMax));
                    }
                }
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                setVolumeLabelsVisible(true, end, animMax, false);
                if (volumeIcon != null) volumeIcon.setAlpha(1f);
            }
        });
        va.start();
        if (videoOverlayMode) pulseVideoOverlay();
        scheduleVolumeRestore();
    }

    private void showVolumeInTrack(int current, int max, boolean pulseOverlay, boolean showEar) {
        if (volumeProgress == null) return;
        if (max < 1) max = 1;
        current = Math.max(0, Math.min(current, max));
        boolean sameLevel = volumeModeActive && current == lastVolCurrent && max == lastVolMax
                && lastVolShowEar == showEar
                && volumeProgress.getVisibility() == View.VISIBLE;
        lastVolCurrent = current;
        lastVolMax = max;
        lastVolShowEar = showEar;
        if (!sameLevel) {
            volumeProgress.setMax(max);
            volumeProgress.setProgress(current);
            if (progressBar != null && progressBar.getVisibility() != View.GONE) {
                progressBar.setVisibility(View.GONE);
            }
            if (scrubMarker != null && scrubMarker.getVisibility() != View.GONE) {
                scrubMarker.setVisibility(View.GONE);
            }
            if (volumeProgress.getVisibility() != View.VISIBLE) {
                volumeProgress.setVisibility(View.VISIBLE);
            }
            // Theme once per pulse session, not every wheel tick.
            if (!volumeModeActive) {
                applyVolumeProgressTheme();
            }
            setVolumeLabelsVisible(true, current, max, showEar);
            ensureVolumeHintVisible();
            if (showEar && current >= max && hint != null && hintMode != HintMode.NONE) {
                try {
                    hint.setText(R.string.hearing_safety_volume_ear_hint);
                } catch (Throwable ignored) {}
            }
        } else {
            // Still update % text if icon band unchanged.
            if (timeTotal != null) {
                String next = formatVolumePercent(current, max) + "%";
                if (timeTotal.getText() == null || !next.contentEquals(timeTotal.getText())) {
                    timeTotal.setText(next);
                }
            }
        }
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

    public void setFmNormalModeActive(boolean active) {
        fmNormalModeActive = active;
    }

    /**
     * 2026-07-06 — FM manual tune: MHz above knob, band min/max on bar ends, scrub circle on track.
     * Layman: shows where you are on the dial while wheel-stepping frequency.
     */
    public void showFmTuneScrub(String centerFreq, String bandMin, String bandMax, float fraction) {
        fmNormalModeActive = false;
        showScrubTrack();
        if (volumeIcon != null) volumeIcon.setVisibility(View.GONE);
        if (scrubFreq != null) {
            scrubFreq.setText(centerFreq);
            scrubFreq.setVisibility(View.VISIBLE);
        }
        if (timeCurrent != null) {
            timeCurrent.setText(bandMin);
            timeCurrent.setVisibility(View.VISIBLE);
        }
        if (timeTotal != null) {
            timeTotal.setText(bandMax);
            timeTotal.setVisibility(View.VISIBLE);
        }
        if (progressBar != null) {
            progressBar.setProgress(Math.round(fraction * 100f));
        }
        updateScrubMarkerFraction(fraction, true);
    }

    /** Hide FM tune MHz header; caller restores time labels when leaving tune mode. 2026-07-06 */
    public void clearFmTuneScrubHeader() {
        fmNormalModeActive = false;
        if (scrubFreq != null) scrubFreq.setVisibility(View.GONE);
        updateScrubMarkerFraction(0f, false);
    }

    /**
     * 2026-07-07 — FM idle bar: volume icon + % (not a scrub timeline).
     * 2026-07-15 — Skip work when volume/mode unchanged (was thrashing NP on every RDS/progress tick).
     */
    public void showFmNormalBar(int current, int max) {
        if (max < 1) max = 1;
        current = Math.max(0, Math.min(current, max));
        // Coalesce: wheel + 2s RDS were both repainting the same bar.
        if (fmNormalModeActive
                && current == lastVolCurrent
                && max == lastVolMax
                && volumeProgress != null
                && volumeProgress.getVisibility() != View.VISIBLE
                && (scrubFreq == null || scrubFreq.getVisibility() != View.VISIBLE)) {
            return;
        }
        boolean needIcon = !fmNormalModeActive
                || volumeIconRes(current, max) != volumeIconRes(lastVolCurrent, lastVolMax);
        fmNormalModeActive = true;
        lastVolCurrent = current;
        lastVolMax = max;
        showScrubTrack();
        if (scrubFreq != null && scrubFreq.getVisibility() != View.GONE) {
            scrubFreq.setVisibility(View.GONE);
        }
        updateScrubMarkerFraction(0f, false);
        if (timeCurrent != null && timeCurrent.getVisibility() != View.GONE) {
            timeCurrent.setVisibility(View.GONE);
        }
        if (timeTotal != null) {
            int pct = formatVolumePercent(current, max);
            CharSequence old = timeTotal.getText();
            String next = pct + "%";
            if (old == null || !next.contentEquals(old)) {
                timeTotal.setText(next);
            }
            if (timeTotal.getVisibility() != View.VISIBLE) {
                timeTotal.setVisibility(View.VISIBLE);
            }
        }
        if (volumeIcon != null) {
            if (needIcon) {
                volumeIcon.setImageResource(volumeIconRes(current, max));
            }
            if (volumeIcon.getVisibility() != View.VISIBLE) {
                volumeIcon.setVisibility(View.VISIBLE);
            }
        }
    }

    /** Left slot = volume icon; right slot = % — same 77dp gutters as scrub timestamps. */
    private void setVolumeLabelsVisible(boolean volumeMode, int current, int max) {
        setVolumeLabelsVisible(volumeMode, current, max, false);
    }

    /**
     * 2026-07-18 — When showEar, use headphone/ear icon at 100% Hearing Safety cap.
     * Layman: ear means “safe max — keep turning for temporary unlock”.
     */
    private void setVolumeLabelsVisible(boolean volumeMode, int current, int max, boolean showEar) {
        volumeModeActive = volumeMode;
        if (volumeMode) {
            if (timeCurrent != null) timeCurrent.setVisibility(View.GONE);
            if (timeTotal != null) {
                int pct = formatVolumePercent(current, max);
                timeTotal.setText(pct + "%");
                timeTotal.setVisibility(View.VISIBLE);
            }
            if (volumeIcon != null) {
                if (showEar && current >= max) {
                    volumeIcon.setImageResource(R.drawable.ic_headphone);
                } else {
                    volumeIcon.setImageResource(volumeIconRes(current, max));
                }
                volumeIcon.setAlpha(1f);
                volumeIcon.setVisibility(View.VISIBLE);
            }
        } else if (fmNormalModeActive) {
            if (timeCurrent != null) timeCurrent.setVisibility(View.GONE);
            if (timeTotal != null) {
                int pct = formatVolumePercent(lastVolCurrent, lastVolMax);
                timeTotal.setText(pct + "%");
                timeTotal.setVisibility(View.VISIBLE);
            }
            if (volumeIcon != null) {
                volumeIcon.setImageResource(volumeIconRes(lastVolCurrent, lastVolMax));
                volumeIcon.setVisibility(View.VISIBLE);
            }
        } else {
            if (volumeIcon != null) volumeIcon.setVisibility(View.GONE);
            if (timeCurrent != null) timeCurrent.setVisibility(View.VISIBLE);
            if (timeTotal != null) timeTotal.setVisibility(View.VISIBLE);
        }
    }

    public static int formatVolumePercent(int current, int max) {
        if (max <= 0) return 0;
        int pct = Math.round(100f * current / (float) max);
        if (pct <= 0) return 0;
        if (pct >= 100) return 100;
        int rem = pct % 10;
        if (rem == 0 || rem == 2 || rem == 5) return pct;
        if (rem == 1) return (pct == 1) ? 2 : pct - 1;
        if (rem == 3) return pct - 1;
        if (rem == 4) return pct + 1;
        if (rem == 6) return pct - 1;
        if (rem == 7) return pct - 2;
        if (rem == 8) return pct + 2;
        if (rem == 9) return pct + 1;
        return pct;
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
        if (hintMode == HintMode.NONE || hint == null) return;
        // 2026-07-18 — Live keep-holding tip owns the label until key-up.
        if (liveHoldHintActive) return;
        cancelSessionTipHide();
        sessionTipActive = false;
        hint.animate().cancel();
        if (volumeHintVisible) {
            if (hint.getVisibility() != View.VISIBLE) {
                hint.setVisibility(View.VISIBLE);
            }
            hint.setSelected(true);
            if (hint.getAlpha() < 1f) {
                hint.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
            }
            return;
        }
        volumeHintVisible = true;
        // 2026-07-18 — Count Flow tip shows once per volume session (first fade-in).
        // 2026-07-20 — Also mark volume-arrow tip seen on first pulse.
        if (hintMode == HintMode.HOLD_PLAY_FLOW && flowHintPresentedListener != null) {
            try {
                flowHintPresentedListener.onFlowHintPresented();
            } catch (Throwable ignored) {}
        }
        if (hintMode == HintMode.VOLUME_ARROWS && volumeTipPresentedListener != null) {
            try {
                volumeTipPresentedListener.onVolumeTipPresented();
            } catch (Throwable ignored) {}
        }
        refreshHintText();
        hint.setVisibility(View.VISIBLE);
        hint.setSelected(true);
        hint.setAlpha(0f);
        // #region agent log
        try {
            final View scrub = scrubRow;
            hint.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("hintTop", hint.getTop());
                        d.put("hintH", hint.getHeight());
                        d.put("scrubTop", scrub != null ? scrub.getTop() : -1);
                        d.put("scrubY", scrub != null ? scrub.getY() : -1);
                        d.put("rootH", root != null ? root.getHeight() : -1);
                        com.solar.launcher.Debug0f5debLog.log(ctx,
                                "MediaTransportBar.ensureVolumeHintVisible",
                                "hint overlay vs scrub", "NP-HINT", d);
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
        // #endregion
        hint.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
    }

    private void fadeVolumeHintOut() {
        if (hint == null || !volumeHintVisible) return;
        if (liveHoldHintActive) {
            volumeHintVisible = false;
            return;
        }
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
                                    hint.setSelected(false);
                                    hint.setVisibility(View.GONE);
                                    hint.setAlpha(1f);
                                    // 2026-07-18 — Restore Options glyph after volume/hearing tip.
                                    refreshHoldBackHint();
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
        if (!videoOverlayMode || videoOverlayPersistent || dismissHandler == null) return;
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

    /**
     * 2026-07-06 — FM MHz scrub uses band fraction 0..1 instead of track ms.
     * Layman: moves the dot along the bar for radio tuning.
     */
    public void updateScrubMarkerFraction(float fraction, boolean scrubActive) {
        if (scrubMarker == null || progressBar == null) return;
        if (!scrubActive) {
            scrubMarker.setVisibility(View.GONE);
            return;
        }
        showScrubTrack();
        int trackW = progressBar.getWidth();
        if (trackW <= 0) {
            final float frac = fraction;
            progressBar.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            updateScrubMarkerFraction(frac, true);
                        }
                    });
            return;
        }
        float frac = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
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
