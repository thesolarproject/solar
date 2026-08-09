package com.solar.launcher.stem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Hardware-faithful Stem Player puck — tan silicone disc, 4 LEDs per arm, recessed center.
 * Hallmark · component: stem-face · genre: atmospheric · theme: studied-DNA (Stem Player photo)
 * Layman: looks like Ye’s Stem Player on the screen; lit beads show how loud each stem is.
 * Technical: Canvas cross of 4 diffused white LEDs/arm; focus tints glow; gain drives lit count.
 * Was: 8 coloured Activity dots + dark center. Reversal: restore DOTS=8 + STEM_COLORS on dots.
 * 2026-07-18
 */
public class StemFaceView extends View {
    /** Zone accent for focus ring / status (Watch DNA) — LEDs stay white like hardware. */
    public static final int[] STEM_COLORS = {
            0xFFFA114F, // Vocals (N)
            0xFF00C7BE, // Bass (W) — StemFM compass
            0xFFFF9F0A, // Melody (E) — StemFM compass
            0xFF92E82A, // Drums (S) — StemFM compass
    };

    /** Real Stem Player face uses four LEDs per trough. */
    public static final int DOTS_PER_ARM = 4;

    private static final int PUCK = 0xFFC5B4A3;
    private static final int PUCK_HIGH = 0xFFD4C6B6;
    private static final int PUCK_EDGE = 0xFFA89888;
    private static final int FIELD = 0xFF000000;
    private static final int LED_WHITE = 0xFFFFF8F0;
    private static final int LED_GLOW = 0x66FFF8F0;
    private static final int RECESS = 0x28000000;

