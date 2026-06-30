package com.solar.launcher.flow;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Animates Flow center cover into Now Playing album slot (iPod Classic drop).
 * ponytail: single overlay ImageView + manual 16ms tick; gated on 3D album art pref.
 */
public final class FlowPlayerHandoff {

    public interface Host {
        Activity activity();
        View playerAlbumContainer();
        View playerLayout();
        View flowLayout();
        View playerBgBlur();
        void onHandoffComplete(Runnable playAction);
    }

    private static final int HANDOFF_MS = 420;

    private FlowPlayerHandoff() {}

    public static void run(final Host host, final Bitmap cover, final RectF fromRect,
            final Runnable playAction) {
        if (host == null || host.activity() == null || cover == null || fromRect == null) {
            if (playAction != null) playAction.run();
            return;
        }
        Activity act = host.activity();
        ViewGroup root = (ViewGroup) act.findViewById(android.R.id.content);
        if (root == null) {
            if (playAction != null) playAction.run();
            return;
        }

        final View flowLayout = host.flowLayout();
        final View playerLayout = host.playerLayout();
        final View albumContainer = host.playerAlbumContainer();
        final View bgBlur = host.playerBgBlur();

        if (playerLayout != null) {
            playerLayout.setVisibility(View.VISIBLE);
            playerLayout.setAlpha(0f);
        }
        if (bgBlur != null) bgBlur.setAlpha(0f);

        final ImageView flyer = new ImageView(act);
        flyer.setImageBitmap(cover);
        flyer.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.round(fromRect.width()), Math.round(fromRect.height()));
        lp.leftMargin = Math.round(fromRect.left);
        lp.topMargin = Math.round(fromRect.top);
        root.addView(flyer, lp);

        final int[] toLoc = new int[2];
        final RectF toRect = new RectF();
        if (albumContainer != null) {
            albumContainer.getLocationOnScreen(toLoc);
            toRect.set(toLoc[0], toLoc[1],
                    toLoc[0] + albumContainer.getWidth(),
                    toLoc[1] + albumContainer.getHeight());
        } else {
            toRect.set(fromRect);
        }

        final long startMs = System.currentTimeMillis();
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                float t = (now - startMs) / (float) HANDOFF_MS;
                if (t >= 1f) t = 1f;
                float eased = 1f - (1f - t) * (1f - t);

                float cx = fromRect.centerX() + (toRect.centerX() - fromRect.centerX()) * eased;
                float cy = fromRect.centerY() + (toRect.centerY() - fromRect.centerY()) * eased;
                float w = fromRect.width() + (toRect.width() - fromRect.width()) * eased;
                float h = fromRect.height() + (toRect.height() - fromRect.height()) * eased;
                float rot = (1f - eased) * 15f;

                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) flyer.getLayoutParams();
                p.width = Math.round(w);
                p.height = Math.round(h);
                p.leftMargin = Math.round(cx - w * 0.5f);
                p.topMargin = Math.round(cy - h * 0.5f);
                flyer.setLayoutParams(p);
                flyer.setRotationY(rot);

                if (flowLayout != null) flowLayout.setAlpha(1f - eased);
                if (playerLayout != null) playerLayout.setAlpha(eased);
                if (bgBlur != null) bgBlur.setAlpha(eased);

                if (t < 1f) {
                    flyer.postDelayed(this, 16);
                } else {
                    root.removeView(flyer);
                    if (flowLayout != null) flowLayout.setAlpha(1f);
                    host.onHandoffComplete(playAction);
                }
            }
        };
        flyer.post(tick);
    }
}
