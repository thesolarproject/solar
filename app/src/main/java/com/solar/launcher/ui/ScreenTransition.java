package com.solar.launcher.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

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
    private static final Interpolator PUSH_EASE = new MechanicalInterpolator(new float[] {
            0f,.00289750f,.01102137f,.02358921f,.03990526f,.05935196f,.08138216f,.10551216f,
            .13131531f,.15841626f,.18648573f,.21523577f,.24441547f,.27380706f,.30322241f,.33249987f,
            .36150135f,.39010978f,.41822676f,.44577042f,.47267357f,.49888195f,.52435271f,.54905304f,
            .57295888f,.59605388f,.61832835f,.63977839f,.66040508f,.68021378f,.69921351f,.71741636f,
            .73483698f,.75149219f,.76740054f,.78258196f,.79705751f,.81084904f,.82397902f,.83647027f,
            .84834586f,.85962889f,.87034239f,.88050920f,.89015187f,.89929259f,.90795311f,.91615470f,
            .92391807f,.93126339f,.93821022f,.94477748f,.95098349f,.95684590f,.96238173f,.96760735f,
            .97253847f,.97719018f,.98157694f,.98571259f,.98961036f,.99328290f,.99674228f,1f});
    private static final Interpolator PLAYER_EASE = new MechanicalInterpolator(new float[] {
            0f,.00204130f,.00783758f,.01693070f,.02890396f,.04337882f,.06001181f,.07849167f,
            .09853675f,.11989254f,.14232946f,.16564076f,.18964062f,.21416238f,.23905689f,.26419106f,
            .28944640f,.31471781f,.33991234f,.36494818f,.38975358f,.41426600f,.43843123f,.46220264f,
            .48554041f,.50841097f,.53078630f,.55264345f,.57396401f,.59473364f,.61494166f,.63458064f,
            .65364610f,.67213612f,.69005108f,.70739340f,.72416728f,.74037846f,.75603407f,.77114239f,
            .78571270f,.79975517f,.81328066f,.82630063f,.83882705f,.85087224f,.86244885f,.87356973f,
            .88424788f,.89449637f,.90432832f,.91375680f,.92279483f,.93145530f,.93975098f,.94769446f,
            .95529812f,.96257414f,.96953447f,.97619077f,.98255447f,.98863672f,.99444837f,1f});
    private static final Interpolator MODAL_EASE = new MechanicalInterpolator(new float[] {
            0f,.00433240f,.01627593f,.03441200f,.05751693f,.08453828f,.11457381f,.14685288f,
            .18072002f,.21562029f,.25108650f,.28672781f,.32221973f,.35729525f,.39173715f,.42537107f,
            .45805958f,.48969688f,.52020421f,.54952577f,.57762525f,.60448269f,.63009187f,.65445794f,
            .67759542f,.69952644f,.72027926f,.73988695f,.75838631f,.77581689f,.79222021f,.80763906f,
            .82211693f,.83569756f,.84842447f,.86034070f,.87148850f,.88190917f,.89164280f,.90072822f,
            .90920289f,.91710278f,.92446240f,.93131470f,.93769112f,.94362158f,.94913448f,.95425675f,
            .95901385f,.96342987f,.96752752f,.97132819f,.97485204f,.97811798f,.98114380f,.98394617f,
            .98654071f,.98894207f,.99116393f,.99321908f,.99511947f,.99687627f,.99849988f,1f});

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

    static float pushInterpolation(float t) { return PUSH_EASE.getInterpolation(t); }
    static float playerInterpolation(float t) { return PLAYER_EASE.getInterpolation(t); }
    static float modalInterpolation(float t) { return MODAL_EASE.getInterpolation(t); }

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
        final float inStart = forward ? distancePx : -0.35f * distancePx;
        final float outEnd = forward ? -0.35f * distancePx : distancePx;

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
        inView.animate().translationX(0f).setStartDelay(0).setDuration(PUSH_MS).setInterpolator(PUSH_EASE)
                .setListener(endListener(done)).start();
        if (outView != null) {
            outView.animate().translationX(outEnd).alpha(OUTGOING_END_ALPHA)
                    .setStartDelay(forward ? 20 : 0)
                    .setDuration(forward ? PUSH_MS - 20 : PUSH_MS).setInterpolator(PUSH_EASE).start();
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
        inView.animate().translationY(0f).setStartDelay(0).setDuration(PLAYER_MS).setInterpolator(PLAYER_EASE)
                .setListener(endListener(done)).start();
        if (outView != null) {
            // Translate only — fading the player/menu to transparent exposes the window (black).
            outView.animate().translationY(outEnd).setStartDelay(0)
                    .setDuration(PLAYER_MS).setInterpolator(PLAYER_EASE).start();
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
                    panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0)
                            .setDuration(MODAL_MS).setInterpolator(MODAL_EASE)
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
            panel.animate().scaleX(MODAL_PANEL_START_SCALE).scaleY(MODAL_PANEL_START_SCALE).alpha(0f).setStartDelay(0)
                    .setDuration(MODAL_MS).setInterpolator(MODAL_EASE)
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
                inBackdrop.animate().translationY(0f).setStartDelay(0)
                        .setDuration(duration).setInterpolator(PLAYER_EASE).start();
            }
            if (outBackdrop != null) {
                // Wallpaper layers slide in lockstep — never fade to black between themes.
                outBackdrop.animate().translationY(outEnd).setStartDelay(0)
                        .setDuration(duration).setInterpolator(PLAYER_EASE).start();
            }
        } else {
            final float inStart = sign * distancePx;
            final float outEnd = -sign * distancePx;
            if (inBackdrop != null) {
                inBackdrop.setTranslationX(inStart);
                inBackdrop.animate().translationX(0f).setStartDelay(0)
                        .setDuration(duration).setInterpolator(PUSH_EASE).start();
            }
            if (outBackdrop != null) {
                outBackdrop.animate().translationX(outEnd)
                        .setStartDelay(forward ? 20 : 0)
                        .setDuration(forward ? duration - 20 : duration).setInterpolator(PUSH_EASE).start();
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
