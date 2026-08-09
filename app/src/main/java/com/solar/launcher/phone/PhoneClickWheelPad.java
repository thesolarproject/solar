package com.solar.launcher.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.solar.launcher.Y1InputKeys;

/**
 * 2026-07-20 — On-screen click wheel that injects synthetic Y1InputKeys into MainActivity.
 * Glyphs painted by PhoneChromeHost; this view owns touch → key only.
 * Reversal: remove from host — hardware keys unchanged.
 */
public final class PhoneClickWheelPad extends View {

    /** Delivers a synthetic key to the activity. */
    public interface KeySink {
        /** Short tap (cardinal button): DOWN then UP. */
        void injectKey(int keyCode);
        /** Long press: DOWN with repeatCount=1 (marker), then UP. */
        void injectKeyLongPress(int keyCode);
        /**
         * 2026-08-02 — Scroll wheel notch: DOWN-only with incrementing repeatCount.
         * Real hardware sends a stream of DOWN events (repeatCount 0,1,2...) and
         * one UP when the finger lifts. Phone chrome must match this pattern so
         * WheelNavPolicy / ListWheelCoalescer work correctly.
         * The UP (held=false) is sent via {@link #injectWheelUp(int)} on finger lift.
         */
        void injectWheelScroll(int keyCode, int repeatCount);
        /**
         * 2026-08-02 — Finger lifted from the wheel. Sends UP for the active scroll
         * direction so wheelKeyHeld clears and the list stops scrolling.
         */
        void injectWheelUp(int keyCode);
    }

    private KeySink sink;
    private float centreX;
    private float centreY;
    private float radius;
    private int wheelColor = PhoneChromePrefs.DEFAULT_WHEEL;
    private Bitmap texture;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private PhoneClickWheel.Zone downZone;
    private float lastDx;
    private float lastDy;
    private float accumRad;
    private boolean dragged;
    private boolean longArmed;
    /**
     * 2026-08-02 — Current scroll-wheel keycode for held gesture (127=down, 126=up).
     * -1 when idle. Used to send UP on finger lift.
     */
    private int scrollKeyCode = -1;
    /** 2026-08-02 — Repeat count for the current scroll gesture (increments per notch). */
    private int scrollRepeatCount = 0;
    private final Runnable longCenter = new Runnable() {
        @Override
        public void run() {
            if (!dragged && downZone == PhoneClickWheel.Zone.CENTER && sink != null) {
                longArmed = true;
                sink.injectKeyLongPress(Y1InputKeys.KEY_CENTER);
            }
        }
    };
    private final Runnable longBack = new Runnable() {
        @Override
        public void run() {
            if (!dragged && downZone == PhoneClickWheel.Zone.MENU && sink != null) {
                longArmed = true;
                sink.injectKeyLongPress(Y1InputKeys.KEY_BACK);
            }
        }
    };
    private final Runnable longPrev = new Runnable() {
        @Override
        public void run() {
            if (!dragged && downZone == PhoneClickWheel.Zone.PREV && sink != null) {
                longArmed = true;
                sink.injectKeyLongPress(Y1InputKeys.KEY_TRACK_PREV);
            }
        }
    };
    private final Runnable longNext = new Runnable() {
        @Override
        public void run() {
            if (!dragged && downZone == PhoneClickWheel.Zone.NEXT && sink != null) {
                longArmed = true;
                sink.injectKeyLongPress(Y1InputKeys.KEY_TRACK_NEXT);
            }
        }
    };
    private final Runnable longPlay = new Runnable() {
        @Override
        public void run() {
            if (!dragged && downZone == PhoneClickWheel.Zone.PLAY && sink != null) {
                longArmed = true;
                sink.injectKeyLongPress(Y1InputKeys.KEY_PLAY_PAUSE);
            }
        }
    };

    public PhoneClickWheelPad(Context context) {
        super(context);
        init();
    }

    public PhoneClickWheelPad(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(2f);
        rimPaint.setColor(0x44000000);
        setClickable(true);
        setFocusable(false);
    }

    /** Wire key injection target (usually MainActivity). */
    public void setKeySink(KeySink sink) {
        this.sink = sink;
    }

    /** Update geometry after layout. */
    public void setWheelGeometry(float cx, float cy, float r) {
        centreX = cx;
        centreY = cy;
        radius = r;
        invalidate();
    }

    /** Solid ring colour (ignored when texture bound). */
    public void setWheelColor(int argb) {
        wheelColor = argb;
        invalidate();
    }

