package com.solar.launcher.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * iPod Classic-style root screen animations — push/pop, vertical player slide, crossfade.
 * Outgoing roots fade out during push/slide so incoming layers do not pop over opaque chrome.
 */
public final class ScreenTransition {

    public static final int PUSH_MS = 240;
    public static final int PLAYER_MS = 260;
    public static final int CROSSFADE_MS = 220;
    /** Global context modal — slightly snappier than root push. */
    public static final int MODAL_MS = 200;

    private static final float MODAL_PANEL_START_SCALE = 0.94f;

    /** Alpha for outgoing *content* on horizontal push/pop — lists fade so incoming chrome does not pop. */
    private static final float OUTGOING_END_ALPHA = 0f;
    /** Vertical player slides keep both panels opaque so wallpapers slide as solid bands. */
    private static final float VERTICAL_INCOMING_START_ALPHA = 1f;

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    private static volatile boolean animating;
    private static volatile boolean modalAnimating;
    private static volatile long lastFrameNanos;

    private ScreenTransition() {}

    public static boolean isAnimating() {
        return animating;
    }

    public static boolean isModalAnimating() {
        return modalAnimating;
    }

    public static boolean systemAnimationsDisabled(android.content.Context ctx) {
        if (ctx == null) return false;
        try {
            return Settings.Global.getFloat(ctx.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static float easeOutQuad(float t) {
        if (t >= 1f) return 1f;
        if (t <= 0f) return 0f;
        return 1f - (1f - t) * (1f - t);
    }

    /** @visibleForTesting */
    static float outgoingEndAlpha() {
        return OUTGOING_END_ALPHA;
    }

    public static void cancel() {
        animating = false;
    }

    public static void animatePushPop(final View outView, final View inView, final boolean forward,
            final int distancePx, final Runnable onComplete) {
        animatePushPop(outView, inView, null, null, forward, distancePx, onComplete);
    }

    public static void animatePushPop(final View outView, final View inView,
            final View outBackdrop, final View inBackdrop, final boolean forward,
            final int distancePx, final Runnable onComplete) {
        if (inView == null) {
            finish(outView, null, onComplete);
            return;
        }
        final float sign = forward ? 1f : -1f;
        final float inStart = sign * distancePx;
        final float outEnd = -sign * distancePx;

        inView.setVisibility(View.VISIBLE);
        inView.setAlpha(1f);
        inView.setTranslationX(inStart);
        if (outView != null) {
            outView.setVisibility(View.VISIBLE);
            outView.setTranslationX(0f);
            outView.setAlpha(1f);
        }
        prepareBackdropPushPop(outBackdrop, inBackdrop, forward, distancePx, false);
        animating = true;
        enableHardwareLayer(inView);
        enableHardwareLayer(outView);
        enableHardwareLayer(outBackdrop);
        enableHardwareLayer(inBackdrop);
        logAnimStart("pushPop", forward, distancePx);
        if (TransitionPerfLog.ENABLED) attachFrameGapProbe(inView);

        final Runnable done = new Runnable() {
            @Override
            public void run() {
                animating = false;
                resetView(outView);
                resetView(inView);
                resetView(outBackdrop);
                resetView(inBackdrop);
                logAnimEnd("pushPop");
                if (onComplete != null) onComplete.run();
            }
        };
        inView.animate().translationX(0f).setDuration(PUSH_MS).setInterpolator(EASE)
                .setListener(endListener(done)).start();
        if (outView != null) {
            outView.animate().translationX(outEnd).alpha(OUTGOING_END_ALPHA)
                    .setDuration(PUSH_MS).setInterpolator(EASE).start();
        }
        animateBackdropPushPop(outBackdrop, inBackdrop, forward, distancePx, false);
    }

    public static void animateSlideY(final View outView, final View inView, final boolean entering,
            final int distancePx, final Runnable onComplete) {
        animateSlideY(outView, inView, null, null, entering, distancePx, onComplete);
    }

    public static void animateSlideY(final View outView, final View inView,
            final View outBackdrop, final View inBackdrop, final boolean entering,
            final int distancePx, final Runnable onComplete) {
        if (inView == null) {
            finish(outView, null, onComplete);
            return;
        }
        final float inStart = entering ? distancePx : -distancePx;
        final float outEnd = entering ? -distancePx * 0.15f : distancePx;

        inView.setVisibility(View.VISIBLE);
        inView.setAlpha(VERTICAL_INCOMING_START_ALPHA);
        inView.setTranslationY(inStart);
        if (outView != null) {
            outView.setVisibility(View.VISIBLE);
            outView.setTranslationY(0f);
            outView.setAlpha(1f);
        }
        prepareBackdropPushPop(outBackdrop, inBackdrop, entering, distancePx, true);
        animating = true;
        enableHardwareLayer(inView);
        enableHardwareLayer(outView);
        enableHardwareLayer(outBackdrop);
        enableHardwareLayer(inBackdrop);
        logAnimStart("slideY", entering, distancePx);
        if (TransitionPerfLog.ENABLED) attachFrameGapProbe(inView);

        final Runnable done = new Runnable() {
            @Override
            public void run() {
                animating = false;
                resetView(outView);
                resetView(inView);
                resetView(outBackdrop);
                resetView(inBackdrop);
                logAnimEnd("slideY");
                if (onComplete != null) onComplete.run();
            }
        };
        inView.animate().translationY(0f).setDuration(PLAYER_MS).setInterpolator(EASE)
                .setListener(endListener(done)).start();
        if (outView != null) {
            // Translate only — fading the player/menu to transparent exposes the window (black).
            outView.animate().translationY(outEnd)
                    .setDuration(PLAYER_MS).setInterpolator(EASE).start();
        }
        animateBackdropPushPop(outBackdrop, inBackdrop, entering, distancePx, true);
    }

    public static void animateCrossfade(final View outView, final View inView, final Runnable onComplete) {
        animateCrossfade(outView, inView, null, null, onComplete);
    }

    public static void animateCrossfade(final View outView, final View inView,
            final View outBackdrop, final View inBackdrop, final Runnable onComplete) {
        if (inView == null) {
            finish(outView, null, onComplete);
            return;
        }
        inView.setVisibility(View.VISIBLE);
        inView.setAlpha(0f);
        if (outView != null) {
            outView.setVisibility(View.VISIBLE);
            outView.setAlpha(1f);
        }
        if (inBackdrop != null) {
            inBackdrop.setVisibility(View.VISIBLE);
            inBackdrop.setAlpha(0f);
        }
        if (outBackdrop != null) {
            outBackdrop.setVisibility(View.VISIBLE);
            outBackdrop.setAlpha(1f);
        }
        animating = true;
        enableHardwareLayer(inView);
        enableHardwareLayer(outView);
        enableHardwareLayer(outBackdrop);
        enableHardwareLayer(inBackdrop);
        logAnimStart("crossfade", true, 0);
        if (TransitionPerfLog.ENABLED) attachFrameGapProbe(inView);

        final Runnable done = new Runnable() {
            @Override
            public void run() {
                animating = false;
                resetView(outView);
                inView.setAlpha(1f);
                resetView(outBackdrop);
                resetView(inBackdrop);
                logAnimEnd("crossfade");
                if (onComplete != null) onComplete.run();
            }
        };
        inView.animate().alpha(1f).setDuration(CROSSFADE_MS).setInterpolator(EASE)
                .setListener(endListener(done)).start();
        if (outView != null) {
            outView.animate().alpha(0f).setDuration(CROSSFADE_MS).setInterpolator(EASE).start();
        }
        if (inBackdrop != null) {
            inBackdrop.animate().alpha(1f).setDuration(CROSSFADE_MS).setInterpolator(EASE).start();
        }
        if (outBackdrop != null) {
            outBackdrop.animate().alpha(0f).setDuration(CROSSFADE_MS).setInterpolator(EASE).start();
        }
    }

    public static void fadeInView(final View view, final Runnable onComplete) {
        if (view == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        animating = true;
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                animating = false;
                view.setAlpha(1f);
                if (onComplete != null) onComplete.run();
            }
        };
        view.animate().alpha(1f).setDuration(CROSSFADE_MS).setInterpolator(EASE)
                .setListener(endListener(done)).start();
    }

    /** Scrim fade + panel scale/alpha for global context modal — caller must prepare first. */
    public static void animateModalPresent(final View scrim, final View panel, final Runnable onComplete) {
        if (scrim == null && panel == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        modalAnimating = true;
        enableHardwareLayer(scrim);
        enableHardwareLayer(panel);

        final Runnable start = new Runnable() {
            @Override
            public void run() {
                if (panel != null) {
                    panel.setPivotX(panel.getWidth() * 0.5f);
                    panel.setPivotY(panel.getHeight() * 0.5f);
                }
                final Runnable done = new Runnable() {
                    @Override
                    public void run() {
                        modalAnimating = false;
                        if (panel != null) {
                            panel.setScaleX(1f);
                            panel.setScaleY(1f);
                            panel.setAlpha(1f);
                            clearHardwareLayer(panel);
                        }
                        if (scrim != null) {
                            scrim.setAlpha(1f);
                            clearHardwareLayer(scrim);
                        }
                        if (onComplete != null) onComplete.run();
                    }
                };
                if (panel != null) {
                    panel.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(MODAL_MS).setInterpolator(EASE)
                            .setListener(endListener(done)).start();
                } else {
                    done.run();
                    return;
                }
                if (scrim != null) {
                    scrim.animate().alpha(1f).setDuration(MODAL_MS).setInterpolator(EASE).start();
                }
            }
        };

        // Next frame after rest state is committed — present was invisible on API 17 without this.
        View anchor = panel != null ? panel : scrim;
        anchor.postOnAnimation(start);
    }

    public static void animateModalDismiss(final View scrim, final View panel, final Runnable onComplete) {
        if (scrim == null && panel == null) {
            modalAnimating = false;
            if (onComplete != null) onComplete.run();
            return;
        }
        modalAnimating = true;
        enableHardwareLayer(scrim);
        enableHardwareLayer(panel);
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                modalAnimating = false;
                clearHardwareLayer(scrim);
                clearHardwareLayer(panel);
                if (onComplete != null) onComplete.run();
            }
        };
        boolean panelStarted = false;
        if (panel != null) {
            panelStarted = true;
            panel.animate().scaleX(MODAL_PANEL_START_SCALE).scaleY(MODAL_PANEL_START_SCALE).alpha(0f)
                    .setDuration(MODAL_MS).setInterpolator(EASE)
                    .setListener(endListener(done)).start();
        }
        if (scrim != null) {
            scrim.animate().alpha(0f).setDuration(MODAL_MS).setInterpolator(EASE).start();
        }
        if (!panelStarted) {
            done.run();
        }
    }

    /** Initial modal state before present animation. */
    public static void prepareModalPresent(View scrim, View panel) {
        if (scrim != null) scrim.setAlpha(0f);
        if (panel != null) {
            panel.setPivotX(panel.getWidth() > 0 ? panel.getWidth() * 0.5f : 0f);
            panel.setPivotY(panel.getHeight() > 0 ? panel.getHeight() * 0.5f : 0f);
            panel.setScaleX(MODAL_PANEL_START_SCALE);
            panel.setScaleY(MODAL_PANEL_START_SCALE);
            panel.setAlpha(0f);
        }
    }

    public static void fadeOutView(final View view, final Runnable onComplete) {
        if (view == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        view.setAlpha(1f);
        animating = true;
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                animating = false;
                view.setAlpha(0f);
                view.setVisibility(View.GONE);
                if (onComplete != null) onComplete.run();
            }
        };
        view.animate().alpha(0f).setDuration(CROSSFADE_MS).setInterpolator(EASE)
                .setListener(endListener(done)).start();
    }

