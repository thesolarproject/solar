package com.solar.launcher.flow;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.solar.launcher.DebugSessionLog;

import org.json.JSONObject;

/**
 * Animates Flow center cover into Now Playing album slot (iPod Classic drop).
 * Forward (Flow→NP) and reverse (NP→Flow) share pose interpolation and Camera draw.
 */
public final class FlowPlayerHandoff {

    public interface Host {
        Activity activity();
        View playerAlbumContainer();
        View playerLayout();
        View flowLayout();
        View playerBgBlur();
        void onHandoffComplete(Runnable playAction);
        /** Re-measure flow center cover after carousel layout (reverse landing snap). */
        RectF flowHandoffLandingRect();
        /** Reverse only — start status bar / label stagger (chrome, not artwork). */
        void onReverseRevealStart();
        /** Reverse — stagger Flow labels and status bar after morph lands. */
        void onReverseRevealProgress(float eased);
        /** Reverse — NP album slot hidden once flyer is on screen (frame 0). */
        void onReverseFlyerMounted();
        /** Reverse — crossfade flyer out / carousel center in at identical pose. */
        void onReverseLandingCrossfade(float flyerAlpha, float carouselAlpha);
        /** Reverse — flyer removed from window; safe to clear pin and fade reflection. */
        void onReverseFlyerRemoved();
        /** Reverse — side carousel covers fade in during morph second half. */
        void onReverseBackgroundProgress(float eased);
    }

    /** Forward and reverse morph duration — same wall time for symmetric feel. */
    private static final int FORWARD_HANDOFF_MS = 420;
    private static final int REVERSE_HANDOFF_MS = 420;
    /** 2D fallback — shorter rect lerp, same landing crossfade. */
    private static final int REVERSE_2D_HANDOFF_MS = 220;
    /** Flyer→carousel alpha handshake after morph pose lands. */
    private static final int REVERSE_LANDING_CROSSFADE_MS = 100;
    /** Title/status stagger starts after this fraction of reverse morph (chrome only). */
    private static final float REVERSE_CHROME_STAGGER_START_T = 0.72f;
    /** Mid-morph landing retarget begins after this fraction (estimated landing only). */
    private static final float REVERSE_RETARGET_START_T = 0.5f;
    /** Side covers fade in between these morph fractions. */
    private static final float REVERSE_SIDE_FADE_START_T = 0.35f;
    private static final float REVERSE_SIDE_FADE_END_T = 0.85f;
    /** Gaps above this ms are slow frames — wall-clock catch-up, skip redundant work. */
    private static final long SLOW_FRAME_GAP_MS = 48L;
    private static final long FRAME_COALESCE_GAP_MS = 20L;

    /** True while flyer animation runs — FlowView uses this to sample onDraw cost. */
    static volatile boolean handoffAnimating;

    public static boolean isHandoffAnimating() {
        return handoffAnimating;
    }

    /** Fail-open when Back arrives after a dropped animation frame. */
    public static void clearHandoffAnimatingFlag() {
        handoffAnimating = false;
    }

    /** @visibleForTesting — measured landing skips the extra flyer→carousel swap fade. */
    static int reverseHandoffTotalMs() {
        return reverseHandoffTotalMs(true);
    }

    static int reverseHandoffTotalMs(boolean landingRectMeasured) {
        return landingRectMeasured
                ? REVERSE_HANDOFF_MS
                : REVERSE_HANDOFF_MS + REVERSE_LANDING_CROSSFADE_MS;
    }

    /** @visibleForTesting */
    static int reverseLandingCrossfadeMs() {
        return REVERSE_LANDING_CROSSFADE_MS;
    }

    /** @visibleForTesting — chrome stagger fraction; flyer stays opaque until landing crossfade. */
    static float reverseChromeStaggerStartFraction() {
        return REVERSE_CHROME_STAGGER_START_T;
    }

    /** @visibleForTesting — flyer alpha during morph (opaque until landing crossfade phase). */
    static float reverseFlyerAlphaAt(float morphT, float landingCrossfadeT) {
        return reverseFlyerAlphaAt(morphT, landingCrossfadeT, false);
    }