    /** Optional photo texture; recycled by caller when replaced. */
    public void setWheelTexture(Bitmap bmp) {
        texture = bmp;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0f) return;
        if (texture != null && !texture.isRecycled()) {
            BitmapShader shader = new BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            fillPaint.setShader(shader);
        } else {
            fillPaint.setShader(null);
            fillPaint.setColor(wheelColor);
        }
        canvas.drawCircle(centreX, centreY, radius, fillPaint);
        // Centre disc (slightly lighter)
        fillPaint.setShader(null);
        int center = lighten(wheelColor, 0.18f);
        fillPaint.setColor(center);
        canvas.drawCircle(centreX, centreY, radius * PhoneClickWheel.CENTER_FRAC, fillPaint);
        canvas.drawCircle(centreX, centreY, radius, rimPaint);
        canvas.drawCircle(centreX, centreY, radius * PhoneClickWheel.CENTER_FRAC, rimPaint);
    }

    private static int lighten(int color, float amount) {
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = Math.min(255, r + (int) ((255 - r) * amount));
        g = Math.min(255, g + (int) ((255 - g) * amount));
        b = Math.min(255, b + (int) ((255 - b) * amount));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (sink == null || radius <= 0f || event == null) return super.onTouchEvent(event);
        float dx = event.getX() - centreX;
        float dy = event.getY() - centreY;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downZone = PhoneClickWheel.zoneAt(dx, dy, radius);
            lastDx = dx;
            lastDy = dy;
            accumRad = 0f;
            dragged = false;
            longArmed = false;
            removeCallbacks(longCenter);
            removeCallbacks(longBack);
            if (downZone == PhoneClickWheel.Zone.CENTER) {
                postDelayed(longCenter, 500);
            } else if (downZone == PhoneClickWheel.Zone.MENU) {
                postDelayed(longBack, 500);
            } else if (downZone == PhoneClickWheel.Zone.PREV) {
                postDelayed(longPrev, 500);
            } else if (downZone == PhoneClickWheel.Zone.NEXT) {
                postDelayed(longNext, 500);
            } else if (downZone == PhoneClickWheel.Zone.PLAY) {
                postDelayed(longPlay, 500);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (downZone == PhoneClickWheel.Zone.RING
                    || downZone == PhoneClickWheel.Zone.MENU
                    || downZone == PhoneClickWheel.Zone.PREV
                    || downZone == PhoneClickWheel.Zone.NEXT
                    || downZone == PhoneClickWheel.Zone.PLAY) {
                // Once finger moves on the ring, treat as scroll.
                float step = PhoneClickWheel.angleDeltaRadians(lastDx, lastDy, dx, dy);
                if (Math.abs(step) > 0.02f) {
                    dragged = true;
                    removeCallbacks(longCenter);
                    removeCallbacks(longBack);
                    removeCallbacks(longPrev);
                    removeCallbacks(longNext);
                    removeCallbacks(longPlay);
                    // Promote cardinal start into ring drag after movement.
                    downZone = PhoneClickWheel.Zone.RING;
                    accumRad += step;
                    int notches = PhoneClickWheel.notchesFromAccumulated(accumRad);
                    if (notches != 0) {
                        int key = notches > 0
                                ? PhoneClickWheel.wheelDownKeyCode()
                                : PhoneClickWheel.wheelUpKeyCode();
                        int abs = Math.abs(notches);
                        // 2026-08-02 — Reset repeat count when scroll direction changes.
                        if (key != scrollKeyCode) {
                            scrollRepeatCount = 0;
                        }
                        scrollKeyCode = key;
                        for (int i = 0; i < abs; i++) {
                            sink.injectWheelScroll(key, scrollRepeatCount++);
                        }
                        accumRad -= notches * PhoneClickWheel.NOTCH_RADIANS;
                    }
                }
            }
            lastDx = dx;
            lastDy = dy;
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longCenter);
            removeCallbacks(longBack);
            removeCallbacks(longPrev);
            removeCallbacks(longNext);
            removeCallbacks(longPlay);
            if (dragged && scrollKeyCode >= 0) {
                // 2026-08-02 — Finger lifted from ring drag: send UP to clear held state.
                sink.injectWheelUp(scrollKeyCode);
                scrollKeyCode = -1;
                scrollRepeatCount = 0;
            } else if (!dragged && !longArmed && downZone != null && downZone != PhoneClickWheel.Zone.RING) {
                int code = PhoneClickWheel.keyCodeForZone(downZone);
                if (code != 0) sink.injectKey(code);
            }
            downZone = null;
            return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * 2026-07-20 — Helper used by host tests / injectors: build KeyEvent pair timestamps.
     */
    public static KeyEvent[] downUp(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now + 1, KeyEvent.ACTION_UP, keyCode, 0);
        return new KeyEvent[] { down, up };
    }

    /**
     * Phone Chrome long press marker: one DOWN with repeat=1 followed by one UP.
     * The Stem host treats this as a complete one-shot hold, never as a repeating key.
     * 2026-08-03
     */
    public static KeyEvent[] longPressDownUp(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 1);
        KeyEvent up = new KeyEvent(now, now + 1, KeyEvent.ACTION_UP, keyCode, 0);
        return new KeyEvent[] { down, up };
    }
}
