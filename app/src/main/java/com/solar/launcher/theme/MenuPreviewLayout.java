package com.solar.launcher.theme;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solar.launcher.R;

import org.json.JSONObject;

/**
 * Right-pane preview sizing for home menu and settings when a theme mask frames the pane.
 * ponytail: optional menuConfig/settingConfig dp overrides; sane Y1 defaults otherwise.
 */
public final class MenuPreviewLayout {

    public static final class Spec {
        public final int paneWidthPx;
        public final int artMaxPx;
        public final int marginTopPx;
        public final int marginEndPx;
        /** When true, title/artist rows stay hidden (mask safe area has no room below art). */
        public final boolean hideTitlesBelowArt;

        Spec(int paneWidthPx, int artMaxPx, int marginTopPx, int marginEndPx, boolean hideTitlesBelowArt) {
            this.paneWidthPx = paneWidthPx;
            this.artMaxPx = artMaxPx;
            this.marginTopPx = marginTopPx;
            this.marginEndPx = marginEndPx;
            this.hideTitlesBelowArt = hideTitlesBelowArt;
        }
    }

    private MenuPreviewLayout() {}

    public static Spec homeSpec(Context ctx, boolean maskActive) {
        JSONObject menu = ThemeManager.getCurrentTheme().root.optJSONObject("menuConfig");
        if (!maskActive) {
            int width = dim(ctx, R.dimen.y1_preview_width);
            int artMax = dim(ctx, R.dimen.y1_preview_art_max);
            int top = dim(ctx, R.dimen.y1_preview_margin_top);
            int end = dim(ctx, R.dimen.y1_preview_margin_right);
            artMax = optDpOverride(ctx, menu, "menuPreviewArtMax", artMax);
            return new Spec(width, artMax, top, end, false);
        }
        int width = dim(ctx, R.dimen.y1_preview_mask_width);
        int artMax = dim(ctx, R.dimen.y1_preview_mask_art_max);
        int top = dim(ctx, R.dimen.y1_preview_mask_margin_top);
        int end = dim(ctx, R.dimen.y1_preview_mask_margin_right);
        artMax = optDpOverride(ctx, menu, "menuPreviewArtMax", artMax);
        top = optDpOverride(ctx, menu, "menuPreviewMarginTop", top);
        end = optDpOverride(ctx, menu, "menuPreviewMarginRight", end);
        width = optDpOverride(ctx, menu, "menuPreviewWidth", width);
        return new Spec(width, artMax, top, end, true);
    }

    public static Spec settingsSpec(Context ctx, boolean maskActive) {
        JSONObject setting = ThemeManager.getCurrentTheme().root.optJSONObject("settingConfig");
        if (!maskActive) {
            int width = dim(ctx, R.dimen.y1_preview_width);
            int artMax = dim(ctx, R.dimen.y1_setting_icon_max);
            int top = dim(ctx, R.dimen.y1_settings_preview_title_top);
            int end = dim(ctx, R.dimen.y1_settings_preview_margin_right);
            return new Spec(width, artMax, top, end, false);
        }
        int width = dim(ctx, R.dimen.y1_preview_mask_width);
        int artMax = dim(ctx, R.dimen.y1_setting_mask_art_max);
        int top = dim(ctx, R.dimen.y1_setting_mask_margin_top);
        int end = dim(ctx, R.dimen.y1_settings_preview_margin_right);
        artMax = optDpOverride(ctx, setting, "settingPreviewArtMax", artMax);
        top = optDpOverride(ctx, setting, "settingPreviewMarginTop", top);
        width = optDpOverride(ctx, setting, "settingPreviewWidth", width);
        return new Spec(width, artMax, top, end, true);
    }

    public static void applyImagePreview(View view, Spec spec) {
        if (view == null || spec == null) return;
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        lp.width = spec.paneWidthPx;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        lp.topMargin = spec.marginTopPx;
        lp.rightMargin = spec.marginEndPx;
        view.setLayoutParams(lp);
        if (view instanceof ImageView) {
            ImageView iv = (ImageView) view;
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setMaxWidth(spec.artMaxPx);
            iv.setMaxHeight(spec.artMaxPx);
        }
    }

    public static void applyLinearPreview(View view, Spec spec) {
        if (view == null || spec == null) return;
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        lp.width = spec.paneWidthPx;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        lp.topMargin = spec.marginTopPx;
        lp.rightMargin = spec.marginEndPx;
        view.setLayoutParams(lp);
    }

    /** Stack title/artist just below preview art (avoids fixed 250dp overlap). */
    public static void layoutTitlesBelowArt(final ImageView art, final TextView title,
            final TextView artist, final int gapPx) {
        if (art == null || title == null) return;
        art.post(new Runnable() {
            @Override
            public void run() {
                if (art.getVisibility() != View.VISIBLE) return;
                int top = art.getTop() + art.getHeight() + gapPx;
                layoutTitleRow(title, top);
                if (artist != null && artist.getVisibility() == View.VISIBLE) {
                    title.post(new Runnable() {
                        @Override
                        public void run() {
                            layoutTitleRow(artist, title.getBottom() + gapPx / 2);
                        }
                    });
                }
            }
        });
    }

    private static void layoutTitleRow(TextView tv, int topPx) {
        ViewGroup.LayoutParams raw = tv.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        lp.topMargin = topPx;
        tv.setLayoutParams(lp);
    }

    public static void applyWidgetAlbum(ImageView album, LinearLayout host, Spec spec) {
        if (album == null || spec == null) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) album.getLayoutParams();
        if (lp == null) {
            lp = new LinearLayout.LayoutParams(spec.artMaxPx, spec.artMaxPx);
        } else {
            lp.width = spec.artMaxPx;
            lp.height = spec.artMaxPx;
        }
        album.setLayoutParams(lp);
        album.setScaleType(ImageView.ScaleType.FIT_CENTER);
        album.setAdjustViewBounds(true);
        if (host != null) {
            ViewGroup.LayoutParams hlp = host.getLayoutParams();
            if (hlp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) hlp;
                flp.width = spec.paneWidthPx;
                flp.topMargin = spec.marginTopPx;
                flp.rightMargin = spec.marginEndPx;
                host.setLayoutParams(flp);
            }
        }
    }

    private static int dim(Context ctx, int resId) {
        return (int) ctx.getResources().getDimension(resId);
    }

    public static void applySettingsPreviewPane(View pane, ImageView icon, Spec spec, boolean maskActive, Context ctx) {
        if (pane == null || spec == null) return;
        ViewGroup.LayoutParams raw = pane.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        lp.width = spec.paneWidthPx;
        lp.topMargin = maskActive ? spec.marginTopPx
                : (int) ctx.getResources().getDimension(R.dimen.y1_settings_preview_title_top);
        pane.setLayoutParams(lp);
        if (icon != null) {
            ViewGroup.LayoutParams ilpRaw = icon.getLayoutParams();
            if (ilpRaw instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams ilp = (LinearLayout.LayoutParams) ilpRaw;
                ilp.width = spec.paneWidthPx;
                icon.setLayoutParams(ilp);
            }
            icon.setAdjustViewBounds(true);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setMaxWidth(spec.artMaxPx);
            icon.setMaxHeight(spec.artMaxPx);
        }
    }

    private static int optDpOverride(Context ctx, JSONObject block, String key, int fallbackPx) {
        if (block == null || key == null) return fallbackPx;
        double dp = block.optDouble(key, -1);
        if (dp <= 0) return fallbackPx;
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }
}