    public static void resetView(View v) {
        if (v == null) return;
        // Detach listener before cancel — API 17 onAnimationCancel must not recurse into resetView.
        v.animate().setListener(null);
        v.animate().cancel();
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setAlpha(1f);
        clearHardwareLayer(v);
    }

    /** Promote animating views to GPU layer for smoother slides on MT6572. */
    public static void enableHardwareLayer(View v) {
        if (v == null) return;
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    /** Restore default layer after transition — limits VRAM use. */
    public static void clearHardwareLayer(View v) {
        if (v == null) return;
        v.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    private static void prepareBackdropPushPop(View outBackdrop, View inBackdrop,
            boolean forward, int distancePx, boolean vertical) {
        if (outBackdrop == null && inBackdrop == null) return;
        final float sign = forward ? 1f : -1f;
        if (vertical) {
            final float inStart = forward ? distancePx : -distancePx;
            if (inBackdrop != null) {
                inBackdrop.setVisibility(View.VISIBLE);
                inBackdrop.setTranslationY(inStart);
                inBackdrop.setAlpha(VERTICAL_INCOMING_START_ALPHA);
            }
            if (outBackdrop != null) {
                outBackdrop.setVisibility(View.VISIBLE);
                outBackdrop.setTranslationY(0f);
                outBackdrop.setAlpha(1f);
            }
        } else {
            final float inStart = sign * distancePx;
            if (inBackdrop != null) {
                inBackdrop.setVisibility(View.VISIBLE);
                inBackdrop.setTranslationX(inStart);
                inBackdrop.setAlpha(1f);
            }
            if (outBackdrop != null) {
                outBackdrop.setVisibility(View.VISIBLE);
                outBackdrop.setTranslationX(0f);
                outBackdrop.setAlpha(1f);
            }
        }
    }

    private static void animateBackdropPushPop(View outBackdrop, View inBackdrop,
            boolean forward, int distancePx, boolean vertical) {
        if (outBackdrop == null && inBackdrop == null) return;
        final int duration = vertical ? PLAYER_MS : PUSH_MS;
        final float sign = forward ? 1f : -1f;
        if (vertical) {
            final float inStart = forward ? distancePx : -distancePx;
            final float outEnd = forward ? -distancePx * 0.15f : distancePx;
            if (inBackdrop != null) {
                inBackdrop.setTranslationY(inStart);
                inBackdrop.animate().translationY(0f)
                        .setDuration(duration).setInterpolator(EASE).start();
            }
            if (outBackdrop != null) {
                // Wallpaper layers slide in lockstep — never fade to black between themes.
                outBackdrop.animate().translationY(outEnd)
                        .setDuration(duration).setInterpolator(EASE).start();
            }
        } else {
            final float inStart = sign * distancePx;
            final float outEnd = -sign * distancePx;
            if (inBackdrop != null) {
                inBackdrop.setTranslationX(inStart);
                inBackdrop.animate().translationX(0f)
                        .setDuration(duration).setInterpolator(EASE).start();
            }
            if (outBackdrop != null) {
                outBackdrop.animate().translationX(outEnd)
                        .setDuration(duration).setInterpolator(EASE).start();
            }
        }
    }

    /** Shared end listener for multi-view crossfades (Flow handoff). */
    public static android.animation.Animator.AnimatorListener endListenerFor(final Runnable onComplete) {
        return endListener(onComplete);
    }

    private static Animator.AnimatorListener endListener(final Runnable onComplete) {
        return new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onComplete != null) onComplete.run();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // Cancel is not completion — resetView clears transforms without firing done.
            }
        };
    }

    private static void finish(View outView, View inView, Runnable onComplete) {
        animating = false;
        resetView(outView);
        resetView(inView);
        if (onComplete != null) onComplete.run();
    }

    private static void attachFrameGapProbe(final View anchor) {
        lastFrameNanos = 0L;
        final Choreographer.FrameCallback probe = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (!animating) return;
                if (lastFrameNanos > 0L) {
                    long gapMs = (frameTimeNanos - lastFrameNanos) / 1_000_000L;
                    if (gapMs > 20L) {
                        // #region agent log
                        try {
                            org.json.JSONObject d = new org.json.JSONObject();
                            d.put("gapMs", gapMs);
                            TransitionPerfLog.log("ScreenTransition.frameProbe", "slow frame", "B", d);
                        } catch (Exception ignored) {}
                        // #endregion
                    }
                }
                lastFrameNanos = frameTimeNanos;
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        anchor.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                Choreographer.getInstance().postFrameCallback(probe);
            }
        });
    }

    private static void logAnimStart(String kind, boolean forward, int distance) {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("kind", kind);
            d.put("forward", forward);
            d.put("distance", distance);
            TransitionPerfLog.log("ScreenTransition", "anim start", "B", d);
        } catch (Exception ignored) {}
        // #endregion
    }

    private static void logAnimEnd(String kind) {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("kind", kind);
            TransitionPerfLog.log("ScreenTransition", "anim end", "B", d);
        } catch (Exception ignored) {}
        // #endregion
    }
}