    private final Paint fieldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint puckPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint recessPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] gains = new float[] { 0f, 0f, 0f, 0f };
    private int activeZone = 0;
    private boolean armed;
    private boolean looping;
    private boolean loading;
    private boolean stuttering;
    private float loopBars = StemControls.DEFAULT_LOOP_BARS;
    /** Song numbers 1..3 per arm; 0 = hide digit. */
    private final int[] songDigits = new int[4];
    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public StemFaceView(Context context) {
        super(context);
        init();
    }

    public StemFaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        fieldPaint.setColor(FIELD);
        fieldPaint.setStyle(Paint.Style.FILL);
        puckPaint.setStyle(Paint.Style.FILL);
        recessPaint.setStyle(Paint.Style.STROKE);
        recessPaint.setStrokeCap(Paint.Cap.ROUND);
        recessPaint.setColor(RECESS);
        glowPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);
        centerPaint.setStyle(Paint.Style.FILL);
        centerShadePaint.setStyle(Paint.Style.STROKE);
        centerShadePaint.setColor(0x33000000);
        digitPaint.setStyle(Paint.Style.FILL);
        digitPaint.setTextAlign(Paint.Align.CENTER);
        digitPaint.setFakeBoldText(true);
    }

    /**
     * Push mixer + UI mode into the face.
     * Layman: update which lights are on and which arm is selected.
     * 2026-07-19 — songDigits 1..3; stuttering pulses active arm when beat roll active.
     */
    public void setState(float[] g, int zone, boolean isArmed, boolean isLooping,
            float bars, boolean isLoading) {
        setState(g, zone, isArmed, isLooping, bars, isLoading, null, false);
    }

    public void setState(float[] g, int zone, boolean isArmed, boolean isLooping,
            float bars, boolean isLoading, int[] songs, boolean isStuttering) {
        if (g != null) {
            for (int i = 0; i < 4 && i < g.length; i++) {
                gains[i] = StemControls.clampGain(g[i]);
            }
        }
        activeZone = zone;
        armed = isArmed;
        looping = isLooping;
        loopBars = bars;
        loading = isLoading;
        stuttering = isStuttering;
        if (songs != null) {
            for (int i = 0; i < 4 && i < songs.length; i++) {
                songDigits[i] = songs[i];
            }
        }
        invalidate();
    }

    public void setActiveZone(int zone) {
        activeZone = zone;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawRect(0, 0, w, h, fieldPaint);

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        // Fill most of the 480×360 viewport like holding the puck.
        float radius = Math.min(w, h) * 0.46f;

        RadialGradient grad = new RadialGradient(
                cx - radius * 0.18f, cy - radius * 0.22f, radius * 1.15f,
                new int[] { PUCK_HIGH, PUCK, PUCK_EDGE },
                new float[] { 0.0f, 0.55f, 1f },
                Shader.TileMode.CLAMP);
        puckPaint.setShader(grad);
        canvas.drawCircle(cx, cy, radius, puckPaint);
        puckPaint.setShader(null);

        float armInner = radius * 0.20f;
        float armOuter = radius * 0.86f;
        float recessW = Math.max(14f, radius * 0.11f);
        recessPaint.setStrokeWidth(recessW);

        for (int arm = 0; arm < 4; arm++) {
            drawArm(canvas, cx, cy, arm, armInner, armOuter, recessW, radius);
        }

        // Center OK — same silicone colour, soft recess (matches photo).
        float centerR = radius * 0.10f;
        centerPaint.setColor(PUCK);
        if (loading || looping || stuttering) {
            float pulse = (float) (0.88 + 0.12 * Math.sin(System.currentTimeMillis() / 240.0));
            centerPaint.setAlpha(Math.round(255 * pulse));
        } else {
            centerPaint.setAlpha(255);
        }
        canvas.drawCircle(cx, cy, centerR, centerPaint);
        centerShadePaint.setStrokeWidth(Math.max(2.5f, radius * 0.014f));
        if (stuttering) {
            centerShadePaint.setColor(STEM_COLORS[clampZone(activeZone)] & 0x00FFFFFF | 0xCC000000);
        } else if (armed) {
            centerShadePaint.setColor(STEM_COLORS[clampZone(activeZone)] & 0x00FFFFFF | 0xAA000000);
        } else if (looping) {
            centerShadePaint.setColor(0x88FFFFFF);
        } else {
            centerShadePaint.setColor(0x40000000);
        }
        canvas.drawCircle(cx, cy, centerR + 1.5f, centerShadePaint);

        if (loading || looping || stuttering) {
            postInvalidateDelayed(40);
        }
    }

    /** One recessed trough + LEDs + optional song digit. Silent pads dim calmly. 2026-07-19 / 2026-07-21 */
    private void drawArm(Canvas canvas, float cx, float cy, int arm,
            float inner, float outer, float recessW, float radius) {
        float dx = 0f;
        float dy = 0f;
        if (arm == 0) dy = -1f;
        else if (arm == 1) dx = -1f;
        else if (arm == 2) dx = 1f;
        else dy = 1f;

        float x0 = cx + dx * inner;
        float y0 = cy + dy * inner;
        float x1 = cx + dx * outer;
        float y1 = cy + dy * outer;
        // Mute dim — darker trough when pad gain ~0 (visual only). 2026-07-21
        // Was: recess always full RECESS. Reversal: recessPaint.setAlpha(255) only.
        float silentMul = StemControls.padSilentVisualMul(gains[arm]);
        int recessA = Math.round(0x28 * silentMul);
        if (StemControls.padFaceShouldDim(gains[arm])) {
            recessA = Math.max(recessA, 0x48);
        }
        recessPaint.setColor((RECESS & 0x00FFFFFF) | (recessA << 24));
        canvas.drawLine(x0, y0, x1, y1, recessPaint);

        int lit;
        if (loading) {
            float pulse = (float) (0.35 + 0.4 * Math.sin(System.currentTimeMillis() / 300.0 + arm));
            lit = StemControls.dotsForGain(pulse, DOTS_PER_ARM);
        } else if (stuttering && arm == activeZone) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 80.0));
            lit = StemControls.dotsForGain(pulse, DOTS_PER_ARM);
        } else if (looping && arm == activeZone && activeZone >= 0) {
            // Loop-bar LEDs only while host says wheel is in loop mode. 2026-07-19
            lit = StemControls.dotsForLoopBars(loopBars, DOTS_PER_ARM);
        } else {
            // Volume beads — fill/shrink with pad gain (0 = dark, 1 = all lit). 2026-07-19
            lit = StemControls.dotsForGain(gains[arm], DOTS_PER_ARM);
        }

        boolean focus = activeZone >= 0 && arm == activeZone;
        float dotR = recessW * 0.28f;
        int accent = STEM_COLORS[arm];
        int ledAlphaScale = Math.round(255 * silentMul);

        for (int i = 0; i < DOTS_PER_ARM; i++) {
            float t = (i + 0.55f) / (DOTS_PER_ARM + 0.2f);
            float px = x0 + (x1 - x0) * t;
            float py = y0 + (y1 - y0) * t;
            if (i >= lit) continue;

            float bloom = focus && armed ? dotR * 2.4f : (focus ? dotR * 2.0f : dotR * 1.7f);
            if (focus) {
                glowPaint.setColor(accent);
                glowPaint.setAlpha(Math.round((focus && armed ? 90 : 55) * silentMul));
            } else {
                glowPaint.setColor(LED_GLOW);
                glowPaint.setAlpha(Math.round(70 * silentMul));
            }
            canvas.drawCircle(px, py, bloom, glowPaint);

            dotPaint.setColor(LED_WHITE);
            dotPaint.setAlpha(Math.min(ledAlphaScale, focus ? 255 : 220));
            canvas.drawCircle(px, py, focus && armed ? dotR * 1.15f : dotR, dotPaint);
        }

        // Soft dark wash when muted so empty trough still reads as “off”. 2026-07-21
        int wash = StemControls.padSilentDimOverlayAlpha(gains[arm]);
        if (wash > 0) {
            glowPaint.setColor(0xFF000000);
            glowPaint.setAlpha(wash);
            float midX = (x0 + x1) * 0.5f;
            float midY = (y0 + y1) * 0.5f;
            canvas.drawCircle(midX, midY, recessW * 0.85f, glowPaint);
        }

        // Song 1/2/3 beside outer end of arm when multi-track.
        int dig = songDigits[arm];
        if (dig >= 1 && dig <= 3) {
            digitPaint.setTextSize(Math.max(12f, radius * 0.11f));
            int digColor = focus ? accent : 0xFF5A5048;
            int a = Math.round(((digColor >>> 24) & 0xFF) * silentMul);
            if (a < 1) a = Math.round(255 * silentMul);
            digitPaint.setColor((digColor & 0x00FFFFFF) | (a << 24));
            float tx = x1 + dx * recessW * 0.55f;
            float ty = y1 + dy * recessW * 0.55f + digitPaint.getTextSize() * 0.35f;
            canvas.drawText(String.valueOf(dig), tx, ty, digitPaint);
        }
    }

    private static int clampZone(int z) {
        if (z < 0) return 0;
        if (z > 3) return 3;
        return z;
    }
}
