package com.solar.launcher.ui;

import android.view.View;

import com.solar.launcher.flow.FlowPlayerHandoff;

import org.json.JSONObject;

/**
 * Orchestrates root-level screen transitions before {@code applyScreenChange} finalizes visibility.
 * Backdrop slots animate in lockstep with content roots when the host prepares them.
 */
public final class ScreenTransitionCoordinator {

    public interface Host {
        android.content.Context context();
        int screenWidthPx();
        int screenHeightPx();
        boolean menuTransitionsEnabled();
        View rootViewForState(int state);
        void applyScreenChange(int state, boolean deferOutgoingHide);
        void finalizeScreenVisibility(int state);
        void prepareTransitionBackdrop(int from, int to);
        View backdropOutSlot();
        View backdropInSlot();
        void commitTransitionBackdrop(int to);
        boolean backdropCrossfadeOnly();
    }

    private ScreenTransitionCoordinator() {}

    public static boolean shouldRun(Host host, int from, int to, boolean isBack) {
        if (host == null || from == to) return false;
        if (!host.menuTransitionsEnabled()) return false;
        if (ScreenTransition.systemAnimationsDisabled(host.context())) return false;
        if (ScreenTransition.isAnimating() || FlowPlayerHandoff.isHandoffAnimating()) return false;
        ScreenTransitionMap.Kind kind = ScreenTransitionMap.resolve(from, to, isBack);
        if (kind == ScreenTransitionMap.Kind.NONE
                || kind == ScreenTransitionMap.Kind.DELEGATE_FLOW) {
            return false;
        }
        View outView = host.rootViewForState(from);
        View inView = host.rootViewForState(to);
        if (inView == null) return false;
        if (ScreenTransitionMap.sharesRoot(from, to)
                && kind != ScreenTransitionMap.Kind.CROSSFADE) {
            return false;
        }
        return kind != ScreenTransitionMap.Kind.NONE;
    }

    public static void run(final Host host, final int from, final int to, final boolean isBack) {
        if (host == null) {
            return;
        }
        final ScreenTransitionMap.Kind kind = ScreenTransitionMap.resolve(from, to, isBack);
        final View outView = host.rootViewForState(from);
        final View inView = host.rootViewForState(to);
        final int w = host.screenWidthPx();
        final int h = host.screenHeightPx();

        // #region agent log
        final long buildStartMs = System.currentTimeMillis();
        // #endregion
        host.applyScreenChange(to, true);
        host.prepareTransitionBackdrop(from, to);
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("from", from);
            d.put("to", to);
            d.put("kind", kind.name());
            d.put("buildMs", System.currentTimeMillis() - buildStartMs);
            TransitionPerfLog.log("ScreenTransitionCoordinator.run", "applyScreenChange done", "A", d);
        } catch (Exception ignored) {}
        // #endregion

        final View outBackdrop = host.backdropOutSlot();
        final View inBackdrop = host.backdropCrossfadeOnly() ? host.backdropInSlot()
                : host.backdropInSlot();

        final Runnable complete = new Runnable() {
            @Override
            public void run() {
                if (outView != null && outView != inView) {
                    outView.setVisibility(View.GONE);
                    ScreenTransition.resetView(outView);
                }
                host.commitTransitionBackdrop(to);
                host.finalizeScreenVisibility(to);
            }
        };

        final Runnable startAnim = new Runnable() {
            @Override
            public void run() {
                if (host.backdropCrossfadeOnly() && kind != ScreenTransitionMap.Kind.SLIDE_UP
                        && kind != ScreenTransitionMap.Kind.SLIDE_DOWN) {
                    ScreenTransition.animateCrossfade(outView, inView, outBackdrop, inBackdrop, complete);
                    return;
                }
                switch (kind) {
                    case PUSH_FORWARD:
                        ScreenTransition.animatePushPop(outView, inView, outBackdrop, inBackdrop,
                                true, w, complete);
                        break;
                    case POP_BACK:
                        ScreenTransition.animatePushPop(outView, inView, outBackdrop, inBackdrop,
                                false, w, complete);
                        break;
                    case SLIDE_UP:
                        ScreenTransition.animateSlideY(outView, inView, outBackdrop, inBackdrop,
                                true, h, complete);
                        break;
                    case SLIDE_DOWN:
                        ScreenTransition.animateSlideY(outView, inView, outBackdrop, inBackdrop,
                                false, h, complete);
                        break;
                    case CROSSFADE:
                        ScreenTransition.animateCrossfade(outView, inView, outBackdrop, inBackdrop, complete);
                        break;
                    default:
                        host.applyScreenChange(to, false);
                        host.commitTransitionBackdrop(to);
                        host.finalizeScreenVisibility(to);
                        break;
                }
            }
        };

        // Start on the outgoing root — it is already on screen so Back feels immediate.
        if (outView != null) {
            outView.post(startAnim);
        } else if (inView != null) {
            inView.post(startAnim);
        } else {
            startAnim.run();
        }
    }
}