    static float reverseFlyerAlphaAt(float morphT, float landingCrossfadeT,
            boolean landingRectMeasured) {
        if (landingRectMeasured) {
            return 1f;
        }
        if (landingCrossfadeT > 0f) {
            return Math.max(0f, 1f - easeInQuad(Math.min(1f, landingCrossfadeT)));
        }
        return 1f;
    }

    /** @visibleForTesting — carousel center alpha during reverse handoff. */
    static float reverseLandingCarouselAlphaAt(float morphT, float landingCrossfadeT) {
        return reverseLandingCarouselAlphaAt(morphT, landingCrossfadeT, false);
    }

    static float reverseLandingCarouselAlphaAt(float morphT, float landingCrossfadeT,
            boolean landingRectMeasured) {
        if (landingRectMeasured) {
            return easeOutQuad(Math.min(1f, morphT));
        }
        if (landingCrossfadeT > 0f) {
            return easeInQuad(Math.min(1f, landingCrossfadeT));
        }
        return 0f;
    }

    /** @visibleForTesting — side slot reveal during morph background crossfade. */
    static float reverseSideRevealAt(float morphT) {
        if (morphT <= REVERSE_SIDE_FADE_START_T) return 0f;
        if (morphT >= REVERSE_SIDE_FADE_END_T) return 1f;
        float p = (morphT - REVERSE_SIDE_FADE_START_T)
                / (REVERSE_SIDE_FADE_END_T - REVERSE_SIDE_FADE_START_T);
        return easeOutQuad(p);
    }

