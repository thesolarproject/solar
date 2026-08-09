package com.solar.launcher.stem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * StemFM-style mashup face — four pads + four corner dial complications.
 * Layman: Vocals/Drums/Bass/Melody bubbles in the middle; watch dials peek from corners.
 * Technical: Canvas diamond pads; Hallmark dials TA TL / TB TR / UpNext BR / Prep BL @ 33 RPM.
 * Was: arch title strips around pads. Reversal: drawArchSongLabel + StemPadArchLabel.
 * 2026-07-20 / 2026-07-21
 */
public class StemMashupFaceView extends View {
    /** Vocals / Bass / Melody / Drums — StemFM compass (N=Vocals, W=Bass, E=Melody, S=Drums). 2026-08-02 */
    public static final String[] ZONE_LABELS = { "VOCALS", "BASS", "MELODY", "DRUMS" };

    /** Placeholder / tint colours per song slot. 2026-07-20 */
    private static final int[] SONG_COLORS = {
            0xFFE85D4C, // Song 1 warm coral
            0xFF4C8FE8, // Song 2 cool blue
    };
    private static final int FIELD = 0xFF0A0A0C;
    private static final int TITLE = 0xFFE8E8EE;
    private static final int LABEL = 0xCCFFFFFF;
    private static final int RING_DIM = 0x44FFFFFF;
    private static final int SHUFFLE_DISC = 0x55FFFFFF;
    private static final int SHUFFLE_ICON = 0xFFFFFFFF;
    /** Whimsical Up Next alert flash (prep-aware reorder) — calm red, not purple glow. 2026-07-21 */
    private static final int UP_NEXT_ALERT = 0xFFE24B4B;
    /** Calm breathe period (~2.4s). Low amplitude for MT6572. 2026-07-21 */
    private static final float BREATHE_MS = 2400f;
    private static final float BREATHE_SCALE = 0.03f;
    private static final float BREATHE_Y = 1.8f;

    private final Paint fieldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shufflePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shufflePath = new Path();
    private final RectF tmpOval = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    /** Marquee offsets for TA / TB / UpNext / Prep dials (px along rim). 2026-07-21 */
    private final float[] dialMarqueePx = new float[] { 0f, 0f, 0f, 0f };
    private long dialMarqueeLastMs;

