package com.solar.launcher;

import android.content.Context;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.TextView;

import com.solar.launcher.DeviceFeatures;
import com.solar.launcher.theme.ThemeManager;
import com.solar.launcher.ui.HardwareButtonGlyph;

/**
 * 2026-07-05 — Binds the shared full-screen wheel keyboard layout to a controller.
 * Layman: paints title, typed text, and the five letter slots — same look as Wi‑Fi / search keyboards.
 * Technical: theme row chrome via ThemeManager; used by Solar IME overlay and optionally MainActivity.
 * 2026-07-14 — Touch: A5FocusConfirm on slots + horizontal drag scroll (lists/Flow style).
 */
public final class SolarWheelKeyboardUi {

    /** Pixels of horizontal drag per one charset step. */
    private static final float DRAG_STEP_PX = 28f;
    /** Min fling velocity (px/s) to keep stepping after lift. */
    private static final float FLING_MIN_VX = 400f;

    private final Context context;
    private final TextView tvTitle;
    private final TextView tvInput;
    private final TextView tvPprev;
    private final TextView tvPrev;
    private final TextView tvCurrent;
    private final TextView tvNext;
    private final TextView tvNnext;
    private final TextView tvHint;
    private final int rowHeightPx;
    private final int screenWidthPx;
    private final String enterLabel;

    /**
     * 2026-07-14 — Host that owns index / confirm for touch (controller or MainActivity).
     * Layman: whatever drives the letter strip implements these so fingers can move it.
     */
    public interface TouchHost {
        int getIndex();
        int charsetLength();
        void setIndex(int idx);
        void confirmCurrent();
        void wheelBy(int steps);
    }

    public SolarWheelKeyboardUi(Context context, View root, String enterLabel) {
        this.context = context.getApplicationContext();
        this.enterLabel = enterLabel != null ? enterLabel : "[ENT]";
        tvTitle = (TextView) root.findViewById(R.id.tv_keyboard_title);
        tvInput = (TextView) root.findViewById(R.id.tv_keyboard_input);
        tvPprev = (TextView) root.findViewById(R.id.tv_key_pprev);
        tvPrev = (TextView) root.findViewById(R.id.tv_key_prev);
        tvCurrent = (TextView) root.findViewById(R.id.tv_key_current);
        tvNext = (TextView) root.findViewById(R.id.tv_key_next);
        tvNnext = (TextView) root.findViewById(R.id.tv_key_nnext);
        tvHint = (TextView) root.findViewById(R.id.tv_keyboard_hint);
        rowHeightPx = context.getResources().getDimensionPixelSize(R.dimen.y1_menu_item_height);
        screenWidthPx = context.getResources().getDisplayMetrics().widthPixels;
        applyTheme();
    }

