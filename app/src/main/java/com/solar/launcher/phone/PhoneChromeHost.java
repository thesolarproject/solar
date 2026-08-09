package com.solar.launcher.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.solar.launcher.ui.HardwareButtonGlyph;

import java.io.File;

/**
 * 2026-07-20 — Hosts Solar content in a 4:3 viewport above a ClassiPod-style body + click wheel.
 * W480: hard cut, full-width Solar; other phones: scaled Solar with body fill around it.
 * (i) flips to customize. Injects keys only — never replaces Y1InputKeys paths.
 * Was: setContentView(activity_main) full bleed. Now: wrap root in this host when policy active.
 * Reversal: skip wrap in MainActivity — prior layout tree returns.
 */
public final class PhoneChromeHost extends FrameLayout {

    /** Activity that can dispatch synthetic keys and start pickers. */
    public interface HostCallbacks {
        void dispatchInjectedKey(KeyEvent event);
        Activity activity();

        /**
         * 2026-08-01 — Phone chrome storage root changed (seed/pick/fallback persisted).
         * Lets MainActivity re-derive the Music media root into the new Internal/MicroSD.
         * Was: customize panel rebuilt itself only — app rootFolder stayed stale (Deezer saves died).
         * Reversal: drop the method + the call site below.
         */
        void onStorageChanged();
    }

    private static final int PENDING_STORAGE_NONE = 0;
    private static final int PENDING_STORAGE_SEED = 1;
    private static final int PENDING_STORAGE_TREE = 2;

    /** Live host for static pending-seed helpers from CustomizePanel. */
    private static java.lang.ref.WeakReference<PhoneChromeHost> sActiveHost;

    private final HostCallbacks callbacks;
    private final FrameLayout flipContainer;
    private final FrameLayout frontFace;
    private final FrameLayout viewport;
    private final BodyPanel bodyPanel;
    private final PhoneClickWheelPad wheelPad;
    private final FrameLayout glyphOverlay;
    private final TextView infoButton;
    private final PhoneChromeCustomizePanel customizePanel;

    private View solarContent;
    private boolean flipped;
    private boolean animating;
    private boolean w480;
    private PhoneChromePolicy.LayoutMetrics metrics;
    private Bitmap bodyTexture;
    private Bitmap wheelTexture;
    /** Last ring/OK glyph px — rebuild bitmaps when wheel radius changes size class. */
    private int lastRingGlyphPx;
    private int lastOkGlyphPx;
    /** 2026-07-20 — Retry seed/tree after API 23–28 storage permission dialog. */
    private int pendingStorageAction = PENDING_STORAGE_NONE;
    private Uri pendingTreeUri;

