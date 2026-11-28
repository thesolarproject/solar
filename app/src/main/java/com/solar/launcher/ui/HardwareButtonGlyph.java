package com.solar.launcher.ui;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.widget.TextView;

import com.solar.launcher.theme.ThemeManager;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 2026-07-18 — Y1/Y2 hardware button prompts as theme-tinted glyphs.
 * Was: plain text "Back" / "OK" / "Wheel" in hints. Now: monochrome PNGs from assets/y1
 * tinted to match decorative theme font color (PorterDuff SRC_IN).
 * Reversal: delete this class + btn_*.png; restore setText(R.string.*) call sites.
 * Layman: little button pictures that pick up the same ink colour as the menu text.
 * Technical: cache raw bitmaps; tint per paint; ImageSpan for inline prompts on API 17.
 */
public final class HardwareButtonGlyph {

    /** Hardware control shown as a glyph. */
    public enum Button {
        /** 2026-07-20 — Full Back/Options composite (back_options.svg @ 48px). Was chevron-only. */
        BACK("y1/btn_back.png"),
        OK("y1/btn_ok.png"),
        WHEEL("y1/btn_wheel.png"),
        PREV("y1/btn_prev.png"),
        NEXT("y1/btn_next.png"),
        /** 2026-07-20 — Play/Pause/Stop composite (play_pause.svg). Was play+pause only. */
        PLAY_PAUSE("y1/btn_play_pause.png");

        final String assetPath;

        Button(String assetPath) {
            this.assetPath = assetPath;
        }
    }

    /** Placeholder char ImageSpan replaces (must be a single code unit). */
    private static final char GLYPH_PLACEHOLDER = '\uFFFC';

    private static final Map<String, Bitmap> RAW_CACHE = new HashMap<String, Bitmap>();

    private HardwareButtonGlyph() {}