    static float easeOutQuad(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    static float easeInQuad(float t) {
        return t * t;
    }

    /** @visibleForTesting — rect/rot interpolation shared by forward and reverse handoff. */
    static void handoffPoseAt(float eased, RectF from, RectF to, float fromRotY, float toRotY,
            RectF outRect, float[] outRotY) {
        float fromCx = (from.left + from.right) * 0.5f;
        float fromCy = (from.top + from.bottom) * 0.5f;
        float toCx = (to.left + to.right) * 0.5f;
        float toCy = (to.top + to.bottom) * 0.5f;
        float fromW = from.right - from.left;
        float fromH = from.bottom - from.top;
        float toW = to.right - to.left;
        float toH = to.bottom - to.top;
        float cx = fromCx + (toCx - fromCx) * eased;
        float cy = fromCy + (toCy - fromCy) * eased;
        float w = fromW + (toW - fromW) * eased;
        float h = fromH + (toH - fromH) * eased;
        outRect.left = cx - w * 0.5f;
        outRect.top = cy - h * 0.5f;
        outRect.right = cx + w * 0.5f;
        outRect.bottom = cy + h * 0.5f;
        if (outRotY != null && outRotY.length > 0) {
            outRotY[0] = fromRotY + (toRotY - fromRotY) * eased;
        }
    }

    /** @visibleForTesting — lerp landing rect toward measured center during reverse morph. */
    static void retargetLandingRect(RectF current, RectF measured, float retargetEased, RectF out) {
        if (measured == null || (measured.right - measured.left) <= 0f) {
            out.set(current);
            return;
        }
        float p = easeOutQuad(Math.min(1f, Math.max(0f, retargetEased)));
        float fromCx = (current.left + current.right) * 0.5f;
        float fromCy = (current.top + current.bottom) * 0.5f;
        float toCx = (measured.left + measured.right) * 0.5f;
        float toCy = (measured.top + measured.bottom) * 0.5f;
        float fromW = current.right - current.left;
        float fromH = current.bottom - current.top;
        float toW = measured.right - measured.left;
        float toH = measured.bottom - measured.top;
        float cx = fromCx + (toCx - fromCx) * p;
        float cy = fromCy + (toCy - fromCy) * p;
        float w = fromW + (toW - fromW) * p;
        float h = fromH + (toH - fromH) * p;
        out.left = cx - w * 0.5f;
        out.top = cy - h * 0.5f;
        out.right = cx + w * 0.5f;
        out.bottom = cy + h * 0.5f;
    }

    private FlowPlayerHandoff() {}

    public static void run(final Host host, final Bitmap cover, final RectF fromRect,
            final float fromRotY, final float toRotY, final Runnable playAction) {
        animate(host, cover, fromRect, null, fromRotY, toRotY, false, false, playAction,
                FORWARD_HANDOFF_MS);
    }

    /** Back from Now Playing → front-center Flow cover (playerRect → flowRect). */
    public static void runReverse(final Host host, final Bitmap cover, final RectF playerRect,
            final RectF flowRect, final float fromRotY, final float toRotY,
            final boolean landingRectMeasured, final Runnable onComplete) {
        animate(host, cover, playerRect, flowRect, fromRotY, toRotY, true, landingRectMeasured,
                onComplete, REVERSE_HANDOFF_MS);
    }

    /** 2D art fallback — rect lerp without Y rotation, shorter morph. */
    public static void runReverse2d(final Host host, final Bitmap cover, final RectF playerRect,
            final RectF flowRect, final boolean landingRectMeasured, final Runnable onComplete) {
        animate(host, cover, playerRect, flowRect, 0f, 0f, true, landingRectMeasured,
                onComplete, REVERSE_2D_HANDOFF_MS);
    }

    private static void animate(final Host host, final Bitmap cover, final RectF fromRect,
            final RectF toRectOverride, final float fromRotY, final float toRotY,
            final boolean reverse, final boolean landingRectMeasured,
            final Runnable onComplete, final int morphDurationMs) {
        if (host == null || host.activity() == null || cover == null || fromRect == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        Activity act = host.activity();
        ViewGroup root = (ViewGroup) act.findViewById(android.R.id.content);
        if (root == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        final View flowLayout = host.flowLayout();
        final View playerLayout = host.playerLayout();
        final View albumContainer = host.playerAlbumContainer();
        final View bgBlur = host.playerBgBlur();

        final RectF from = new RectF(fromRect);
        final RectF to = new RectF();
        final RectF morphTarget = new RectF();
        if (toRectOverride != null) {
            to.set(new RectF(toRectOverride));
        } else if (albumContainer != null) {
            to.set(FlowAlbumArt3d.playerScreenRect(albumContainer));
        } else {
            to.set(from);
        }
        morphTarget.set(to);

        if (reverse) {
            // Mirror forward Flow→NP: NP chrome out, Flow in, flyer carries the cover.
            if (playerLayout != null) {
                playerLayout.setVisibility(View.VISIBLE);
                playerLayout.setAlpha(1f);
            }
            if (flowLayout != null) {
                flowLayout.setVisibility(View.VISIBLE);
                flowLayout.setAlpha(0f);
            }
            if (bgBlur != null) bgBlur.setAlpha(1f);
            if (!landingRectMeasured) {
                host.onReverseLandingCrossfade(0f, 0f);
            }
        } else {
            if (flowLayout != null) {
                flowLayout.setVisibility(View.VISIBLE);
                flowLayout.setAlpha(1f);
            }
            if (playerLayout != null) {
                playerLayout.setVisibility(View.VISIBLE);
                playerLayout.setAlpha(0f);
            }
            if (bgBlur != null) bgBlur.setAlpha(0f);
        }

        final AlbumArtFlyerView flyer = new AlbumArtFlyerView(act);
        flyer.setCover(cover);
        root.addView(flyer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        handoffAnimating = true;
        final long animStartMs = System.currentTimeMillis();
        final int[] frameCount = new int[1];
        final int[] slowFrameCount = new int[1];
        final long[] lastTickWallMs = new long[1];
        final long[] lastDrawWallMs = new long[1];
        final boolean[] chromeStaggerStarted = new boolean[1];
        final long[] landingCrossfadeStartMs = new long[1];

        final RectF poseBuf = new RectF();
        final float[] rotBuf = new float[1];

        // Frame 0 — flyer visible at NP pose before first animation callback.
        handoffPoseAt(0f, from, morphTarget, fromRotY, toRotY, poseBuf, rotBuf);
        flyer.setScreenPose(poseBuf, rotBuf[0], 1f);
        if (reverse) {
            host.onReverseFlyerMounted();
            if (!landingRectMeasured) {
                host.onReverseBackgroundProgress(0f);
            }
        }

        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long frameGap = lastTickWallMs[0] > 0L ? now - lastTickWallMs[0] : 0L;
                if (frameGap > SLOW_FRAME_GAP_MS) {
                    slowFrameCount[0]++;
                    if (DebugSessionLog.ENABLED) {
                        try {
                            JSONObject d = new JSONObject();
                            d.put("reverse", reverse);
                            d.put("gapMs", frameGap);
                            d.put("frame", frameCount[0]);
                            DebugSessionLog.log("FlowPlayerHandoff.tick", "slow frame gap", "H3", d);
                        } catch (Exception ignored) {}
                    }
                }
                lastTickWallMs[0] = now;
                frameCount[0]++;
                // Under load, wall-clock elapsed still advances — skip redundant flyer redraws.
                boolean skipDraw = frameGap > SLOW_FRAME_GAP_MS
                        && (now - lastDrawWallMs[0]) < FRAME_COALESCE_GAP_MS;

                long elapsed = now - animStartMs;
                float morphT = Math.min(1f, elapsed / (float) morphDurationMs);
                float landingT = 0f;
                // Measured carousel landing — single morph phase like forward Flow→NP (no swap fade).
                if (reverse && morphT >= 1f && !landingRectMeasured) {
                    if (landingCrossfadeStartMs[0] == 0L) {
                        landingCrossfadeStartMs[0] = now;
                    }
                    landingT = Math.min(1f,
                            (now - landingCrossfadeStartMs[0]) / (float) REVERSE_LANDING_CROSSFADE_MS);
                }
                float eased = easeOutQuad(morphT);

                if (reverse && !landingRectMeasured && morphT > REVERSE_RETARGET_START_T && morphT < 1f) {
                    RectF measured = host.flowHandoffLandingRect();
                    if (measured != null && (measured.right - measured.left) > 0f) {
                        float retargetP = (morphT - REVERSE_RETARGET_START_T)
                                / (1f - REVERSE_RETARGET_START_T);
                        retargetLandingRect(morphTarget, measured, retargetP, morphTarget);
                    }
                }

                float poseEased = morphT >= 1f ? 1f : eased;
                handoffPoseAt(poseEased, from, morphTarget, fromRotY, toRotY, poseBuf, rotBuf);

                if (reverse) {
                    // Measured landing — mirror forward: layout crossfade + opaque flyer only.
                    if (!skipDraw) {
                        float drawAlpha = landingRectMeasured ? 1f
                                : reverseFlyerAlphaAt(morphT, landingT, false);
                        flyer.setScreenPose(poseBuf, rotBuf[0], drawAlpha);
                        lastDrawWallMs[0] = now;
                    }
                    if (!landingRectMeasured) {
                        float flyerAlpha = reverseFlyerAlphaAt(morphT, landingT, false);
                        float carouselAlpha = reverseLandingCarouselAlphaAt(
                                morphT, landingT, false);
                        host.onReverseLandingCrossfade(flyerAlpha, carouselAlpha);
                        host.onReverseBackgroundProgress(reverseSideRevealAt(morphT));
                        if (morphT >= REVERSE_CHROME_STAGGER_START_T) {
                            if (!chromeStaggerStarted[0]) {
                                chromeStaggerStarted[0] = true;
                                host.onReverseRevealStart();
                            }
                            float staggerT = (morphT - REVERSE_CHROME_STAGGER_START_T)
                                    / (1f - REVERSE_CHROME_STAGGER_START_T);
                            host.onReverseRevealProgress(easeOutQuad(Math.min(1f, staggerT)));
                        }
                    } else {
                        // Carousel center fades in under opaque flyer — no end pop.
                        host.onReverseLandingCrossfade(1f, easeOutQuad(Math.min(1f, morphT)));
                    }
                    // NP chrome out, Flow in under the flyer (inverse of forward).
                    if (morphT < 1f) {
                        if (playerLayout != null) playerLayout.setAlpha(1f - eased);
                        if (bgBlur != null) bgBlur.setAlpha(1f - eased);
                        if (flowLayout != null) flowLayout.setAlpha(eased);
                    }
                } else {
                    if (!skipDraw) {
                        flyer.setScreenPose(poseBuf, rotBuf[0], 1f);
                        lastDrawWallMs[0] = now;
                    }
                    if (flowLayout != null) flowLayout.setAlpha(1f - eased);
                    if (playerLayout != null) playerLayout.setAlpha(eased);
                    if (bgBlur != null) bgBlur.setAlpha(eased);
                }

                boolean reverseDone = reverse && morphT >= 1f
                        && (landingRectMeasured || landingT >= 1f);
                boolean forwardDone = !reverse && morphT >= 1f;

                if (!reverseDone && !forwardDone) {
                    flyer.postOnAnimation(this);
                } else if (reverse) {
                    // Snap flyer to live carousel center before remove — mirror forward NP slot snap.
                    if (landingRectMeasured) {
                        RectF measured = host.flowHandoffLandingRect();
                        if (measured != null && measured.width() > 0f) {
                            morphTarget.set(measured);
                            flyer.setScreenPose(morphTarget, toRotY, 1f);
                        }
                    }
                    if (playerLayout != null) {
                        playerLayout.setAlpha(0f);
                        playerLayout.setVisibility(View.GONE);
                    }
                    if (bgBlur != null) bgBlur.setAlpha(0f);
                    if (flowLayout != null) flowLayout.setAlpha(1f);
                    handoffAnimating = false;
                    if (DebugSessionLog.ENABLED) {
                        try {
                            JSONObject d = new JSONObject();
                            d.put("reverse", true);
                            d.put("landingMeasured", landingRectMeasured);
                            d.put("wallMs", now - animStartMs);
                            d.put("frames", frameCount[0]);
                            d.put("slowFrames", slowFrameCount[0]);
                            DebugSessionLog.log("FlowPlayerHandoff.animate", "handoff end", "H2-H4", d);
                        } catch (Exception ignored) {}
                    }
                    root.removeView(flyer);
                    host.onReverseFlyerRemoved();
                    host.onHandoffComplete(onComplete);
                } else {
                    if (albumContainer != null) {
                        morphTarget.set(FlowAlbumArt3d.playerScreenRect(albumContainer));
                        flyer.setScreenPose(morphTarget, toRotY, 1f);
                    }
                    handoffAnimating = false;
                    host.onHandoffComplete(onComplete);
                    root.removeView(flyer);
                }
            }
        };
        flyer.postOnAnimation(tick);
    }

    /** Full-screen overlay — draws cover in screen space via shared {@link FlowAlbumArt3d}. */
    static final class AlbumArtFlyerView extends View {

        private final Paint coverPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF screenRect = new RectF();
        private final RectF localRect = new RectF();
        private Bitmap cover;
        private float rotationYDeg;
        private float drawAlpha = 1f;

        AlbumArtFlyerView(Context context) {
            super(context);
            coverPaint.setFilterBitmap(false);
            setBackgroundColor(0x00000000);
        }

        void setCover(Bitmap bmp) {
            cover = bmp;
        }

        void setScreenPose(RectF rect, float rotY, float alpha) {
            screenRect.set(rect);
            rotationYDeg = rotY;
            drawAlpha = alpha;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (cover == null || cover.isRecycled() || screenRect.width() <= 0f || drawAlpha <= 0f) {
                return;
            }
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            localRect.set(
                    screenRect.left - loc[0],
                    screenRect.top - loc[1],
                    screenRect.right - loc[0],
                    screenRect.bottom - loc[1]);
            if (localRect.width() <= 0f) return;

            // Camera draw for entire flight — forward and reverse share the same pose path.
            FlowAlbumArt3d.drawCover(canvas, cover, localRect, rotationYDeg, drawAlpha, coverPaint);
        }
    }
}