    public PhoneChromeHost(Context context, HostCallbacks callbacks) {
        super(context);
        this.callbacks = callbacks;
        sActiveHost = new java.lang.ref.WeakReference<PhoneChromeHost>(this);
        setClickable(true);

        flipContainer = new FrameLayout(context);
        frontFace = new FrameLayout(context);
        viewport = new FrameLayout(context);
        bodyPanel = new BodyPanel(context);
        wheelPad = new PhoneClickWheelPad(context);
        glyphOverlay = new FrameLayout(context);
        infoButton = new TextView(context);
        customizePanel = new PhoneChromeCustomizePanel(context);

        addView(flipContainer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        flipContainer.addView(frontFace, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        flipContainer.addView(customizePanel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        customizePanel.setVisibility(GONE);

        frontFace.addView(bodyPanel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        frontFace.addView(viewport, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        frontFace.addView(wheelPad, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        frontFace.addView(glyphOverlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        frontFace.addView(infoButton, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        infoButton.setText("(i)");
        infoButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        infoButton.setPadding(dp(10), dp(6), dp(10), dp(6));
        infoButton.setTextColor(0xFF333333);
        infoButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleCustomize();
            }
        });

        wheelPad.setKeySink(new PhoneClickWheelPad.KeySink() {
            @Override
            public void injectKey(int keyCode) {
                fireKey(keyCode, false);
            }

            @Override
            public void injectKeyLongPress(int keyCode) {
                fireKey(keyCode, true);
            }

            @Override
            public void injectWheelScroll(int keyCode, int repeatCount) {
                // 2026-08-02 — Scroll notch: DOWN-only, no UP. Real hardware sends
                // a stream of DOWN events with incrementing repeatCount; phone chrome
                // must match this so WheelNavPolicy / ListWheelCoalescer work correctly.
                if (callbacks == null || keyCode == 0) return;
                long now = android.os.SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, repeatCount);
                callbacks.dispatchInjectedKey(down);
            }

            @Override
            public void injectWheelUp(int keyCode) {
                // 2026-08-02 — Finger lifted: send UP to clear the held state.
                if (callbacks == null || keyCode == 0) return;
                long now = android.os.SystemClock.uptimeMillis();
                KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
                callbacks.dispatchInjectedKey(up);
            }
        });

        customizePanel.setListener(new PhoneChromeCustomizePanel.Listener() {
            @Override
            public void onColorsChanged() {
                applyChromeLook();
            }

            @Override
            public void onTextureChanged() {
                applyChromeLook();
            }

            @Override
            public void onStorageChanged() {
                customizePanel.rebuild();
                // After first-run pick, stay on customize so look options appear.
                // 2026-08-01 — Tell MainActivity so the Music root re-derives into the new folder.
                if (callbacks != null) callbacks.onStorageChanged();
            }

            @Override
            public void onClose() {
                // 2026-07-20 — Block Done until Storage is set (phone chrome only).
                if (PhoneStorageRoots.needsStoragePrompt(getContext())) {
                    Toast.makeText(getContext(),
                            "Choose a Storage folder first", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (flipped) toggleCustomize();
            }

            @Override
            public void requestImagePick(boolean forBody) {
                Activity a = callbacks != null ? callbacks.activity() : null;
                if (a == null) return;
                try {
                    a.startActivityForResult(PhoneChromeCustomizePanel.imagePickIntent(),
                            forBody ? PhoneChromeCustomizePanel.REQ_BODY_IMAGE
                                    : PhoneChromeCustomizePanel.REQ_WHEEL_IMAGE);
                } catch (Throwable t) {
                    // Fail-open: picker missing on some skins.
                }
            }

            @Override
            public void requestStoragePick() {
                Activity a = callbacks != null ? callbacks.activity() : null;
                if (a == null) return;
                PhoneChromeCustomizePanel.pickStorageFallback(a, this);
            }
        });

        applyChromeLook();
        buildGlyphs();
    }

    /**
     * 2026-07-20 — Reparent activity_main root into the Solar viewport.
     * Call once after setContentView when policy is active.
     */
    public void attachSolarContent(View content) {
        if (content == null) return;
        solarContent = content;
        ViewGroup parent = (ViewGroup) content.getParent();
        if (parent != null) parent.removeView(content);
        viewport.removeAllViews();
        viewport.addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        requestLayout();
        // 2026-07-20 — First launch: open Storage onboarding before downloads fail.
        // Was: silent sdcard0 fall-open. Reversal: remove post — user opens (i) manually.
        post(new Runnable() {
            @Override
            public void run() {
                maybeOpenStorageOnboarding();
            }
        });
    }

    /**
     * 2026-07-20 — Flip to customize when Phone Solar has no Storage folder yet.
     * Y1/Y2/A5 never reach this host.
     */
    public void maybeOpenStorageOnboarding() {
        if (!PhoneStorageRoots.needsStoragePrompt(getContext())) return;
        if (flipped || animating) return;
        customizePanel.rebuild();
        toggleCustomize();
    }

    /** Forward image / tree picker results from MainActivity.onActivityResult. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return false;
        Context ctx = getContext();
        if (requestCode == PhoneChromeCustomizePanel.REQ_BODY_IMAGE) {
            Uri uri = data.getData();
            if (uri != null) {
                PhoneChromePrefs.importTexture(ctx, uri, true);
                applyChromeLook();
                customizePanel.rebuild();
            }
            return true;
        }
        if (requestCode == PhoneChromeCustomizePanel.REQ_WHEEL_IMAGE) {
            Uri uri = data.getData();
            if (uri != null) {
                PhoneChromePrefs.importTexture(ctx, uri, false);
                applyChromeLook();
                customizePanel.rebuild();
            }
            return true;
        }
        if (requestCode == PhoneChromeCustomizePanel.REQ_STORAGE_TREE) {
            Uri uri = data.getData();
            if (uri != null) {
                PhoneChromeCustomizePanel.applyTreeUri(ctx, uri);
                customizePanel.rebuild();
            }
            return true;
        }
        return false;
    }

    /**
     * 2026-07-20 — Remember “Use SolarPhone” while the runtime permission dialog is up.
     * Called from CustomizePanel before requestPermissions returns.
     */
    public static void pendingSeedSolarPhone(Activity activity) {
        PhoneChromeHost h = activeHost();
        if (h != null) h.pendingStorageAction = PENDING_STORAGE_SEED;
    }

    /**
     * 2026-07-20 — Remember SAF tree apply while waiting for legacy storage grant.
     */
    public static void pendingApplyTreeUri(Activity activity, Uri treeUri) {
        PhoneChromeHost h = activeHost();
        if (h == null) return;
        h.pendingStorageAction = PENDING_STORAGE_TREE;
        h.pendingTreeUri = treeUri;
    }

    private static PhoneChromeHost activeHost() {
        return sActiveHost != null ? sActiveHost.get() : null;
    }

    /**
     * 2026-07-20 — After READ/WRITE result, finish seed/tree via ladder (grant or deny).
     * Deny still completes onboarding on app dirs — never leave the create-failed toast.
     */
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode != PhoneStorageRuntimePerms.REQ_STORAGE) return false;
        int action = pendingStorageAction;
        Uri tree = pendingTreeUri;
        pendingStorageAction = PENDING_STORAGE_NONE;
        pendingTreeUri = null;
        Context ctx = getContext();
        if (action == PENDING_STORAGE_SEED) {
            File candidate = null;
            try {
                File base = android.os.Environment.getExternalStorageDirectory();
                if (base != null && base.isDirectory()) {
                    candidate = new File(base, PhoneStorageAccess.APP_FOLDER);
                }
            } catch (Throwable ignored) {}
            PhoneChromeCustomizePanel.applyResolvedStorage(ctx, candidate, true);
            customizePanel.rebuild();
            // 2026-08-01 — Auto-seed path: storage root persisted here, not via the panel
            // button — tell MainActivity so the Music root re-derives (Deezer/Soulseek saves).
            if (callbacks != null) callbacks.onStorageChanged();
            return true;
        }
        if (action == PENDING_STORAGE_TREE) {
            File candidate = PhoneChromeCustomizePanel.bestEffortTreeFile(tree);
            PhoneChromeCustomizePanel.applyResolvedStorage(ctx, candidate, true);
            customizePanel.rebuild();
            // 2026-08-01 — Same notification as the seed path above.
            if (callbacks != null) callbacks.onStorageChanged();
            return true;
        }
        return true;
    }

    private void applyChromeLook() {
        Context ctx = getContext();
        int body = PhoneChromePrefs.bodyColor(ctx);
        int wheel = PhoneChromePrefs.wheelColor(ctx);
        bodyPanel.setBodyColor(body);
        wheelPad.setWheelColor(wheel);

        recycle(bodyTexture);
        bodyTexture = PhoneChromePrefs.loadTextureBitmap(PhoneChromePrefs.bodyTexturePath(ctx));
        bodyPanel.setBodyTexture(bodyTexture);

        recycle(wheelTexture);
        wheelTexture = PhoneChromePrefs.loadTextureBitmap(PhoneChromePrefs.wheelTexturePath(ctx));
        wheelPad.setWheelTexture(wheelTexture);

        infoButton.setTextColor(contrastInk(body));
    }

    /**
     * 2026-07-20 — Paint Solar btn_* icons on the click ring.
     * Was: fixed 22dp (tiny on WVGA wheels). Now: scale from wheel radius (~30% / ~36% OK).
     * Reversal: restore size = dp(22) for all five glyphs.
     */
    private void buildGlyphs() {
        // Placeholder size until first applyMetrics; rebuilds with real wheel radius then.
        int size = dp(40);
        rebuildGlyphs(size, Math.round(size * 1.15f));
    }

    /** Rebuild glyph ImageViews at the given pixel sizes (ring + centre OK). */
    private void rebuildGlyphs(int ringPx, int okPx) {
        if (ringPx <= 0 || okPx <= 0) return;
        if (ringPx == lastRingGlyphPx && okPx == lastOkGlyphPx
                && glyphOverlay.getChildCount() == 5) {
            return;
        }
        lastRingGlyphPx = ringPx;
        lastOkGlyphPx = okPx;
        glyphOverlay.removeAllViews();
        addGlyph(HardwareButtonGlyph.Button.BACK, ringPx);   // MENU top
        addGlyph(HardwareButtonGlyph.Button.PREV, ringPx);
        addGlyph(HardwareButtonGlyph.Button.NEXT, ringPx);
        addGlyph(HardwareButtonGlyph.Button.PLAY_PAUSE, ringPx);
        addGlyph(HardwareButtonGlyph.Button.OK, okPx);
    }

    private void addGlyph(HardwareButtonGlyph.Button button, int sizePx) {
        ImageView iv = new ImageView(getContext());
        Drawable d = loadGlyphDrawable(button, sizePx);
        if (d != null) iv.setImageDrawable(d);
        iv.setTag(button);
        glyphOverlay.addView(iv, new LayoutParams(sizePx, sizePx));
    }

    private Drawable loadGlyphDrawable(HardwareButtonGlyph.Button button, int sizePx) {
        try {
            // Light ink on dark wheel — theme text may be wrong on photo textures.
            return HardwareButtonGlyph.tintedDrawable(getContext(), button, 0xFFE8E8E8, sizePx);
        } catch (Throwable t) {
            return null;
        }
    }

    private void fireKey(int keyCode, boolean longPress) {
        if (callbacks == null || keyCode == 0) return;
        KeyEvent[] pair = longPress
                ? PhoneClickWheelPad.longPressDownUp(keyCode)
                : PhoneClickWheelPad.downUp(keyCode);
        callbacks.dispatchInjectedKey(pair[0]);
        callbacks.dispatchInjectedKey(pair[1]);
    }

    /** Flip customize face — W480 flips body band only; else whole host. */
    public void toggleCustomize() {
        if (animating) return;
        final boolean toBack = !flipped;
        View target = w480 ? bodyFlipTarget() : flipContainer;
        animating = true;
        ObjectAnimator anim = ObjectAnimator.ofFloat(target, "rotationY",
                toBack ? 0f : 180f, toBack ? 180f : 360f);
        anim.setDuration(220);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                flipped = toBack;
                if (toBack) {
                    customizePanel.setVisibility(VISIBLE);
                    customizePanel.rebuild();
                    if (w480) {
                        // Hide wheel chrome while editing body face.
                        wheelPad.setVisibility(INVISIBLE);
                        glyphOverlay.setVisibility(INVISIBLE);
                    } else {
                        frontFace.setVisibility(INVISIBLE);
                    }
                    target.setRotationY(0f);
                } else {
                    customizePanel.setVisibility(GONE);
                    wheelPad.setVisibility(VISIBLE);
                    glyphOverlay.setVisibility(VISIBLE);
                    frontFace.setVisibility(VISIBLE);
                    target.setRotationY(0f);
                }
                PhoneChromePrefs.setCustomizeOpen(getContext(), flipped);
                animating = false;
            }
        });
        // Mid-flip swap for non-W480 so the back face shows.
        if (!w480) {
            target.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (toBack) {
                        frontFace.setVisibility(INVISIBLE);
                        customizePanel.setVisibility(VISIBLE);
                    } else {
                        customizePanel.setVisibility(GONE);
                        frontFace.setVisibility(VISIBLE);
                    }
                }
            }, 110);
        } else if (toBack) {
            bodyPanel.postDelayed(new Runnable() {
                @Override
                public void run() {
                    customizePanel.setVisibility(VISIBLE);
                }
            }, 110);
        }
        anim.start();
    }

    /** Body band used as W480 flip target (viewport stays put). */
    private View bodyFlipTarget() {
        return bodyPanel;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        w480 = PhoneChromePolicy.isW480(w, h);
        metrics = PhoneChromePolicy.layoutMetrics(w, h, w480);
        applyMetrics();
    }

    private void applyMetrics() {
        if (metrics == null) return;
        // Viewport: Solar content
        LayoutParams vp = (LayoutParams) viewport.getLayoutParams();
        if (vp == null) vp = new LayoutParams(metrics.viewportW, metrics.viewportH);
        vp.width = metrics.viewportW;
        vp.height = metrics.viewportH;
        vp.leftMargin = metrics.offsetX;
        vp.topMargin = metrics.offsetY;
        vp.gravity = Gravity.TOP | Gravity.LEFT;
        viewport.setLayoutParams(vp);

        bodyPanel.setMetrics(metrics);
        customizePanel.setLayoutMetrics(metrics);

        // Customize panel: W480 covers body band only; bordered covers full host.
        LayoutParams cp = (LayoutParams) customizePanel.getLayoutParams();
        if (cp == null) cp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        if (w480) {
            cp.width = LayoutParams.MATCH_PARENT;
            cp.height = Math.max(0, metrics.screenH - metrics.bodyTop);
            cp.topMargin = metrics.bodyTop;
            cp.leftMargin = 0;
            cp.gravity = Gravity.TOP | Gravity.LEFT;
        } else {
            cp.width = LayoutParams.MATCH_PARENT;
            cp.height = LayoutParams.MATCH_PARENT;
            cp.topMargin = 0;
            cp.leftMargin = 0;
            cp.gravity = Gravity.FILL;
        }
        customizePanel.setLayoutParams(cp);

        // Wheel sits in the body remainder, centred.
        int bodyH = Math.max(0, metrics.screenH - metrics.bodyTop);
        float wheelR = Math.min(metrics.screenW, bodyH) * 0.38f;
        float cx = metrics.screenW / 2f;
        float cy = metrics.bodyTop + bodyH / 2f;
        // 2026-07-20 — Glyphs track wheel size so WVGA/xhdpi rings stay readable.
        int ringPx = clamp(Math.round(wheelR * 0.30f), dp(36), dp(64));
        int okPx = clamp(Math.round(wheelR * 0.36f), dp(42), dp(72));
        rebuildGlyphs(ringPx, okPx);
        wheelPad.setWheelGeometry(cx, cy, wheelR);
        layoutGlyphs(cx, cy, wheelR);

        LayoutParams ip = (LayoutParams) infoButton.getLayoutParams();
        if (ip == null) ip = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.TOP | Gravity.LEFT;
        int infoSize = dp(28);
        ip.leftMargin = (int) (cx + wheelR * 0.55f);
        ip.topMargin = (int) (cy + wheelR * 0.55f);
        infoButton.setLayoutParams(ip);
        infoButton.bringToFront();
    }

    private void layoutGlyphs(float cx, float cy, float r) {
        int n = glyphOverlay.getChildCount();
        float glyphR = r * 0.72f;
        for (int i = 0; i < n; i++) {
            View child = glyphOverlay.getChildAt(i);
            Object tag = child.getTag();
            float angleDeg;
            if (tag == HardwareButtonGlyph.Button.BACK) angleDeg = -90;      // top
            else if (tag == HardwareButtonGlyph.Button.NEXT) angleDeg = 0;    // right
            else if (tag == HardwareButtonGlyph.Button.PLAY_PAUSE) angleDeg = 90; // bottom
            else if (tag == HardwareButtonGlyph.Button.PREV) angleDeg = 180;  // left
            else if (tag == HardwareButtonGlyph.Button.OK) {
                // Centre
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                lp.leftMargin = Math.round(cx - child.getLayoutParams().width / 2f);
                lp.topMargin = Math.round(cy - child.getLayoutParams().height / 2f);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                child.setLayoutParams(lp);
                continue;
            } else {
                continue;
            }
            double rad = Math.toRadians(angleDeg);
            float gx = cx + (float) Math.cos(rad) * glyphR;
            float gy = cy + (float) Math.sin(rad) * glyphR;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.leftMargin = Math.round(gx - lp.width / 2f);
            lp.topMargin = Math.round(gy - lp.height / 2f);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            child.setLayoutParams(lp);
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** Clamp int to [lo, hi] — keeps glyph px sane on tiny/huge panels. */
    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static int contrastInk(int bg) {
        int r = (bg >> 16) & 0xFF;
        int g = (bg >> 8) & 0xFF;
        int b = bg & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return lum > 0.55 ? 0xFF222222 : 0xFFEEEEEE;
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            try { b.recycle(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 2026-07-20 — Body fill behind viewport + wheel; paints colour or texture.
     * Texture is centre-cropped to fill (not letterboxed).
     */
    static final class BodyPanel extends View {
        private int bodyColor = PhoneChromePrefs.DEFAULT_BODY;
        private Bitmap texture;
        private PhoneChromePolicy.LayoutMetrics metrics;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix textureMatrix = new Matrix();

        BodyPanel(Context ctx) {
            super(ctx);
        }

        void setBodyColor(int c) {
            bodyColor = c;
            invalidate();
        }

        void setBodyTexture(Bitmap bmp) {
            texture = bmp;
            invalidate();
        }

        void setMetrics(PhoneChromePolicy.LayoutMetrics m) {
            metrics = m;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (texture != null && !texture.isRecycled()) {
                // 2026-07-20 — Scale-to-fill (centre crop) so skins cover the plastic face.
                // Was: BitmapShader 1:1 CLAMP — small photos sat top-left. Reversal: drop Matrix.
                float bw = texture.getWidth();
                float bh = texture.getHeight();
                if (bw > 0 && bh > 0) {
                    float scale = Math.max(w / bw, h / bh);
                    float dx = (w - bw * scale) * 0.5f;
                    float dy = (h - bh * scale) * 0.5f;
                    textureMatrix.reset();
                    textureMatrix.setScale(scale, scale);
                    textureMatrix.postTranslate(dx, dy);
                    BitmapShader shader = new BitmapShader(texture,
                            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader.setLocalMatrix(textureMatrix);
                    paint.setShader(shader);
                } else {
                    paint.setShader(null);
                    paint.setColor(bodyColor);
                }
            } else {
                paint.setShader(null);
                paint.setColor(bodyColor);
            }
            canvas.drawRect(0, 0, w, h, paint);
            // Hard cut: no black bezel — viewport simply sits on top.
            if (metrics != null && metrics.bodyFill) {
                // Viewport hole is covered by the Solar view; body already fills margins.
            }
        }
    }
}