    /** Theme fonts, row tiles, and hint line — matches in-app keyboard chrome. */
    public void applyTheme() {
        if (tvTitle != null) {
            ThemeManager.applyThemedTextStyle(tvTitle, ThemeManager.getSectionHeaderTextColor());
        }
        float menuTextPx = context.getResources().getDimension(R.dimen.y1_menu_text_size);
        int keyPad = (int) (6 * context.getResources().getDisplayMetrics().density);
        int layoutPad = (int) (15 * context.getResources().getDisplayMetrics().density);
        int inputRowW = screenWidthPx - layoutPad * 2;
        int keyW = (int) (52 * context.getResources().getDisplayMetrics().density);
        if (tvInput != null) {
            tvInput.setMinHeight(rowHeightPx);
            tvInput.setPadding(keyPad, 0, keyPad, 0);
            tvInput.setTextSize(TypedValue.COMPLEX_UNIT_PX, menuTextPx);
            tvInput.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            tvInput.setGravity(android.view.Gravity.CENTER);
        }
        styleKey(tvPprev, menuTextPx, keyPad, ThemeManager.getDimmedTextColor(0x55), 0);
        styleKey(tvPrev, menuTextPx, keyPad, ThemeManager.getTextColorPrimary(), 0);
        styleKey(tvNext, menuTextPx, keyPad, ThemeManager.getTextColorPrimary(), 0);
        styleKey(tvNnext, menuTextPx, keyPad, ThemeManager.getDimmedTextColor(0x55), 0);
        if (tvCurrent != null) {
            tvCurrent.setPadding(keyPad, keyPad / 2, keyPad, keyPad / 2);
            tvCurrent.setTextSize(TypedValue.COMPLEX_UNIT_PX, menuTextPx);
            tvCurrent.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
            android.graphics.drawable.Drawable bg = ThemeManager.getItemRowBackgroundScaled(
                    context.getResources(), true, keyW, rowHeightPx);
            if (bg != null) tvCurrent.setBackground(bg);
            ThemeManager.applyThemedTextStyle(tvCurrent, ThemeManager.getListButtonFocusedTextColor());
        }
        if (tvHint != null) {
            tvHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, menuTextPx * 0.85f);
            // 2026-07-20 — ImageSpan hints need system typeface (theme font tofu-boxes U+FFFC).
            tvHint.setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL);
            ThemeManager.applyThemedTextStyle(tvHint, ThemeManager.getHintTextColor());
            // 2026-07-18 — Y1/Y2: glyph legend (Prev/Play/Next/Wheel/OK/Back). A5 keeps setHintText(a5).
            // Reversal: remove branch — XML @string/keyboard_hint stays as inflate default.
            if (!DeviceFeatures.isA5()) {
                tvHint.setIncludeFontPadding(true);
                tvHint.setLineSpacing(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f,
                                context.getResources().getDisplayMetrics()),
                        1.1f);
                HardwareButtonGlyph.bindGlyphText(tvHint,
                        HardwareButtonGlyph.keyboardHint(context));
            }
        }
        if (tvInput != null) {
            android.graphics.drawable.Drawable bg = ThemeManager.getItemRowBackgroundScaled(
                    context.getResources(), true, inputRowW, rowHeightPx);
            if (bg != null) tvInput.setBackground(bg);
        }
    }

    private void styleKey(TextView tv, float textPx, int pad, int color, int bgColor) {
        if (tv == null) return;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        tv.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        ThemeManager.applyThemedTextStyle(tv, color);
        tv.setBackgroundColor(bgColor);
    }

    /** Refresh title, buffer line, and charset strip from controller state. */
    public void refresh(SolarWheelKeyboardController controller, String title,
            String inputText, boolean inputPlaceholder) {
        if (controller == null) return;
        int idx = controller.getIndex();
        if (tvTitle != null) {
            if (title != null && title.length() > 0) {
                tvTitle.setText(title);
                tvTitle.setVisibility(View.VISIBLE);
            } else {
                tvTitle.setVisibility(View.GONE);
            }
        }
        if (tvInput != null) {
            tvInput.setText(inputText != null ? inputText : "");
            tvInput.setSelected(!inputPlaceholder);
            ThemeManager.applyThemedTextStyle(tvInput, inputPlaceholder
                    ? ThemeManager.getHintTextColor()
                    : ThemeManager.getListButtonFocusedTextColor());
        }
        setKeyLabel(controller, tvPprev, idx, -2);
        setKeyLabel(controller, tvPrev, idx, -1);
        setKeyLabel(controller, tvCurrent, idx, 0);
        setKeyLabel(controller, tvNext, idx, 1);
        setKeyLabel(controller, tvNnext, idx, 2);
    }

    private void setKeyLabel(SolarWheelKeyboardController controller, TextView tv,
            int baseIdx, int offset) {
        if (tv == null || controller == null) return;
        int i = offset == 0 ? baseIdx : controller.wrapActiveIndex(baseIdx, offset);
        tv.setText(SolarWheelKeyboardController.displayChar(controller.charAt(i), enterLabel,
                controller.isPasswordVisible()));
    }

    /**
     * 2026-07-14 — A5/touch: focus-then-confirm on slots + drag on strip parent.
     * Layman: poke a letter to highlight it, poke again to type; swipe the row to scroll.
     * Tech: A5FocusConfirm + VelocityTracker; offset −2…+2 → setIndex / centerPress.
     * Reversal: skip attachTouch; strip stays key-only.
     */
    public void attachTouch(final SolarWheelKeyboardController controller) {
        if (controller == null) return;
        attachTouchSlots(
                new TextView[] { tvPprev, tvPrev, tvCurrent, tvNext, tvNnext },
                tvCurrent != null ? (View) tvCurrent.getParent() : null,
                new TouchHost() {
                    @Override
                    public int getIndex() {
                        return controller.getIndex();
                    }

                    @Override
                    public int charsetLength() {
                        return controller.getCharset().length;
                    }

                    @Override
                    public void setIndex(int idx) {
                        controller.setIndex(idx);
                    }

                    @Override
                    public void confirmCurrent() {
                        controller.centerPress();
                    }

                    @Override
                    public void wheelBy(int steps) {
                        if (steps > 0) {
                            for (int i = 0; i < steps; i++) controller.wheelDown();
                        } else if (steps < 0) {
                            for (int i = 0; i < -steps; i++) controller.wheelUp();
                        }
                    }
                });
    }

    /**
     * 2026-07-14 — IME touch: confirm must commit via InputConnection (not buffer-only).
     * Layman: tapping a letter puts it into the other app's text box.
     * Tech: confirm Runnable → applyCenterSelection; drag still uses controller.wheel*.
     */
    public void attachTouchSlotsForIme(final SolarWheelKeyboardController controller,
            final Runnable confirmWithCommit) {
        if (controller == null || confirmWithCommit == null) return;
        attachTouchSlots(
                new TextView[] { tvPprev, tvPrev, tvCurrent, tvNext, tvNnext },
                tvCurrent != null ? (View) tvCurrent.getParent() : null,
                new TouchHost() {
                    @Override
                    public int getIndex() {
                        return controller.getIndex();
                    }

                    @Override
                    public int charsetLength() {
                        return controller.getCharset().length;
                    }

                    @Override
                    public void setIndex(int idx) {
                        controller.setIndex(idx);
                    }

                    @Override
                    public void confirmCurrent() {
                        confirmWithCommit.run();
                    }

                    @Override
                    public void wheelBy(int steps) {
                        if (steps > 0) {
                            for (int i = 0; i < steps; i++) controller.wheelDown();
                        } else if (steps < 0) {
                            for (int i = 0; i < -steps; i++) controller.wheelUp();
                        }
                    }
                });
    }

    /**
     * 2026-07-14 — Shared touch wiring for IME shell and MainActivity in-app keys.
     * Layman: same finger rules wherever the five-letter strip lives.
     */
    public static void attachTouchSlots(TextView[] slots, View dragStrip, final TouchHost host) {
        if (host == null || slots == null || !A5FocusConfirm.enabled()) return;
        final int[] offsets = { -2, -1, 0, 1, 2 };
        final DragScrollListener drag = new DragScrollListener(host);
        for (int i = 0; i < slots.length && i < offsets.length; i++) {
            final TextView slot = slots[i];
            final int offset = offsets[i];
            if (slot == null) continue;
            A5FocusConfirm.prepareRow(slot);
            final View.OnClickListener activate = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // #region agent log
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("offset", offset);
                        d.put("focused", v != null && v.isFocused());
                        d.put("drag", drag.consumedAsDrag());
                        d.put("idx", host.getIndex());
                        Debug670453Log.log(slot != null ? slot.getContext() : null,
                                "SolarWheelKeyboardUi.attachTouchSlots", "activate click", "H5", d);
                    } catch (Exception ignored) {}
                    // #endregion
                    if (drag.consumedAsDrag()) return;
                    int len = host.charsetLength();
                    if (len <= 0) return;
                    int target = SolarWheelKeyboardController.wrapIndex(host.getIndex(), offset, len);
                    if (offset == 0 || target == host.getIndex()) {
                        host.confirmCurrent();
                    } else {
                        host.setIndex(target);
                        if (v != null) v.requestFocus();
                    }
                }
            };
            // Drag on the letter itself so MOVE is not eaten by a non-listening parent.
            slot.setOnTouchListener(drag);
            A5FocusConfirm.setOnClickListener(slot, activate);
        }
        if (dragStrip != null) {
            dragStrip.setOnTouchListener(drag);
        }
    }

    /**
     * 2026-07-14 — Horizontal drag/fling steps the charset like Flow free-scroll.
     * Layman: slide your finger across the letter row to spin the alphabet.
     * Tech: accumulate dx / DRAG_STEP_PX; fling uses VelocityTracker sign.
     */
    private static final class DragScrollListener implements View.OnTouchListener {
        private final TouchHost host;
        private float lastX;
        private float accum;
        private boolean dragging;
        private boolean lastStrokeWasDrag;
        private VelocityTracker tracker;

        DragScrollListener(TouchHost host) {
            this.host = host;
        }

        /** True when the last stroke scrolled — suppress click confirm. */
        boolean consumedAsDrag() {
            return lastStrokeWasDrag;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (host == null || event == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                accum = 0f;
                dragging = false;
                lastStrokeWasDrag = false;
                if (tracker != null) tracker.recycle();
                tracker = VelocityTracker.obtain();
                tracker.addMovement(event);
                return false;
            }
            if (tracker != null) tracker.addMovement(event);
            if (action == MotionEvent.ACTION_MOVE) {
                float x = event.getX();
                float dx = x - lastX;
                lastX = x;
                accum += dx;
                while (accum <= -DRAG_STEP_PX) {
                    host.wheelBy(1);
                    accum += DRAG_STEP_PX;
                    dragging = true;
                }
                while (accum >= DRAG_STEP_PX) {
                    host.wheelBy(-1);
                    accum -= DRAG_STEP_PX;
                    dragging = true;
                }
                if (dragging) lastStrokeWasDrag = true;
                return dragging;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                boolean wasDrag = dragging;
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000);
                    float vx = tracker.getXVelocity();
                    tracker.recycle();
                    tracker = null;
                    if (wasDrag && Math.abs(vx) >= FLING_MIN_VX) {
                        int steps = (int) (vx / -FLING_MIN_VX);
                        if (steps > 6) steps = 6;
                        if (steps < -6) steps = -6;
                        if (steps != 0) host.wheelBy(steps);
                    }
                }
                lastStrokeWasDrag = wasDrag;
                dragging = false;
                accum = 0f;
                // false so click can still fire when it was a tap (wasDrag false).
                return wasDrag;
            }
            return false;
        }
    }

    /** Update A5 / Y1 hint copy on the shared strip. */
    public void setHintText(CharSequence hint) {
        if (tvHint != null && hint != null) {
            tvHint.setText(hint);
        }
    }

    public TextView getHintView() {
        return tvHint;
    }
}