    private final String[] songTitles = new String[] { "", "" };
    private final String[] songArtists = new String[] { "", "" };
    private final char[] songLetters = new char[] { 'A', 'B' };
    private final Bitmap[] songArts = new Bitmap[2];
    /** Up Next dial title + optional art. 2026-07-21 */
    private String upNextTitle = "";
    private Bitmap upNextArt;
    /** Prep chronograph label (real status only). 2026-07-21 */
    private String prepLabel = "Ready";
    private float prepFraction;
    private boolean prepBusy;
    /** Whimsical red flash until uptime ms. 2026-07-21 */
    private long upNextAlertUntilMs;
    /** True when both songs share album art (or same album tag) — letter+tint over cover. 2026-07-20 */
    private boolean sameAlbumArt;
    private final int[] zoneSong = new int[] { 0, 0, 0, 0 };
    private final float[] zoneGains = new float[] { 0f, 0f, 0f, 0f };
    private int activeZone = -1;
    private int focusHaloColor = 0xFFFFCC00;
    private boolean loading;
    private boolean shufflePulse;
    /**
     * Centre disc: false = Play glyph (pre-start); true = shuffle (playing).
     * Layman: OK looks like Play until the jam starts, then remix arrows.
     * Was: always shuffle. Reversal: transportPlaying always true in draw.
     * 2026-07-21
     */
    private boolean transportPlaying;
    /**
     * Hold-OK circular scrub — shrink focus ring + seek ball on the focused pad.
     * Audio path unchanged here (host commits seek). Was: focus halo only.
     * Reversal: drop padScrubbing branch in drawPad.
     * 2026-07-21
     */
    private boolean padScrubbing;
    private float padScrubFrac;
    private int padScrubMs;
    private float padScrubTransitionFrac = -1f;
    private final Paint scrubCursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] scrubXy = new float[2];
    /**
     * Idle defocus — pads shrink; no focus halo until a key wakes them.
     * Layman: after quiet time bubbles go small so accidents don’t flip tracks.
     * Audio path unchanged (visual only). Was: always full size. Reversal: padsIdle=false always.
     * 2026-07-21
     */
    private boolean padsIdle;
    /**
     * Meatball fold — idle folds the four pads into two overlapping song discs
     * (StemFM "two discs" view) so both mixed tracks read at a glance.
     * 0 = four-pad spread · 1 = two-disc meatball. Animated, not a snap.
     * Was: idle just shrank pads. Reversal: meatballBlend always 0 (no fold).
     * 2026-08-01
     */
    private float meatballBlend;
    private float meatballFromBlend;
    private float meatballToBlend;
    private long meatballAnimStartMs = -1L;
    private static final long MEATBALL_ANIM_MS = 420L;
    /**
     * Dominant (tempo/key lead) song — its disc is larger, brighter and drawn on
     * top; the subordinate disc dims. Animates smoothly on dominance change.
     * 0 = song 0 lead · 1 = song 1 lead. Was: no emphasis (equal discs). 2026-08-02
     */
    private int dominantSong = 0;
    private int dominantFromSong = 0;
    private long dominantAnimStartMs = -1L;
    private static final long DOMINANT_ANIM_MS = 380L;
    /** Subordinate disc emphasis multiplier (dims, keeps legible). 2026-08-02 */
    private static final float SUBORDINATE_EMPHASIS = 0.72f;
    /**
     * Play-both stacking — pad feeds its stem from BOTH songs; show A+B badge.
     * Layman: a stacked pad shows both tracks’ letters so you know vocals are doubled.
     * Audio path unchanged here (host owns gains). Was: no badge. Reversal: ignore flags.
     * 2026-08-01
     */
    private final boolean[] bothZones = new boolean[4];

    public StemMashupFaceView(Context context) {
        super(context);
        init();
    }

    public StemMashupFaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        fieldPaint.setColor(FIELD);
        fieldPaint.setStyle(Paint.Style.FILL);
        bubblePaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeCap(Paint.Cap.ROUND);
        letterPaint.setStyle(Paint.Style.FILL);
        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setFakeBoldText(true);
        letterPaint.setColor(0xFFFFFFFF);
        titlePaint.setStyle(Paint.Style.FILL);
        titlePaint.setColor(resolveTitleColor());
        titlePaint.setAntiAlias(true);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(LABEL);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
        tintPaint.setStyle(Paint.Style.FILL);
        shufflePaint.setAntiAlias(true);
        dialPaint.setAntiAlias(true);
        // Seek ball fill — warm amber so it reads on coral/blue pads. 2026-07-21
        scrubCursorPaint.setStyle(Paint.Style.FILL);
        scrubCursorPaint.setColor(0xFFFFCC66);
    }

    /**
     * Arm / update Hold-OK circular scrub paint on the focused pad.
     * Layman: shrink the glow and show a little ball you wheel around the bubble.
     * Audio: host owns seek; this is face-only. Reversal: ignore calls.
     * 2026-07-21
     */
    public void setPadScrub(boolean armed, float frac, int scrubMs, float transitionFrac) {
        padScrubbing = armed;
        padScrubFrac = StemMixSoftScrub.clampFrac(frac);
        padScrubMs = scrubMs;
        padScrubTransitionFrac = transitionFrac >= 0f ? StemMixSoftScrub.clampFrac(transitionFrac) : -1f;
        invalidate();
    }

    /** Clear circular scrub chrome. 2026-07-21 */
    public void clearPadScrub() {
        padScrubbing = false;
        padScrubFrac = 0f;
        padScrubMs = 0;
        padScrubTransitionFrac = -1f;
        invalidate();
    }

    /**
     * Centre transport chrome — Play before start, shuffle while playing.
     * Layman: the middle button shows Play until you start the jam.
     * Audio path unchanged. Was: always shuffle icon. Reversal: ignore.
     * 2026-07-21
     */
    public void setCentreTransportPlaying(boolean playing) {
        transportPlaying = playing;
        invalidate();
    }

    /**
     * Push mashup visual state — per-pad song routing + gains + titles.
     * Layman: refresh which picture sits on Vocals/Drums/Bass/Melody and how loud they are.
     * 2026-07-20
     */
    public void setState(int[] zoneToSong, float[] zoneGain, int zone,
            String title0, String artist0, String title1, String artist1,
            int haloArgb, boolean isLoading, boolean sameAlbum) {
        if (zoneToSong != null) {
            for (int i = 0; i < 4 && i < zoneToSong.length; i++) {
                int s = zoneToSong[i];
                zoneSong[i] = (s < 0) ? 0 : (s > 1 ? 1 : s);
            }
        }
        if (zoneGain != null) {
            for (int i = 0; i < 4 && i < zoneGain.length; i++) {
                zoneGains[i] = StemControls.clampGain(zoneGain[i]);
            }
        }
        activeZone = zone;
        songTitles[0] = title0 != null ? title0 : "";
        songTitles[1] = title1 != null ? title1 : "";
        songArtists[0] = artist0 != null ? artist0 : "";
        songArtists[1] = artist1 != null ? artist1 : "";
        songLetters[0] = StemControls.placeholderLetter(
                songTitles[0].length() > 0 ? songTitles[0] : "A");
        songLetters[1] = StemControls.placeholderLetter(
                songTitles[1].length() > 0 ? songTitles[1] : "B");
        focusHaloColor = haloArgb != 0 ? haloArgb : 0xFFFFCC00;
        loading = isLoading;
        sameAlbumArt = sameAlbum;
        invalidate();
    }

    /**
     * Feed Up Next + Prep dial complications (real prep copy only).
     * Layman: corner dials show what’s waiting and whether stems are cooking.
     * Was: arch titles only. Reversal: ignore; dials blank.
     * 2026-07-21
     */
    public void setComplications(String nextTitle, Bitmap nextArt,
            String prepText, float prepFrac, boolean busy) {
        upNextTitle = nextTitle != null ? nextTitle : "";
        upNextArt = nextArt;
        prepLabel = prepText != null && prepText.length() > 0 ? prepText : "Ready";
        prepFraction = prepFrac < 0f ? 0f : (prepFrac > 1f ? 1f : prepFrac);
        prepBusy = busy;
        invalidate();
    }

    /**
     * Whimsical red flash on Up Next when prep-aware reorder fires.
     * Layman: the waiting-song dial blinks red when readiness jumped the queue.
     * Audio path unchanged — visual only.
     * 2026-07-21
     */
    public void flashUpNextAlert() {
        upNextAlertUntilMs = System.currentTimeMillis() + 1400L;
        invalidate();
    }

    /**
     * Set circle-cropped art for song 0 or 1 (null = letter-only placeholder).
     * Layman: drop that track’s album picture into every pad that uses it.
     * 2026-07-20
     */
    public void setArt(int songIndex, Bitmap art) {
        if (songIndex < 0 || songIndex > 1) return;
        songArts[songIndex] = art;
        invalidate();
    }

    /** Flash the centre shuffle disc after OK. 2026-07-20 */
    public void pulseShuffle() {
        shufflePulse = true;
        // Shuffle fans the two-disc meatball back out to the four stems. 2026-08-01
        animateMeatballTo(false);
        invalidate();
        postDelayed(new Runnable() {
            @Override
            public void run() {
                shufflePulse = false;
                invalidate();
            }
        }, 220);
    }

    public void setActiveZone(int zone) {
        activeZone = zone;
        // Focusing a pad wakes idle shrink + fans the meatball back to four pads. 2026-07-21
        if (zone >= 0 && padsIdle) {
            padsIdle = false;
            animateMeatballTo(false);
        }
        invalidate();
    }

    /**
     * Idle shrink on/off — host clears focus after 2s quiet.
     * Layman: tiny pads = asleep; normal size when you poke a button again.
     * First pad key after idle only re-focuses (no replace) — host clears activeZone.
     * Audio unchanged. Reversal: ignore; always full size.
     * 2026-07-21
     */
    public void setPadsIdle(boolean idle) {
        if (padsIdle == idle) return;
        padsIdle = idle;
        if (idle) {
            // No lit bubble while asleep. 2026-07-21
            activeZone = -1;
        }
        // Idle folds the pads into two song discs; waking fans them back out. 2026-08-01
        animateMeatballTo(idle);
        invalidate();
    }

    /** True when pads are in idle shrink (tests / host). 2026-07-21 */
    public boolean isPadsIdle() {
        return padsIdle;
    }

    /**
     * Animate toward the two-disc meatball (true) or the four-pad spread (false).
     * Layman: pads fold into two big song discs when asleep, fan out on focus.
     * Audio path unchanged — visual only. Reversal: no-op (snap between layouts).
     * 2026-08-01
     */
    private void animateMeatballTo(boolean meatball) {
        float from = currentMeatballBlend();
        float to = meatball ? 1f : 0f;
        if (Math.abs(from - to) < 0.001f && !meatballAnimRunning()) {
            meatballBlend = to;
            return;
        }
        meatballFromBlend = from;
        meatballToBlend = to;
        meatballBlend = from;
        meatballAnimStartMs = System.currentTimeMillis();
        invalidate();
        postInvalidateDelayed(33);
    }

    /** True while the fold/fan animation is in flight. 2026-08-01 */
    private boolean meatballAnimRunning() {
        return meatballAnimStartMs >= 0L;
    }

    /** Eased current fold blend 0..1 (0 = spread, 1 = two discs). 2026-08-01 */
    private float currentMeatballBlend() {
        if (meatballAnimStartMs < 0L) return meatballBlend;
        float t = (System.currentTimeMillis() - meatballAnimStartMs) / (float) MEATBALL_ANIM_MS;
        if (t >= 1f) {
            meatballAnimStartMs = -1L;
            meatballBlend = meatballToBlend;
            return meatballBlend;
        }
        if (t < 0f) t = 0f;
        t = StemControls.meatballEase(t);
        meatballBlend = meatballFromBlend + (meatballToBlend - meatballFromBlend) * t;
        return meatballBlend;
    }

    /**
     * Push play-both stacking flags for all pads (host refresh).
     * Layman: which bubbles are doubled up on both tracks.
     * Audio path unchanged — visual only. 2026-08-01
     */
    public void setBothZones(boolean[] both) {
        if (both == null) return;
        boolean changed = false;
        for (int i = 0; i < bothZones.length && i < both.length; i++) {
            if (bothZones[i] != both[i]) {
                bothZones[i] = both[i];
                changed = true;
            }
        }
        if (changed) invalidate();
    }

    /** True when a pad is stacked on both songs (tests / host). 2026-08-01 */
    public boolean isZoneBoth(int zone) {
        if (zone < 0 || zone >= bothZones.length) return false;
        return bothZones[zone];
    }

    /**
     * Set which song leads the mix (pad-majority; vocals breaks 2-2 ties).
     * The lead song's disc grows with a brighter ring and sits on top; the
     * other disc dims — animated so the swap reads as one coherent motion.
     * Audio path unchanged (host owns tempo/key). 2026-08-02
     */
    public void setDominantSong(int song) {
        int s = (song < 0) ? 0 : (song > 1 ? 1 : song);
        if (s == dominantSong && !dominantAnimRunning()) return;
        dominantFromSong = dominantSong;
        dominantSong = s;
        dominantAnimStartMs = System.currentTimeMillis();
        invalidate();
        postInvalidateDelayed(33);
    }

    /** True while the dominance emphasis animation is in flight. 2026-08-02 */
    private boolean dominantAnimRunning() {
        return dominantAnimStartMs >= 0L;
    }

    /** Eased 0..1 transition progress for the current dominance change. 2026-08-02 */
    private float currentDominantT() {
        if (dominantAnimStartMs < 0L) return 1f;
        float t = (System.currentTimeMillis() - dominantAnimStartMs) / (float) DOMINANT_ANIM_MS;
        if (t >= 1f) {
            dominantAnimStartMs = -1L;
            return 1f;
        }
        if (t < 0f) t = 0f;
        return StemControls.meatballEase(t);
    }

    /** Per-song disc emphasis — lead 1.0, subordinate 0.72, crossfaded on change. 2026-08-02 */
    private float discEmphasis(int song) {
        float t = currentDominantT();
        if (song == dominantSong) return SUBORDINATE_EMPHASIS + (1f - SUBORDINATE_EMPHASIS) * t;
        if (song == dominantFromSong) return 1f - (1f - SUBORDINATE_EMPHASIS) * t;
        return SUBORDINATE_EMPHASIS;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawRect(0, 0, w, h, fieldPaint);

        float cx = w * 0.5f;
        float cy = h * 0.52f;
        // Louder pads push apart — mean volume grows reach. 2026-07-21
        float meanVol = 0f;
        for (int i = 0; i < 4; i++) meanVol += zoneGains[i];
        meanVol *= 0.25f;
        float minSide = Math.min(w, h);
        float reach = minSide * (0.24f + 0.09f * meanVol);
        // Idle: pads tuck in smaller so the face reads “hands off”. 2026-07-21
        float idleScale = StemControls.padIdleDrawScale(padsIdle);
        float baseR = minSide * 0.145f * idleScale;
        if (padsIdle) {
            reach *= idleScale;
        }
        // Calm breathe — scale + tiny Y drift (transform only). 2026-07-21
        float phase = (float) (System.currentTimeMillis() % (long) BREATHE_MS) / BREATHE_MS;
        float breathe = (float) Math.sin(phase * Math.PI * 2.0);
        float breatheScale = 1f + BREATHE_SCALE * breathe;
        float breatheY = BREATHE_Y * breathe;

        // Four protruding corner dials — retire arch strips that collided. 2026-07-21
        // Was: drawArchSongLabel top/bottom. Reversal: that arch block.
        tickDialMarquee();
        drawCornerDial(canvas, StemComplicationGeometry.Corner.TL, 0,
                songTitles[0].length() > 0 ? songTitles[0] : "Track A",
                songArts[0], songLetters[0], SONG_COLORS[0], false, 0f);
        // Empty B when single-track — no fake second song name. 2026-07-21
        String tb = songTitles[1].length() > 0 ? songTitles[1] : "—";
        drawCornerDial(canvas, StemComplicationGeometry.Corner.TR, 1,
                tb,
                songArts[1],
                songTitles[1].length() > 0 ? songLetters[1] : '·',
                SONG_COLORS[1], false, 0f);
        boolean alert = System.currentTimeMillis() < upNextAlertUntilMs;
        drawCornerDial(canvas, StemComplicationGeometry.Corner.BR, 2,
                upNextTitle.length() > 0 ? upNextTitle : "Up Next",
                upNextArt,
                StemControls.placeholderLetter(upNextTitle.length() > 0 ? upNextTitle : "N"),
                alert ? UP_NEXT_ALERT : resolveAccentColor(),
                alert, 0f);
        drawCornerDial(canvas, StemComplicationGeometry.Corner.BL, 3,
                prepLabel,
                null, 'P', resolveSecondaryColor(), false, prepFraction);

        // N=Vocals, W=Bass, E=Melody, S=Drums — StemFM compass alignment. 2026-08-02
        // Was: W=Drums, E=Bass, S=Melody (wrong order for StemFM parity).
        float[][] pos = new float[][] {
                { cx, cy - reach + breatheY },
                { cx - reach, cy + breatheY },
                { cx + reach, cy + breatheY },
                { cx, cy + reach + breatheY },
        };
        // Meatball fold — pads drift toward their song's disc; discs fade up behind. 2026-08-01
        float meatball = currentMeatballBlend();
        float discR = minSide * 0.205f * meatball * breatheScale;
        float d = minSide * 0.10f;
        float[][] discPos = new float[][] {
                { cx - d, cy - d },
                { cx + d, cy + d },
        };
        for (int z = 0; z < 4; z++) {
            // Per-pad: louder = larger + further from centre. 2026-07-21
            float vol = zoneGains[z];
            float outward = 1f + 0.12f * vol;
            float px = cx + (pos[z][0] - cx) * outward;
            float py = cy + (pos[z][1] - cy) * outward;
            // Fold toward the song's disc center (top-left song 0 / bottom-right song 1).
            int song = zoneSong[z];
            px += (discPos[song][0] - px) * meatball;
            py += (discPos[song][1] - py) * meatball;
            drawPad(canvas, z, px, py, baseR * breatheScale * (1f - 0.72f * meatball));
        }

        // Two overlapping song discs — the StemFM meatball view, drawn over the folded pads.
        // Dominant song's disc draws last (on top), larger + brighter; subordinate dims. 2026-08-02
        int drawFirst = dominantSong == 0 ? 1 : 0;
        int drawSecond = dominantSong;
        for (int pass = 0; pass < 2; pass++) {
            int s = pass == 0 ? drawFirst : drawSecond;
            if (s == 1 && songTitles[1].length() == 0) continue;
            drawMeatballDisc(canvas, s, discPos[s][0], discPos[s][1] + breatheY * 0.5f,
                    discR, discEmphasis(s));
        }

        // Centre shuffle disc grows a touch at the disc intersection while folded. 2026-08-01
        drawShuffleCentre(canvas, cx, cy + breatheY * 0.5f,
                baseR * 0.42f * breatheScale * (1f + 0.3f * meatball));

        // ~5fps breathe/marquee — was 50ms (~20fps) and cooked MT6572. Reversal: 50L.
        // Skip while idle (no motion needed). 2026-07-21
        if (meatballAnimRunning() || dominantAnimRunning()) {
            postInvalidateDelayed(33);
        } else if (!padsIdle || loading || padScrubbing || alert) {
            postInvalidateDelayed(200);
        }
    }

    /**
     * Theme primary for dial titles; fail-open to stock light grey.
     * Layman: match Solar theme text when available.
     * 2026-07-21
     */
    private int resolveTitleColor() {
        try {
            int c = com.solar.launcher.theme.ThemeManager.getTextColorPrimary();
            if (c != 0) return c;
        } catch (Throwable ignored) {}
        return TITLE;
    }

    /** Theme secondary for prep dial chrome. 2026-07-21 */
    private int resolveSecondaryColor() {
        try {
            int c = com.solar.launcher.theme.ThemeManager.getTextColorSecondary();
            if (c != 0) return c;
        } catch (Throwable ignored) {}
        return 0xFFB0B0B8;
    }

    /** Accent-ish ring for Up Next (theme primary when calm). 2026-07-21 */
    private int resolveAccentColor() {
        return resolveTitleColor();
    }

    /** Advance 33 RPM marquee clocks on all four dials. 2026-07-21 */
    private void tickDialMarquee() {
        long now = System.currentTimeMillis();
        float minSide = Math.min(getWidth(), getHeight());
        float r = StemComplicationGeometry.dialRadius(minSide);
        float rimR = r * 0.88f;
        float speed = StemComplicationGeometry.pathPxPerSec(rimR);
        if (dialMarqueeLastMs > 0L) {
            float dt = (now - dialMarqueeLastMs) / 1000f;
            if (dt > 0f && dt < 0.5f) {
                for (int i = 0; i < dialMarqueePx.length; i++) {
                    dialMarqueePx[i] += speed * dt;
                }
            }
        }
        dialMarqueeLastMs = now;
    }

    /**
     * One protruding corner dial — art bubble + upright 33 RPM rim marquee.
     * Layman: a watch complication peeking from that corner of the screen.
     * Was: arch title strips. Reversal: drawArchSongLabel.
     * 2026-07-21
     */
    private void drawCornerDial(Canvas canvas, StemComplicationGeometry.Corner corner,
            int marqueeIdx, String title, Bitmap art, char letter, int ringColor,
            boolean alertFlash, float chronoFrac) {
        float w = getWidth();
        float h = getHeight();
        float minSide = Math.min(w, h);
        float r = StemComplicationGeometry.dialRadius(minSide);
        float[] cxy = StemComplicationGeometry.dialCenter(corner, w, h, r);
        float cx = cxy[0];
        float cy = cxy[1];
        float artR = StemComplicationGeometry.artRadius(r);
        float rimR = r * 0.88f;

        // Dial face disc (mostly off-screen). 2026-07-21
        dialPaint.setStyle(Paint.Style.FILL);
        dialPaint.setColor(0xEE121218);
        canvas.drawCircle(cx, cy, r, dialPaint);
        dialPaint.setStyle(Paint.Style.STROKE);
        dialPaint.setStrokeWidth(Math.max(2f, r * 0.06f));
        int ring = alertFlash ? UP_NEXT_ALERT : ringColor;
        dialPaint.setColor(ring);
        dialPaint.setAlpha(alertFlash ? 255 : 200);
        canvas.drawCircle(cx, cy, rimR, dialPaint);

        // Prep chronograph fill arc (real busy fraction only). 2026-07-21
        if (chronoFrac > 0.02f) {
            dialPaint.setStrokeWidth(Math.max(3f, r * 0.1f));
            dialPaint.setAlpha(220);
            tmpOval.set(cx - rimR, cy - rimR, cx + rimR, cy + rimR);
            float sweep = StemComplicationGeometry.visibleSweepDeg() * chronoFrac;
            canvas.drawArc(tmpOval, StemComplicationGeometry.rimStartDeg(corner), sweep,
                    false, dialPaint);
        }

        // Centre art / letter — always hint initial over art so dials stay distinct. 2026-07-21
        boolean hasArt = art != null && !art.isRecycled();
        if (hasArt) {
            drawCircleArt(canvas, art, cx, cy, artR);
            tintPaint.setColor((ring & 0x00FFFFFF) | 0x55000000);
            canvas.drawCircle(cx, cy, artR, tintPaint);
            drawLetter(canvas, letter, cx, cy, artR * 0.72f);
        } else {
            bubblePaint.setShader(null);
            bubblePaint.setColor((ring & 0x00FFFFFF) | 0xCC000000);
            canvas.drawCircle(cx, cy, artR, bubblePaint);
            drawLetter(canvas, letter, cx, cy, artR);
        }

        // Upright rim marquee (top arches inverted). 2026-07-21
        titlePaint.setColor(resolveTitleColor());
        titlePaint.setTextSize(StemComplicationGeometry.titleTextSize(minSide));
        titlePaint.setAlpha(255);
        String label = title != null ? title : "";
        float start = StemComplicationGeometry.rimStartDeg(corner);
        float sign = StemComplicationGeometry.rimSweepSign(corner);
        float sweep = StemComplicationGeometry.visibleSweepDeg() * sign;
        float arcLen = StemComplicationGeometry.arcLengthPx(rimR, Math.abs(sweep));
        float mq = marqueeIdx >= 0 && marqueeIdx < dialMarqueePx.length
                ? dialMarqueePx[marqueeIdx] : 0f;
        boolean invertTop = corner == StemComplicationGeometry.Corner.TL
                || corner == StemComplicationGeometry.Corner.TR;
        drawUprightRimText(canvas, label, cx, cy, rimR, start, sweep, arcLen, mq, invertTop);
    }

    /**
     * Char-by-char rim text with upright glyphs (readable toward pads).
     * Layman: letters crawl around the dial the right way up.
     * Was: drawTextOnArch with tangent-only rot. Reversal: that rot path.
     * 2026-07-21
     */
    private void drawUprightRimText(Canvas canvas, String text, float cx, float cy, float radius,
            float startDeg, float sweepDeg, float arcLenPx, float marqueePx, boolean invertTop) {
        if (text == null || text.length() == 0 || radius < 8f) return;
        float textW = titlePaint.measureText(text);
        float gap = Math.max(18f, titlePaint.getTextSize() * 1.4f);
        float scroll;
        if (textW > arcLenPx) {
            float loop = textW + gap;
            scroll = marqueePx % loop;
            if (scroll < 0f) scroll += loop;
        } else {
            scroll = -(arcLenPx - textW) * 0.5f;
        }
        drawUprightRimPass(canvas, text, cx, cy, radius, startDeg, sweepDeg, arcLenPx, -scroll,
                invertTop);
        if (textW > arcLenPx) {
            drawUprightRimPass(canvas, text, cx, cy, radius, startDeg, sweepDeg, arcLenPx,
                    -scroll + textW + gap, invertTop);
        }
    }

    /** One upright glyph pass along the visible rim sweep. 2026-07-21 */
    private void drawUprightRimPass(Canvas canvas, String text, float cx, float cy, float radius,
            float startDeg, float sweepDeg, float arcLenPx, float offsetPx, boolean invertTop) {
        float cursor = offsetPx;
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            float cw = titlePaint.measureText(ch);
            float mid = cursor + cw * 0.5f;
            cursor += cw;
            if (mid < -cw || mid > arcLenPx + cw) continue;
            float t = mid / arcLenPx;
            if (t < 0f || t > 1f) continue;
            float deg = startDeg + sweepDeg * t;
            double rad = Math.toRadians(deg);
            float x = cx + radius * (float) Math.cos(rad);
            float y = cy + radius * (float) Math.sin(rad);
            float rot = StemComplicationGeometry.uprightGlyphRotationDeg(deg, invertTop);
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(rot);
            canvas.drawText(ch, 0f, 0f, titlePaint);
            canvas.restore();
        }
    }

    /**
     * One stem pad: art (or letter), label, gain ring, focus halo.
     * Hold-OK scrub: shrink halo + seek ball — face picks song seat; host seeks whole mixer.
     * Mute dims chrome. 2026-07-20 / 2026-07-21
     */
    private void drawPad(Canvas canvas, int zone, float cx, float cy, float baseR) {
        int song = zoneSong[zone];
        float vol = zoneGains[zone];
        if (loading) {
            vol = (float) (0.35 + 0.4 * Math.sin(System.currentTimeMillis() / 280.0 + zone));
        }
        float r = baseR * (0.78f + 0.38f * vol);
        float ringW = Math.max(2.5f, baseR * 0.07f);
        // Mute dim — soft dark wash + lower alpha (visual only; gain mute unchanged). 2026-07-21
        // Was: silent pads full brightness. Reversal: drop silentMul / wash overlay.
        float silentMul = loading ? 1f : StemControls.padSilentVisualMul(zoneGains[zone]);
        int wash = loading ? 0 : StemControls.padSilentDimOverlayAlpha(zoneGains[zone]);
        // Scrub chrome only on the lit pad (song seat selector). 2026-07-21
        boolean scrubHere = padScrubbing && zone == activeZone;

        if (zone == activeZone) {
            haloPaint.setColor(focusHaloColor);
            // Shrink ring while scrubbing so the seek ball reads clearly. 2026-07-21
            float haloScale = scrubHere ? StemMixSoftScrub.scrubFocusHaloScale() : 1f;
            haloPaint.setStrokeWidth(ringW * 1.7f * (scrubHere ? 0.85f : 1f));
            haloPaint.setAlpha(Math.round((loading ? 150 : 230) * silentMul));
            canvas.drawCircle(cx, cy, (r + ringW * 2.1f) * haloScale, haloPaint);
        }

        ringPaint.setStrokeWidth(ringW);
        int ringBase = zone == activeZone
                ? ((StemFaceView.STEM_COLORS[zone] & 0x00FFFFFF) | 0xCC000000)
                : RING_DIM;
        if (scrubHere) {
            // Amber rim while Hold-OK scrub armed. 2026-07-21
            ringBase = 0xFFFFCC66;
        }
        int ringA = Math.round(((ringBase >>> 24) & 0xFF) * silentMul);
        ringPaint.setColor((ringBase & 0x00FFFFFF) | (ringA << 24));
        canvas.drawCircle(cx, cy, r + ringW * 0.55f, ringPaint);

        Bitmap art = songArts[song];
        boolean hasArt = art != null && !art.isRecycled();
        if (hasArt) {
            drawCircleArt(canvas, art, cx, cy, r);
            // Same album on both tracks — tint + letter so pads stay distinct. 2026-07-20
            if (sameAlbumArt) {
                tintPaint.setColor((SONG_COLORS[song] & 0x00FFFFFF) | 0x66000000);
                canvas.drawCircle(cx, cy, r, tintPaint);
                drawLetter(canvas, songLetters[song], cx, cy, r * 0.75f);
            }
        } else {
            bubblePaint.setShader(null);
            bubblePaint.setColor(SONG_COLORS[song]);
            canvas.drawCircle(cx, cy, r, bubblePaint);
            drawLetter(canvas, songLetters[song], cx, cy, r);
        }

        // Dark wash when muted — pad still shows art but reads as “off”. 2026-07-21
        if (wash > 0) {
            tintPaint.setColor(0xFF000000);
            tintPaint.setAlpha(wash);
            canvas.drawCircle(cx, cy, r, tintPaint);
            tintPaint.setAlpha(255);
        }

        // Play-both badge — second song letter in a small circle on the pad’s rim.
        // Layman: doubled pad shows a second letter chip so stacking is obvious.
        // Was: no badge. Reversal: drop bothZones block. 2026-08-01
        if (zone >= 0 && zone < bothZones.length && bothZones[zone]) {
            int other = 1 - song;
            float badgeR = r * 0.30f;
            float bx = cx + r * 0.66f;
            float by = cy - r * 0.66f;
            bubblePaint.setShader(null);
            bubblePaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(bx, by, badgeR, bubblePaint);
            letterPaint.setColor(0xFF101014);
            letterPaint.setTextSize(badgeR * 1.2f);
            canvas.drawText(String.valueOf(songLetters[other]), bx, by + badgeR * 0.4f, letterPaint);
            letterPaint.setColor(0xFFFFFFFF);
        }

        // Circular seek cursor on rim — time for whole song seat, not one stem. 2026-07-21
        if (scrubHere) {
            float rim = r + ringW * 0.55f;
            float ball = Math.max(4f, r * StemMixSoftScrub.scrubCursorRadiusFrac());
            
            // Draw transition dot first if it exists
            if (padScrubTransitionFrac >= 0f) {
                StemMixSoftScrub.cursorXy(cx, cy, rim, padScrubTransitionFrac, scrubXy);
                scrubCursorPaint.setColor(0x88FFFFFF); // Semi-transparent white
                canvas.drawCircle(scrubXy[0], scrubXy[1], ball * 0.8f, scrubCursorPaint);
            }

            StemMixSoftScrub.cursorXy(cx, cy, rim, padScrubFrac, scrubXy);
            scrubCursorPaint.setColor(0xFFFFCC66);
            canvas.drawCircle(scrubXy[0], scrubXy[1], ball, scrubCursorPaint);
            scrubCursorPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(scrubXy[0], scrubXy[1], ball * 0.45f, scrubCursorPaint);
        }

        // Curved-ish label above/beside pad. 2026-07-20
        labelPaint.setTextSize(Math.max(8f, baseR * 0.28f));
        // Labels fade as the pad folds into its song disc. 2026-08-01
        labelPaint.setAlpha(Math.round(0xCC * silentMul * (1f - meatballBlend)));
        float labelY;
        if (zone == 0) labelY = cy - r - ringW * 2.2f;
        else if (zone == 3) labelY = cy + r + ringW * 2.8f;
        else labelY = cy - r - ringW * 1.6f;
        if (zone == 1) {
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            if (scrubHere) {
                labelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                canvas.drawText(StemMixSoftScrub.formatMmSs(padScrubMs), cx - r - ringW, cy + labelPaint.getTextSize() * 0.35f, labelPaint);
                labelPaint.setTypeface(android.graphics.Typeface.DEFAULT);
            } else {
                canvas.drawText(ZONE_LABELS[zone], cx - r - ringW, cy + labelPaint.getTextSize() * 0.35f, labelPaint);
            }
            labelPaint.setTextAlign(Paint.Align.CENTER);
        } else if (zone == 2) {
            labelPaint.setTextAlign(Paint.Align.LEFT);
            if (scrubHere) {
                labelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                canvas.drawText(StemMixSoftScrub.formatMmSs(padScrubMs), cx + r + ringW, cy + labelPaint.getTextSize() * 0.35f, labelPaint);
                labelPaint.setTypeface(android.graphics.Typeface.DEFAULT);
            } else {
                canvas.drawText(ZONE_LABELS[zone], cx + r + ringW, cy + labelPaint.getTextSize() * 0.35f, labelPaint);
            }
            labelPaint.setTextAlign(Paint.Align.CENTER);
        } else {
            if (scrubHere) {
                labelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                canvas.drawText(StemMixSoftScrub.formatMmSs(padScrubMs), cx, labelY, labelPaint);
                labelPaint.setTypeface(android.graphics.Typeface.DEFAULT);
            } else {
                canvas.drawText(ZONE_LABELS[zone], cx, labelY, labelPaint);
            }
        }
        labelPaint.setAlpha(0xCC);
    }

    /** White letter glyph centred in a pad. 2026-07-20 */
    private void drawLetter(Canvas canvas, char letter, float cx, float cy, float r) {
        letterPaint.setTextSize(r * 0.95f);
        Paint.FontMetrics fm = letterPaint.getFontMetrics();
        float ty = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(String.valueOf(letter), cx, ty, letterPaint);
    }

    /** Centre Play / shuffle disc (StemFM OK affordance). 2026-07-20 / 2026-07-21 */
    private void drawShuffleCentre(Canvas canvas, float cx, float cy, float r) {
        shufflePaint.setStyle(Paint.Style.FILL);
        int alpha = shufflePulse ? 200 : 120;
        shufflePaint.setColor((SHUFFLE_DISC & 0x00FFFFFF) | (alpha << 24));
        canvas.drawCircle(cx, cy, r, shufflePaint);
        shufflePaint.setStyle(Paint.Style.STROKE);
        shufflePaint.setStrokeWidth(Math.max(2f, r * 0.12f));
        shufflePaint.setColor(focusHaloColor);
        shufflePaint.setAlpha(shufflePulse ? 255 : 180);
        canvas.drawCircle(cx, cy, r, shufflePaint);

        shufflePaint.setColor(SHUFFLE_ICON);
        shufflePaint.setAlpha(255);
        if (!transportPlaying) {
            // Play triangle — jam not started yet. 2026-07-21
            shufflePaint.setStyle(Paint.Style.FILL);
            shufflePath.reset();
            float s = r * 0.55f;
            shufflePath.moveTo(cx - s * 0.35f, cy - s * 0.55f);
            shufflePath.lineTo(cx - s * 0.35f, cy + s * 0.55f);
            shufflePath.lineTo(cx + s * 0.65f, cy);
            shufflePath.close();
            canvas.drawPath(shufflePath, shufflePaint);
            return;
        }

        // Two curved arrows ≈ shuffle. 2026-07-20
        shufflePaint.setStyle(Paint.Style.STROKE);
        shufflePaint.setStrokeWidth(Math.max(2f, r * 0.14f));
        shufflePaint.setStrokeCap(Paint.Cap.ROUND);
        float a = r * 0.55f;
        tmpOval.set(cx - a, cy - a * 0.55f, cx + a * 0.15f, cy + a * 0.55f);
        canvas.drawArc(tmpOval, 200f, 140f, false, shufflePaint);
        tmpOval.set(cx - a * 0.15f, cy - a * 0.55f, cx + a, cy + a * 0.55f);
        canvas.drawArc(tmpOval, 20f, 140f, false, shufflePaint);
        // Arrow heads. 2026-07-20
        shufflePaint.setStyle(Paint.Style.FILL);
        shufflePath.reset();
        shufflePath.moveTo(cx - a * 0.85f, cy - a * 0.15f);
        shufflePath.lineTo(cx - a * 0.45f, cy - a * 0.55f);
        shufflePath.lineTo(cx - a * 0.35f, cy - a * 0.05f);
        shufflePath.close();
        canvas.drawPath(shufflePath, shufflePaint);
        shufflePath.reset();
        shufflePath.moveTo(cx + a * 0.85f, cy + a * 0.15f);
        shufflePath.lineTo(cx + a * 0.45f, cy + a * 0.55f);
        shufflePath.lineTo(cx + a * 0.35f, cy + a * 0.05f);
        shufflePath.close();
        canvas.drawPath(shufflePath, shufflePaint);
    }

    /**
     * One song disc for the two-disc meatball view — ring + art (or letter).
     * Dominant song: larger radius, brighter/wider ring; subordinate dims.
     * Layman: the big album-cover disc a track folds into while the pads nap.
     * Was: no discs (pads just shrank); equal emphasis. Reversal: drop block / equal.
     * 2026-08-01 / 2026-08-02
     */
    private void drawMeatballDisc(Canvas canvas, int song, float cx, float cy, float r,
            float emphasis) {
        if (r < 2f) return;
        // Lead disc ~8% larger + brighter ring; subordinate slightly smaller + dim. 2026-08-02
        float dr = r * (0.94f + 0.10f * emphasis);
        float ringW = Math.max(3f, dr * 0.055f) * (0.85f + 0.35f * emphasis);
        ringPaint.setStrokeWidth(ringW);
        int ringA = Math.round(0x88 + (0xE6 - 0x88) * emphasis);
        ringPaint.setColor((SONG_COLORS[song] & 0x00FFFFFF) | (ringA << 24));
        canvas.drawCircle(cx, cy, dr + ringW * 0.55f, ringPaint);

        Bitmap art = songArts[song];
        boolean hasArt = art != null && !art.isRecycled();
        if (hasArt) {
            drawCircleArt(canvas, art, cx, cy, dr);
            // Same album on both tracks — tint + letter keeps the discs distinct. 2026-08-01
            if (sameAlbumArt) {
                tintPaint.setColor((SONG_COLORS[song] & 0x00FFFFFF) | 0x66000000);
                canvas.drawCircle(cx, cy, dr, tintPaint);
                drawLetter(canvas, songLetters[song], cx, cy, dr * 0.75f);
            }
        } else {
            bubblePaint.setShader(null);
            bubblePaint.setColor(SONG_COLORS[song]);
            canvas.drawCircle(cx, cy, dr, bubblePaint);
            drawLetter(canvas, songLetters[song], cx, cy, dr);
        }
    }

    /** Circle-crop bitmap into pad. 2026-07-20 */
    private void drawCircleArt(Canvas canvas, Bitmap art, float cx, float cy, float r) {
        int bw = art.getWidth();
        int bh = art.getHeight();
        if (bw < 1 || bh < 1) return;
        float scale = (r * 2f) / Math.min(bw, bh);
        shaderMatrix.reset();
        shaderMatrix.setScale(scale, scale);
        shaderMatrix.postTranslate(cx - bw * scale * 0.5f, cy - bh * scale * 0.5f);
        BitmapShader shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(shaderMatrix);
        bubblePaint.setShader(shader);
        canvas.drawCircle(cx, cy, r, bubblePaint);
        bubblePaint.setShader(null);
    }
}