    /** 2026-07-18 — Default hint-line size (~14dp) for 480×360; scales with density. */
    public static int defaultSizePx(Context ctx) {
        if (ctx == null) return 14;
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 14f, ctx.getResources().getDisplayMetrics()));
    }

    /** 2026-07-18 — Slightly larger for section headers / tutorial bodies (~16dp). */
    public static int bodySizePx(Context ctx) {
        if (ctx == null) return 16;
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, ctx.getResources().getDisplayMetrics()));
    }

    /**
     * 2026-07-20 — Glyph height locked to a TextView’s letter box (font metrics).
     * Layman: button pictures match how tall the words on that line look.
     * Was: free-floating dp guesses (bodySizePx / tipSizePx) even when a TextView existed.
     * Reversal: callers pass bodySizePx/defaultSizePx again.
     */
    public static int sizePxMatchingTextView(TextView tv) {
        if (tv == null) return 1;
        return sizePxMatchingPaint(tv.getPaint());
    }

    /**
     * 2026-07-20 — Glyph height = paint ascent→descent (fallback: textSize).
     * Technical: max(1, round(fm.descent - fm.ascent)); empty paint → round(textSize).
     */
    public static int sizePxMatchingPaint(Paint paint) {
        if (paint == null) return 1;
        Paint.FontMetrics fm = paint.getFontMetrics();
        int h = Math.round(fm.descent - fm.ascent);
        if (h < 1) h = Math.round(paint.getTextSize());
        return Math.max(1, h);
    }

    /** 2026-07-20 — Positive sizePx wins; else bodySizePx fallback when no TextView yet. */
    private static int resolveBodySize(Context ctx, int sizePx) {
        return sizePx > 0 ? sizePx : bodySizePx(ctx);
    }

    /** 2026-07-20 — Positive sizePx wins; else defaultSizePx. */
    private static int resolveDefaultSize(Context ctx, int sizePx) {
        return sizePx > 0 ? sizePx : defaultSizePx(ctx);
    }

    /** 2026-07-20 — Positive sizePx wins; else tipSizePx. */
    private static int resolveTipSize(Context ctx, int sizePx) {
        return sizePx > 0 ? sizePx : tipSizePx(ctx);
    }

    /**
     * 2026-07-18 — Loads and caches the monochrome source PNG (black ink, alpha mask).
     * Fail-open: missing asset returns null so callers keep plain text.
     */
    public static Bitmap loadRaw(Context ctx, Button button) {
        if (ctx == null || button == null) return null;
        Bitmap cached = RAW_CACHE.get(button.assetPath);
        if (cached != null && !cached.isRecycled()) return cached;
        AssetManager am = ctx.getAssets();
        InputStream in = null;
        try {
            in = am.open(button.assetPath);
            Bitmap decoded = BitmapFactory.decodeStream(in);
            if (decoded != null) {
                RAW_CACHE.put(button.assetPath, decoded);
            }
            return decoded;
        } catch (Exception e) {
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("path", button.assetPath);
                d.put("err", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                com.solar.launcher.Debug0f5debLog.log(ctx, "HardwareButtonGlyph.loadRaw",
                        "asset miss", "KB-H3", d);
            } catch (Exception ignored) {}
            // #endregion
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 2026-07-18 — Clears bitmap cache (tests / theme asset hot-reload). */
    public static void clearCacheForTest() {
        RAW_CACHE.clear();
    }

    /**
     * 2026-07-18 — Tinted drawable sized to font height; width keeps bitmap aspect.
     * Was: square bounds (w=h=sizePx) which squashed wide Prev/Next/Play glyphs.
     * 2026-07-18 — Also: setTargetDensity rewrote draw size on API 17 → empty/cropped
     * “rectangles”. Now: scale bitmap to exact px then bounds=pixels (no density rewrite).
     * Layman: icons match letter height but stay their natural shape (not squeezed).
     * Reversal: setBounds(0,0,sizePx,sizePx) + setTargetDensity again.
     */
    public static Drawable tintedDrawable(Context ctx, Button button, int color, int sizePx) {
        Bitmap raw = loadRaw(ctx, button);
        if (raw == null) return null;
        int h = sizePx > 0 ? sizePx : defaultSizePx(ctx);
        int[] wh = boundsForFontHeight(raw.getWidth(), raw.getHeight(), h);
        Bitmap pixels = raw;
        if (raw.getWidth() != wh[0] || raw.getHeight() != wh[1]) {
            // Pixel-exact size for ImageSpan — aspect preserved by boundsForFontHeight.
            pixels = Bitmap.createScaledBitmap(raw, wh[0], wh[1], true);
        }
        BitmapDrawable d = new BitmapDrawable(ctx.getResources(), pixels);
        d.setFilterBitmap(true);
        // Bounds are already screen pixels — skip setTargetDensity (API 17 density rewrite
        // was drawing empty/cropped rectangles for wide Prev/Next/Play glyphs).
        d.setBounds(0, 0, wh[0], wh[1]);
        d.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        return d;
    }

    /**
     * 2026-07-18 — Map bitmap size to height=fontH with aspect preserved (min 1px).
     * Wide glyphs get width &gt; height; tall glyphs get width &lt; height.
     */
    public static int[] boundsForFontHeight(int bitmapW, int bitmapH, int fontHeightPx) {
        int h = fontHeightPx > 0 ? fontHeightPx : 1;
        if (bitmapW <= 0 || bitmapH <= 0) {
            return new int[] { h, h };
        }
        int w = Math.round(h * (bitmapW / (float) bitmapH));
        if (w < 1) w = 1;
        return new int[] { w, h };
    }

    /**
     * 2026-07-18 — Appends a tinted glyph into a spannable.
     * Was: stock ImageSpan ALIGN_BASELINE — last-line Back/cancel clipped to a thin strip
     * (only the chevron’s middle bar showed). Now: CenteredImageSpan grows line metrics
     * and draws the full bounds, aspect preserved.
     * Layman: the whole button picture shows, not a sliced streak.
     * Reversal: new ImageSpan(d, 1) again.
     */
    public static void appendGlyph(SpannableStringBuilder out, Context ctx, Button button,
            int color, int sizePx) {
        if (out == null || ctx == null || button == null) return;
        
        if (button == Button.WHEEL) {
            out.append("🗘");
            return;
        }
        
        Drawable d = tintedDrawable(ctx, button, color, sizePx);
        if (d == null) return;
        int start = out.length();
        out.append(GLYPH_PLACEHOLDER);
        out.setSpan(new CenteredImageSpan(d), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * 2026-07-18 — ImageSpan that keeps the full glyph visible inside TextView lines.
     * Stock ALIGN_BASELINE hangs the drawable above the baseline; TextView often clips
     * the top on the last keyboard-hint row — Back looked like a white dash.
     * This span expands FontMetrics to the drawable height and centres it in the line.
     */
    static final class CenteredImageSpan extends ImageSpan {
        CenteredImageSpan(Drawable drawable) {
            // ALIGN_BOTTOM = 0 — we ignore stock vertical align and draw ourselves.
            super(drawable, 0);
        }

        /** Reports drawable width and grows ascent/descent so the line fits the full icon. */
        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                Paint.FontMetricsInt fm) {
            Drawable d = getDrawable();
            if (d == null) return 0;
            int dw = d.getBounds().width();
            int dh = d.getBounds().height();
            if (fm != null) {
                // Centre glyph on the paint’s mid-line; expand so nothing is cropped.
                Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
                int fontH = pfm.descent - pfm.ascent;
                if (fontH < 1) fontH = dh > 0 ? dh : 1;
                int mid = pfm.ascent + fontH / 2;
                int half = dh / 2;
                fm.ascent = Math.min(pfm.ascent, mid - half);
                fm.descent = Math.max(pfm.descent, mid + (dh - half));
                fm.top = Math.min(pfm.top, fm.ascent);
                fm.bottom = Math.max(pfm.bottom, fm.descent);
            }
            return dw > 0 ? dw : 0;
        }

        /** Draws the full drawable centred between line top and bottom. */
        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, Paint paint) {
            Drawable d = getDrawable();
            if (d == null) return;
            canvas.save();
            int dh = d.getBounds().height();
            // Mid of the laid-out line — not baseline — so tall Back chevrons stay whole.
            int transY = top + ((bottom - top) - dh) / 2;
            canvas.translate(x, transY);
            d.draw(canvas);
            canvas.restore();
        }
    }

    /** 2026-07-18 — Appends glyph then a short label with a thin space between. */
    public static void appendGlyphLabel(SpannableStringBuilder out, Context ctx, Button button,
            int color, int sizePx, CharSequence label) {
        appendGlyph(out, ctx, button, color, sizePx);
        if (label != null && label.length() > 0) {
            // Non-breaking thin space — glyph stays glued to its verb (legend cells).
            out.append('\u202F');
            out.append(label);
        }
    }

    /**
     * 2026-07-20 — Hold prompts: “hold” left of the button picture, then the action.
     * Layman: you read hold → which button → what it does.
     * Was: glyph then “hold …” (hold sat on the right of the icon).
     * Technical: prose + ImageSpan + stripped action (drops a leading “hold” in labels/strings).
     * Reversal: appendGlyphLabel(…, "hold " + action).
     */
    public static void appendHoldGlyphLabel(SpannableStringBuilder out, Context ctx, Button button,
            int color, int sizePx, CharSequence actionAfterGlyph) {
        if (out == null) return;
        out.append(ctx != null ? ctx.getString(com.solar.launcher.R.string.hint_hold_word) : "hold");
        out.append('\u202F');
        appendGlyph(out, ctx, button, color, sizePx);
        CharSequence rest = stripLeadingHoldWord(actionAfterGlyph);
        if (rest != null && rest.length() > 0) {
            out.append('\u202F');
            out.append(rest);
        }
    }

    /**
     * 2026-07-20 — Drop a leading “hold” / “Hold” (+ spaces) so we don’t double the word.
     * Keeps the action text after the word (no em-dash in user copy).
     */
    static CharSequence stripLeadingHoldWord(CharSequence label) {
        if (label == null || label.length() == 0) return label;
        String s = label.toString().trim();
        if (s.length() >= 4 && s.regionMatches(true, 0, "hold", 0, 4)) {
            return s.substring(4).trim();
        }
        return label;
    }

    /**
     * 2026-07-18 — Legend separator between glyph+label cells (centred middot with room).
     * Hallmark: one visual rhythm — never trailing separators.
     */
    private static void appendLegendSep(SpannableStringBuilder out) {
        out.append(" \u00B7 ");
    }

    /**
     * 2026-07-20 — Bind spannable hint text with inline button glyphs.
     * Layman: button pictures + words without empty □ boxes.
     * Tech: theme TTF lacks U+FFFC; ImageSpan placeholders tofu if custom typeface is set.
     * Reversal: tv.setText(text) + ThemeManager.getCustomFont() on hint rows.
     */
    public static void bindGlyphText(TextView tv, CharSequence text) {
        if (tv == null) return;
        tv.setText(text != null ? text : "");
        // 2026-07-20 — System face only while ImageSpans are present (theme font → □).
        if (hasGlyphSpans(text)) {
            tv.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
        }
    }

    /**
     * 2026-07-20 — True when text carries button ImageSpans (U+FFFC placeholders).
     * Layman: this line has little button pictures mixed in.
     * Technical: applyFontToAllViews must skip these or theme TTF draws □ tofu.
     */
    public static boolean hasGlyphSpans(CharSequence text) {
        if (!(text instanceof Spanned)) return false;
        ImageSpan[] spans = ((Spanned) text).getSpans(0, text.length(), ImageSpan.class);
        return spans != null && spans.length > 0;
    }

    /** 2026-07-18 — Hint-tint colour matching decorative theme fonts. */
    public static int hintTint() {
        return ThemeManager.getHintTextColor();
    }

    /** 2026-07-18 — Primary/item text tint for headers and list chrome. */
    public static int itemTint() {
        return ThemeManager.getItemTextColorNormal();
    }

    /**
     * 2026-07-20 — Status-strip ink so button pictures match the clock/title colour.
     * Layman: icons use the same paint as the status bar letters.
     */
    public static int statusTint() {
        return ThemeManager.getStatusBarTextColor();
    }

    /**
     * 2026-07-20 — Wheel keyboard legend: edit row + hold charset + cancel.
     * Was: also “pick” / “type” (wheel/OK) — intuitive, ate width on 480px.
     * 2026-07-20 — Hold tip: “for Capitals and Symbols” (was cryptic Aa/#).
     * Layman: delete / OK / space; hold Play/Pause/Stop for capitals & symbols; Back cancels.
     * Technical: hold left of PP glyph; no WHEEL/OK legend cells on row 2.
     * Reversal: restore pick/type cells; label “Aa/#” again after hold glyph.
     */
    public static CharSequence keyboardHint(Context ctx) {
        int color = hintTint();
        int size = Math.max(hintSizePx(ctx), 1);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        // Row 1 — edit
        appendGlyphLabel(sb, ctx, Button.PREV, color, size, "delete");
        appendLegendSep(sb);
        appendGlyphLabel(sb, ctx, Button.PLAY_PAUSE, color, size, "OK");
        appendLegendSep(sb);
        appendGlyphLabel(sb, ctx, Button.NEXT, color, size, "space");
        sb.append('\n');
        // Row 2 — hold Play/Pause/Stop flips case / symbol blocks (pick/type removed 2026-07-20)
        appendHoldGlyphLabel(sb, ctx, Button.PLAY_PAUSE, color, size, "for Capitals and Symbols");
        sb.append('\n');
        // Row 3 — leave
        appendGlyphLabel(sb, ctx, Button.BACK, color, size, "cancel");
        return sb;
    }

    /**
     * 2026-07-18 — Compact glyph height for multi-line keyboard legends (~13sp).
     * Was: 12sp — Back chevron’s thin strokes vanished when line-clipped.
     * Layman: slightly larger button pictures so cancel’s Back icon stays whole.
     */
    private static int hintSizePx(Context ctx) {
        if (ctx == null) return 16;
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f,
                ctx.getResources().getDisplayMetrics()));
    }

    /**
     * 2026-07-20 — Now Playing Options tip — hold left of Back glyph.
     * Was: [Back] hold Options. Reversal: appendGlyph then “hold Options”.
     */
    public static CharSequence holdBackOptionsHint(Context ctx, boolean y2PowerAlso) {
        return holdBackOptionsHint(ctx, y2PowerAlso, tipSizePx(ctx));
    }

    /** 2026-07-20 — Same Options tip with font-locked glyph height. */
    public static CharSequence holdBackOptionsHint(Context ctx, boolean y2PowerAlso, int sizePx) {
        int color = hintTint();
        int size = resolveTipSize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (y2PowerAlso) {
            appendHoldGlyphLabel(sb, ctx, Button.BACK, color, size, "/ ⏻ Options");
        } else {
            appendHoldGlyphLabel(sb, ctx, Button.BACK, color, size, "Options");
        }
        return sb;
    }

    /**
     * 2026-07-20 — First-play NP tip: Press [Back] for more Options (not hold).
     * Layman: picture of Back + “for more Options”.
     * Tech: additive; holdBackOptionsHint remains for live keep-holding.
     * Reversal: callers use holdBackOptionsHint again.
     */
    public static CharSequence pressBackOptionsHint(Context ctx, boolean y2PowerAlso) {
        return pressBackOptionsHint(ctx, y2PowerAlso, tipSizePx(ctx));
    }

    /** 2026-07-20 — Same Press-Back Options tip with font-locked height. */
    public static CharSequence pressBackOptionsHint(Context ctx, boolean y2PowerAlso, int sizePx) {
        int color = hintTint();
        int size = resolveTipSize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Press");
        sb.append('\u202F');
        if (y2PowerAlso) {
            appendGlyph(sb, ctx, Button.BACK, color, size);
            sb.append("\u202F/ ⏻ for Options");
        } else {
            appendGlyphLabel(sb, ctx, Button.BACK, color, size, "for Options");
        }
        return sb;
    }

    /**
     * 2026-07-20 — First-play volume tip: ↻ Volume Up · ↺ Volume Down.
     * Layman: spinning arrows remind which way is louder/quieter on the wheel path.
     * Tech: plain unicode + labels (no button PNGs); sizePx unused but kept for API parity.
     * Reversal: delete; no VOLUME_ARROWS tip mode.
     */
    public static CharSequence volumeUpDownHint(Context ctx) {
        return volumeUpDownHint(ctx, tipSizePx(ctx));
    }

    /** 2026-07-20 — Same volume arrow tip (size ignored — text-only). */
    public static CharSequence volumeUpDownHint(Context ctx, int sizePx) {
        return "↺ Volume Down ↻ Volume Up";
    }

    /**
     * 2026-07-20 — Queue / playlist hold-OK tutorial — hold left of OK.
     * Was: em-dash prose after glyph. Reversal: appendGlyphLabel(…, "hold pick up…").
     */
    public static CharSequence queueHoldTutorial(Context ctx) {
        return queueHoldTutorial(ctx, bodySizePx(ctx));
    }

    /** 2026-07-20 — Same as {@link #queueHoldTutorial(Context)} with font-locked height. */
    public static CharSequence queueHoldTutorial(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendHoldGlyphLabel(sb, ctx, Button.OK, color, size, QUEUE_HOLD_ACTION);
        return sb;
    }

    /**
     * 2026-07-18 — Place track — legend: [OK] place.
     * Was: "Press [OK] again to place."
     */
    public static CharSequence pressOkPlace(Context ctx) {
        return pressOkPlace(ctx, bodySizePx(ctx));
    }

    /** 2026-07-20 — Same as {@link #pressOkPlace(Context)} with font-locked height. */
    public static CharSequence pressOkPlace(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendGlyphLabel(sb, ctx, Button.OK, color, size, OK_PLACE_LABEL);
        return sb;
    }

    /** 2026-07-20 — Queue hold-OK action prose (no “OK” word; glyph supplies the button). */
    static final String QUEUE_HOLD_ACTION = "pick up the track, then scroll to move it.";
    /** 2026-07-20 — Place verb after OK glyph. */
    static final String OK_PLACE_LABEL = "place";
    /** 2026-07-20 — Playlist pick-up action after hold OK glyph. */
    static final String PLAYLIST_HOLD_ACTION = "pick up a track";
    /** 2026-07-20 — Playlist wheel line (no em-dash). */
    static final String PLAYLIST_WHEEL_LABEL = "move, neighbours show where it lands";

    /**
     * 2026-07-20 — Queue onboarding detail: hold [OK] pick up… then [OK] place.
     * Layman: pictures of the OK button, not the word OK; height matches the tip letters.
     * Was: plain queue_tutorial_line_* strings in the context-menu tier.
     * Reversal: restore string concat in rebuildContextQueueTutorialTier.
     */
    public static CharSequence queueTutorialBody(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendHoldGlyphLabel(sb, ctx, Button.OK, color, size, QUEUE_HOLD_ACTION);
        sb.append('\n');
        appendGlyphLabel(sb, ctx, Button.OK, color, size, OK_PLACE_LABEL);
        return sb;
    }

    /**
     * 2026-07-20 — Playlist move tutorial — hold left of OK on pick-up row.
     * Was: em-dash in pick-up / wheel lines. Reversal: old prose with \u2014.
     */
    public static CharSequence playlistMoveTutorialBody(Context ctx) {
        return playlistMoveTutorialBody(ctx, bodySizePx(ctx));
    }

    /** 2026-07-20 — Same tutorial with font-locked glyph height (pass TextView paint size). */
    public static CharSequence playlistMoveTutorialBody(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendHoldGlyphLabel(sb, ctx, Button.OK, color, size, PLAYLIST_HOLD_ACTION);
        sb.append("\n\n");
        appendGlyphLabel(sb, ctx, Button.WHEEL, color, size, PLAYLIST_WHEEL_LABEL);
        sb.append("\n\n");
        appendGlyphLabel(sb, ctx, Button.OK, color, size, OK_PLACE_LABEL);
        sb.append("\n\n");
        appendGlyphLabel(sb, ctx, Button.BACK, color, size, "cancel");
        return sb;
    }

    /** 2026-07-18 — Home editor move-mode header: wheel glyph + "move". */
    public static CharSequence wheelToMove(Context ctx) {
        int color = itemTint();
        int size = bodySizePx(ctx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendGlyphLabel(sb, ctx, Button.WHEEL, color, size, "move");
        return sb;
    }

    /** 2026-07-18 — Home editor (touch): OK glyph + "confirm". */
    public static CharSequence okToConfirm(Context ctx) {
        int color = itemTint();
        int size = bodySizePx(ctx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendGlyphLabel(sb, ctx, Button.OK, color, size, "confirm");
        return sb;
    }

    /** 2026-07-18 — Brightness overlay hint. */
    public static CharSequence wheelBrightness(Context ctx) {
        int color = hintTint();
        int size = defaultSizePx(ctx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendGlyphLabel(sb, ctx, Button.WHEEL, color, size, "brightness");
        return sb;
    }

    /** 2026-07-18 — FM tune row / NP status: wheel adjusts MHz. */
    public static CharSequence wheelAdjustsMhz(Context ctx) {
        int color = hintTint();
        int size = defaultSizePx(ctx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendGlyphLabel(sb, ctx, Button.WHEEL, color, size, "MHz");
        return sb;
    }

    /**
     * 2026-07-18 — FM tuning legend: [OK] save (status prefix stays text).
     * Was: "Tuning — [OK] save". Now: "Tuning: " + glyph (no em-dash).
     */
    public static CharSequence tuningPressOkSave(Context ctx) {
        return tuningPressOkSave(ctx, defaultSizePx(ctx));
    }

    /** 2026-07-20 — Same as {@link #tuningPressOkSave(Context)} with font-locked height. */
    public static CharSequence tuningPressOkSave(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveDefaultSize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Tuning: ");
        appendGlyphLabel(sb, ctx, Button.OK, color, size, "save");
        return sb;
    }

    /**
     * 2026-07-18 — Favourites empty — legend cell mid sentence after setup copy.
     * Was: "Hold [Back] for the menu…". Now: … [Back] menu …
     */
    public static CharSequence favoritesEmptyHint(Context ctx) {
        return favoritesEmptyHint(ctx, defaultSizePx(ctx));
    }

    /** 2026-07-20 — Same as {@link #favoritesEmptyHint(Context)} with font-locked height. */
    public static CharSequence favoritesEmptyHint(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveDefaultSize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("No favorites yet. ");
        appendGlyphLabel(sb, ctx, Button.BACK, color, size, "menu, then Add to favorites.");
        return sb;
    }

    /**
     * 2026-07-20 — NP Flow tip — hold left of Play/Pause glyph.
     * Was: [PP] + “hold — open Flow”. Reversal: appendGlyph then full string.
     */
    public static CharSequence holdPlayPauseOpenFlow(Context ctx) {
        return holdPlayPauseOpenFlow(ctx, tipSizePx(ctx));
    }

    /** 2026-07-20 — Same Flow tip with font-locked glyph height. */
    public static CharSequence holdPlayPauseOpenFlow(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveTipSize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String action = ctx != null
                ? ctx.getString(com.solar.launcher.R.string.flow_hold_play_pause_hint)
                : "open Flow";
        appendHoldGlyphLabel(sb, ctx, Button.PLAY_PAUSE, color, size, action);
        return sb;
    }

    /**
     * 2026-07-20 — Settings → Flow preview: hold left of PP glyph.
     * 2026-07-20 — Tint matches settings preview ink (was hintTint — washed out vs title).
     * Reversal: appendGlyph then settings_flow_hold_play_pause_hint; hintTint().
     */
    public static CharSequence settingsHoldPlayPauseOpenFlow(Context ctx) {
        return settingsHoldPlayPauseOpenFlow(ctx, bodySizePx(ctx));
    }

    /** 2026-07-20 — Same settings Flow tip with font-locked height. */
    public static CharSequence settingsHoldPlayPauseOpenFlow(Context ctx, int sizePx) {
        int color = itemTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String action = ctx != null
                ? ctx.getString(com.solar.launcher.R.string.settings_flow_hold_play_pause_hint)
                : "open Flow view";
        appendHoldGlyphLabel(sb, ctx, Button.PLAY_PAUSE, color, size, action);
        return sb;
    }

    /**
     * 2026-07-20 — Live NP Options tip — hold left of Back (+ Power on Y2).
     * Was: [Back] [/ Power] hold — Options. Reversal: glyph then string.
     */
    public static CharSequence keepHoldingForOptions(Context ctx, boolean y2PowerAlso) {
        return keepHoldingForOptions(ctx, y2PowerAlso, tipSizePx(ctx));
    }

    /** 2026-07-20 — Same Options tip with font-locked height. */
    public static CharSequence keepHoldingForOptions(Context ctx, boolean y2PowerAlso, int sizePx) {
        int color = hintTint();
        int size = resolveTipSize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String action = ctx != null
                ? ctx.getString(com.solar.launcher.R.string.np_keep_holding_for_options)
                : "Options";
        if (y2PowerAlso) {
            // hold [Back] / Power Options
            outHoldBackSlashPower(sb, ctx, color, size, action);
        } else {
            appendHoldGlyphLabel(sb, ctx, Button.BACK, color, size, action);
        }
        return sb;
    }

    /** 2026-07-20 — Y2: hold [Back] / Power + stripped action. */
    private static void outHoldBackSlashPower(SpannableStringBuilder sb, Context ctx,
            int color, int size, String action) {
        sb.append(ctx != null ? ctx.getString(com.solar.launcher.R.string.hint_hold_word) : "hold");
        sb.append('\u202F');
        appendGlyph(sb, ctx, Button.BACK, color, size);
        sb.append("\u202F/ ⏻\u202F");
        CharSequence rest = stripLeadingHoldWord(action);
        if (rest != null && rest.length() > 0) {
            sb.append(rest);
        }
    }

    /**
     * 2026-07-20 — Live NP Flow tip — hold left of PP.
     * Reversal: appendGlyph then np_keep_holding_for_flow.
     */
    public static CharSequence keepHoldingForFlow(Context ctx) {
        return keepHoldingForFlow(ctx, tipSizePx(ctx));
    }

    /** 2026-07-20 — Same Flow hold tip with font-locked height. */
    public static CharSequence keepHoldingForFlow(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveTipSize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String action = ctx != null
                ? ctx.getString(com.solar.launcher.R.string.np_keep_holding_for_flow)
                : "Flow";
        appendHoldGlyphLabel(sb, ctx, Button.PLAY_PAUSE, color, size, action);
        return sb;
    }

    /**
     * 2026-07-18 — Glyph height matched to transport tip text (~17sp).
     * Layman: button pictures match the tip letters so nothing sticks out and gets cropped.
     */
    private static int tipSizePx(Context ctx) {
        if (ctx == null) return 18;
        try {
            return Math.max(1, Math.round(ctx.getResources()
                    .getDimension(com.solar.launcher.R.dimen.y1_transport_hint_text_size)));
        } catch (Exception e) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f,
                    ctx.getResources().getDisplayMetrics()));
        }
    }

    /**
     * 2026-07-20 / 2026-07-21 — Stem pick status: Stem · n marked · [Play] to jam.
     * Was: n/2 hard cap. Reversal: clamp n to 2 and append "/2 · ".
     */
    public static CharSequence stemPickStatus(Context ctx, int markedCount) {
        return stemPickStatus(ctx, markedCount, defaultSizePx(ctx));
    }

    /** Unlimited mark count in status (live pads still 2). 2026-07-21 */
    public static CharSequence stemPickStatus(Context ctx, int markedCount, int sizePx) {
        int color = statusTint();
        int size = resolveDefaultSize(ctx, sizePx);
        int n = markedCount < 0 ? 0 : markedCount;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Stem · ").append(String.valueOf(n)).append(" marked · ");
        appendGlyphLabel(sb, ctx, Button.PLAY_PAUSE, color, size, "to jam");
        return sb;
    }

    /**
     * Plain stub for Stem pick tip when glyphs are not yet painted.
     * Layman: full sentence so the panel is never just two words.
     * Was: mentioned Prev/Next assign. Reversal: that Prev/Next clause.
     * 2026-07-21
     */
    public static final String STEM_PICK_TIP_STUB =
            "Mark songs for a Stem mashup.\n"
                    + "Center toggles a mark; Play starts the jam.";

    /**
     * 2026-07-21 — Stem pick onboarding: Center toggle marks, Play starts (unlimited).
     * Was: Prev=Track1 / Next=Track2; then “Prev/Next also add”. Reversal: that assign lesson.
     */
    public static CharSequence stemPickOnboarding(Context ctx) {
        return stemPickOnboarding(ctx, defaultSizePx(ctx));
    }

    /** Glyph onboarding paragraph for Stem mashup pick. 2026-07-20 / 2026-07-21 */
    public static CharSequence stemPickOnboarding(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Mark songs from your library for a Stem mashup.\n\n");
        sb.append("Focus a track, then press Center to add or remove it from the queue.\n\n");
        appendGlyphLabel(sb, ctx, Button.PLAY_PAUSE, color, size, "starts the jam");
        sb.append(" with whatever you marked (two play live; the rest wait as Next-up).\n\n");
        sb.append("Wheel scrolls the list. On a folder row, Center drills in as usual.");
        return sb;
    }

    /**
     * 2026-07-20 — Mix assign status: Prev/Next/Play-Pause glyphs + hold Play start.
     * Was: mix_assign_status with PREV/NEXT/PLAY words. Reversal: getString(R.string.mix_assign_status).
     */
    public static CharSequence mixAssignStatus(Context ctx) {
        return mixAssignStatus(ctx, defaultSizePx(ctx));
    }

    /** 2026-07-20 — Same mix assign status with font-locked height. */
    public static CharSequence mixAssignStatus(Context ctx, int sizePx) {
        int color = statusTint();
        int size = resolveDefaultSize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Mix assign · ");
        appendGlyph(sb, ctx, Button.PREV, color, size);
        sb.append('/');
        appendGlyph(sb, ctx, Button.NEXT, color, size);
        sb.append('/');
        appendGlyph(sb, ctx, Button.PLAY_PAUSE, color, size);
        sb.append(" bind · ");
        appendHoldGlyphLabel(sb, ctx, Button.PLAY_PAUSE, color, size, "start");
        return sb;
    }

    /**
     * 2026-07-20 — Mid-mix reassign: pick track · Prev/Next/Play-Pause assign.
     * Was: mix_reassign_pick with PREV/NEXT/PLAY words. Reversal: getString(R.string.mix_reassign_pick, n).
     */
    public static CharSequence mixReassignPick(Context ctx, int deckOneBased) {
        return mixReassignPick(ctx, deckOneBased, defaultSizePx(ctx));
    }

    /** 2026-07-20 — Same reassign pick status with font-locked height. */
    public static CharSequence mixReassignPick(Context ctx, int deckOneBased, int sizePx) {
        int color = statusTint();
        int size = resolveDefaultSize(ctx, sizePx);
        int deck = deckOneBased < 1 ? 1 : deckOneBased;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Pick a track for Mix deck ").append(String.valueOf(deck)).append(" · ");
        appendGlyph(sb, ctx, Button.PREV, color, size);
        sb.append('/');
        appendGlyph(sb, ctx, Button.NEXT, color, size);
        sb.append('/');
        appendGlyph(sb, ctx, Button.PLAY_PAUSE, color, size);
        sb.append(" assign");
        return sb;
    }

    /**
     * Plain stub for Mix fader tip (no glyphs). 2026-07-21
     */
    public static final String MIX_FADER_TIP_STUB =
            "Mix keeps three decks live.\n"
                    + "Prev, Next, or Play lights a deck; the scrollwheel moves that fader.";

    /**
     * 2026-07-20 — First-open Mix face tip: highlight decks with pad glyphs; wheel moves lit fader.
     * Was: no Mix onboarding. Reversal: drop call sites + PREF_MIX_FADER_ONBOARDING_SEEN.
     * Layman: button pictures show which pad lights which slider; scrollwheel moves the lit one.
     * 2026-07-21 — Longer lesson body.
     */
    public static CharSequence mixFaderOnboarding(Context ctx) {
        return mixFaderOnboarding(ctx, bodySizePx(ctx));
    }

    /** Prose after pad glyphs — unit-testable without Context. 2026-07-20 */
    public static final String MIX_FADER_HIGHLIGHT_ACTION = "highlight a deck";
    /** Prose after wheel glyph. 2026-07-20 */
    public static final String MIX_FADER_WHEEL_ACTION = "moves the lit fader";

    /** Mix face tip with font-locked glyph height. 2026-07-20 / 2026-07-21 */
    public static CharSequence mixFaderOnboarding(Context ctx, int sizePx) {
        int color = statusTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Mix plays up to three songs at once. Each deck has its own volume fader.\n\n");
        sb.append("Tap ");
        appendGlyph(sb, ctx, Button.PREV, color, size);
        sb.append(", ");
        appendGlyph(sb, ctx, Button.NEXT, color, size);
        sb.append(", or ");
        appendGlyph(sb, ctx, Button.PLAY_PAUSE, color, size);
        sb.append(" to ");
        sb.append(MIX_FADER_HIGHLIGHT_ACTION);
        sb.append(".\n\n");
        appendGlyphLabel(sb, ctx, Button.WHEEL, color, size, MIX_FADER_WHEEL_ACTION);
        sb.append(".\n\nHold a side button for that deck’s options. Music keeps playing under Options.");
        return sb;
    }

    /**
     * Plain stub for Mix assign tip. 2026-07-21
     */
    public static final String MIX_ASSIGN_TIP_STUB =
            "Choose up to three songs for Mix.\n"
                    + "Prev, Next, and Play bind decks; hold Play to start.";

    /**
     * 2026-07-20 — First-open Mix assign tip with pad glyphs (status bar / scrollable hint).
     * Was: mix_assign_status words only. Reversal: getString(R.string.mix_assign_status).
     * 2026-07-21 — Longer lesson body.
     */
    public static CharSequence mixAssignOnboarding(Context ctx) {
        return mixAssignOnboarding(ctx, bodySizePx(ctx));
    }

    /** Prose after bind glyphs. 2026-07-20 */
    public static final String MIX_ASSIGN_BIND_ACTION = "bind tracks";
    /** Prose after hold Play. 2026-07-20 */
    public static final String MIX_ASSIGN_START_ACTION = "start Mix";

    /** Assign onboarding with font-locked height. 2026-07-20 / 2026-07-21 */
    public static CharSequence mixAssignOnboarding(Context ctx, int sizePx) {
        int color = statusTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Choose up to three songs from your library for Mix.\n\n");
        sb.append("Focus a track, then press ");
        appendGlyph(sb, ctx, Button.PREV, color, size);
        sb.append(", ");
        appendGlyph(sb, ctx, Button.NEXT, color, size);
        sb.append(", or ");
        appendGlyph(sb, ctx, Button.PLAY_PAUSE, color, size);
        sb.append(" to ");
        sb.append(MIX_ASSIGN_BIND_ACTION);
        sb.append(" onto that deck.\n\n");
        appendHoldGlyphLabel(sb, ctx, Button.PLAY_PAUSE, color, size, MIX_ASSIGN_START_ACTION);
        sb.append(" when you are ready.\n\n");
        sb.append("Center opens Artists or folders — it does not bind a song.");
        return sb;
    }

    /**
     * Stem/Mix queue journey — hold Prev/Next swap or Scrub.
     * Was: no queue journey glyph. Reversal: plain StemMixOnboardingPrefs.pageProse only.
     * 2026-07-21
     */
    public static CharSequence stemMixHoldScrubOnboarding(Context ctx) {
        return stemMixHoldScrubOnboarding(ctx, bodySizePx(ctx));
    }

    public static CharSequence stemMixHoldScrubOnboarding(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Hold ");
        appendGlyph(sb, ctx, Button.PREV, color, size);
        sb.append(" or ");
        appendGlyph(sb, ctx, Button.NEXT, color, size);
        sb.append(" to swap that track or Scrub");
        return sb;
    }

    /**
     * Short jam-face cue beside Track 1 / Track 2 names.
     * Layman: tiny “hold [glyph] for options” — no tip wall.
     * Was: long hint paragraphs under the face. Reversal: stemMixHoldScrubOnboarding prose.
     * 2026-07-21
     */
    public static CharSequence stemMixHoldOptionsCue(Context ctx, boolean prevSide) {
        return stemMixHoldOptionsCue(ctx, prevSide, bodySizePx(ctx));
    }

    public static CharSequence stemMixHoldOptionsCue(Context ctx, boolean prevSide, int sizePx) {
        int color = hintTint();
        int size = resolveBodySize(ctx, Math.max(10, sizePx - 2));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("hold ");
        appendGlyph(sb, ctx, prevSide ? Button.PREV : Button.NEXT, color, size);
        sb.append(" for options");
        return sb;
    }

    /**
     * Dual-hold session menu tip.
     * 2026-07-21
     */
    public static CharSequence stemMixDualHoldOnboarding(Context ctx) {
        return stemMixDualHoldOnboarding(ctx, bodySizePx(ctx));
    }

    public static CharSequence stemMixDualHoldOnboarding(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Hold ");
        appendGlyph(sb, ctx, Button.PREV, color, size);
        sb.append('+');
        appendGlyph(sb, ctx, Button.NEXT, color, size);
        sb.append(" for Play queue or TRANSITION");
        return sb;
    }

    /**
     * Plain stub for single-track Stem face tip. 2026-07-21
     */
    public static final String STEM_FACE_TIP_STUB =
            "Stem Player splits one song into four pads.\n"
                    + "Tap a pad, then turn the scrollwheel to change that pad’s volume.";

    /**
     * Stem face tip — wheel = pad volume; mute is silence not stop.
     * Was: jam-face “Center = loop” wall. Reversal: that hint string.
     * 2026-07-21 — Longer lesson body.
     */
    public static CharSequence stemPadVolumeOnboarding(Context ctx) {
        return stemPadVolumeOnboarding(ctx, bodySizePx(ctx));
    }

    /** Same Stem pad tip with font-locked glyph height. 2026-07-21 */
    public static CharSequence stemPadVolumeOnboarding(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Stem Player splits one song into four pads (vocals, drums, bass, other).\n\n");
        sb.append("Tap a pad to focus it, then use ");
        appendGlyphLabel(sb, ctx, Button.WHEEL, color, size, "to raise or lower that pad’s volume");
        sb.append(".\n\n");
        sb.append("Mute is silence only — the song keeps playing in place. "
                + "Raise the dial again and the part comes back where it left off.");
        return sb;
    }

    /**
     * Plain stub for Stem/Mix journey tip. 2026-07-21
     */
    public static final String STEM_MIX_JOURNEY_TIP_STUB =
            "Stem and Mix share your play queue.\n"
                    + "With two tracks, each song repeats when it ends; add more to advance.\n"
                    + "System Volume is the ceiling for Stem and Mix.\n"
                    + "Hold Prev or Next for that track’s options; hold both for jam options.";

    /**
     * Stem/Mix journey tip body (queue + hold Prev/Next + volume envelope + pair-repeat).
     * Was: multi-page status wall. Reversal: StemMixOnboardingPrefs.pageProse only.
     * 2026-07-21 — Longer lesson body + volume ceiling + pair-repeat.
     */
    public static CharSequence stemMixJourneyOnboarding(Context ctx) {
        return stemMixJourneyOnboarding(ctx, bodySizePx(ctx));
    }

    /** Journey tip with font-locked glyphs. 2026-07-21 */
    public static CharSequence stemMixJourneyOnboarding(Context ctx, int sizePx) {
        int color = hintTint();
        int size = resolveBodySize(ctx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Your playlist is the play queue — Stem and Mix share it with Now Playing.\n\n");
        sb.append("With only two tracks in the jam, each song soft-repeats when it ends so unequal "
                + "lengths keep mixing. Add more songs to the queue to advance into Next-up.\n\n");
        sb.append("Hold ");
        appendGlyph(sb, ctx, Button.PREV, color, size);
        sb.append(" or ");
        appendGlyph(sb, ctx, Button.NEXT, color, size);
        sb.append(" to open options for that live track (replace, queue, scrub).\n\n");
        sb.append("Hold both side buttons together for jam options: play queue, blend length, "
                + "and more. Use the Home chip in Options to leave the jam.\n\n");
        sb.append("The Volume chip in Options is the session ceiling — Stem and Mix pads cannot "
                + "go louder than system volume.\n\n");
        sb.append("Music keeps playing while Options is open — wheel and Center drive the menu.");
        return sb;
    }

    /**
     * 2026-07-18 — ARGB → #RRGGBB for tests / logging (documents ink→theme contract).
     */
    static String colorHex(int argb) {
        return String.format("#%06X", (argb & 0xFFFFFF));
    }
}
