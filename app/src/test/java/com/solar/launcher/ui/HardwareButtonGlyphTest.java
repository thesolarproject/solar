package com.solar.launcher.ui;

import org.junit.After;
import org.junit.Test;

/**
 * 2026-07-18 — Smoke checks for HardwareButtonGlyph (no Android Context needed for colour hex / enum paths).
 * 2026-07-20 — Font-lock size helper + no em-dash / no literal OK in Context-null tutorial builders.
 */
public class HardwareButtonGlyphTest {

    @After
    public void tearDown() {
        HardwareButtonGlyph.clearCacheForTest();
    }

    @Test
    public void boundsForFontHeightPreservesAspect() {
        // Wide Prev-like 92×48 → height 14 → width ~27
        int[] wide = HardwareButtonGlyph.boundsForFontHeight(92, 48, 14);
        if (wide[1] != 14) throw new AssertionError("height locked to font: " + wide[1]);
        if (wide[0] < 20 || wide[0] > 30) throw new AssertionError("wide width: " + wide[0]);
        // Square OK 48×48 → 14×14
        int[] square = HardwareButtonGlyph.boundsForFontHeight(48, 48, 14);
        if (square[0] != 14 || square[1] != 14) {
            throw new AssertionError("square: " + square[0] + "x" + square[1]);
        }
        // Back/options composite ~127×48 — height 14 → width ~37
        int[] back = HardwareButtonGlyph.boundsForFontHeight(127, 48, 14);
        if (back[1] != 14) throw new AssertionError("back height");
        if (back[0] < 34 || back[0] > 40) throw new AssertionError("back width: " + back[0]);
        // Play/pause/stop ~133×48 — height 14 → width ~39
        int[] pp = HardwareButtonGlyph.boundsForFontHeight(133, 48, 14);
        if (pp[1] != 14) throw new AssertionError("pp height");
        if (pp[0] < 36 || pp[0] > 42) throw new AssertionError("pp width: " + pp[0]);
        // Wider source still maps wider (aspect lock)
        int[] ppWide = HardwareButtonGlyph.boundsForFontHeight(133, 48, 14);
        if (ppWide[0] <= wide[0]) throw new AssertionError("wider source must stay wider");
    }

    @Test
    public void sizePxMatchingPaintUsesFontMetricsFallback() {
        // 2026-07-20 — Null paint fails open to 1px (callers always pass a real paint on device).
        if (HardwareButtonGlyph.sizePxMatchingPaint(null) != 1) {
            throw new AssertionError("null paint");
        }
        if (HardwareButtonGlyph.sizePxMatchingTextView(null) != 1) {
            throw new AssertionError("null TextView");
        }
    }

    @Test
    public void buttonAssetPathsAreY1Pngs() {
        for (HardwareButtonGlyph.Button b : HardwareButtonGlyph.Button.values()) {
            if (b.assetPath == null || !b.assetPath.startsWith("y1/btn_") || !b.assetPath.endsWith(".png")) {
                throw new AssertionError("bad path: " + b + " → " + b.assetPath);
            }
        }
        if (!"y1/btn_back.png".equals(HardwareButtonGlyph.Button.BACK.assetPath)) {
            throw new AssertionError("BACK path");
        }
        if (!"y1/btn_ok.png".equals(HardwareButtonGlyph.Button.OK.assetPath)) {
            throw new AssertionError("OK path");
        }
        if (!"y1/btn_wheel.png".equals(HardwareButtonGlyph.Button.WHEEL.assetPath)) {
            throw new AssertionError("WHEEL path");
        }
    }

    @Test
    public void loadRawNullContextFailsOpen() {
        if (HardwareButtonGlyph.loadRaw(null, HardwareButtonGlyph.Button.OK) != null) {
            throw new AssertionError("null context must fail-open");
        }
        if (HardwareButtonGlyph.tintedDrawable(null, HardwareButtonGlyph.Button.OK, 0xFFFFFFFF, 14) != null) {
            throw new AssertionError("null tint must fail-open");
        }
    }

    @Test
    public void volumeUpDownHintUsesArrows() {
        // 2026-07-20 — First-play NP volume tip (no Context needed).
        CharSequence tip = HardwareButtonGlyph.volumeUpDownHint(null, 14);
        String s = tip != null ? tip.toString() : "";
        if (s.indexOf('\u21BB') < 0 || !s.contains("Volume Up")) {
            throw new AssertionError("missing up: " + s);
        }
        if (s.indexOf('\u21BA') < 0 || !s.contains("Volume Down")) {
            throw new AssertionError("missing down: " + s);
        }
    }

    @Test
    public void stripLeadingHoldWordKeepsAction() {
        // 2026-07-20 — Action-only resources (no em-dash); was "hold — open Flow".
        CharSequence a = HardwareButtonGlyph.stripLeadingHoldWord("hold open Flow");
        String as = a.toString();
        if (!as.contains("open Flow") || as.toLowerCase().startsWith("hold")) {
            throw new AssertionError("strip hold: " + as);
        }
        CharSequence b = HardwareButtonGlyph.stripLeadingHoldWord("Aa/#");
        if (!"Aa/#".equals(b.toString())) throw new AssertionError("no hold prefix: " + b);
        CharSequence c = HardwareButtonGlyph.stripLeadingHoldWord("Hold Options");
        if (!"Options".equals(c.toString())) throw new AssertionError("Hold Options: " + c);
        CharSequence d = HardwareButtonGlyph.stripLeadingHoldWord("open Flow");
        if (!"open Flow".equals(d.toString())) throw new AssertionError("action-only: " + d);
    }

    @Test
    public void hasGlyphSpansRejectsPlainText() {
        // 2026-07-20 — Font walker must not treat ordinary titles as glyph rows.
        if (HardwareButtonGlyph.hasGlyphSpans(null)) throw new AssertionError("null");
        if (HardwareButtonGlyph.hasGlyphSpans("")) throw new AssertionError("empty");
        if (HardwareButtonGlyph.hasGlyphSpans("hold open Flow")) {
            throw new AssertionError("plain string must be false");
        }
    }

    @Test
    public void queueAndPlaylistProseAvoidEmDashAndOkWord() {
        // 2026-07-20 — Spannable builders need Android framework (not mocked on JVM).
        // Assert the prose constants builders use instead.
        assertCleanTutorialProse(HardwareButtonGlyph.QUEUE_HOLD_ACTION);
        assertCleanTutorialProse(HardwareButtonGlyph.OK_PLACE_LABEL);
        assertCleanTutorialProse(HardwareButtonGlyph.PLAYLIST_HOLD_ACTION);
        assertCleanTutorialProse(HardwareButtonGlyph.PLAYLIST_WHEEL_LABEL);
        if (!HardwareButtonGlyph.QUEUE_HOLD_ACTION.toLowerCase().contains("pick up")) {
            throw new AssertionError("queue hold structure");
        }
        if (!"place".equals(HardwareButtonGlyph.OK_PLACE_LABEL)) {
            throw new AssertionError("place label");
        }
    }

    @Test
    public void mixOnboardingProseAvoidEmDashAndOkWord() {
        // 2026-07-20 — Mix fader / assign tip prose (glyphs supply the buttons).
        assertCleanTutorialProse(HardwareButtonGlyph.MIX_FADER_HIGHLIGHT_ACTION);
        assertCleanTutorialProse(HardwareButtonGlyph.MIX_FADER_WHEEL_ACTION);
        assertCleanTutorialProse(HardwareButtonGlyph.MIX_ASSIGN_BIND_ACTION);
        assertCleanTutorialProse(HardwareButtonGlyph.MIX_ASSIGN_START_ACTION);
        if (!HardwareButtonGlyph.MIX_FADER_WHEEL_ACTION.toLowerCase().contains("fader")) {
            throw new AssertionError("fader wheel action");
        }
        if (!HardwareButtonGlyph.MIX_ASSIGN_START_ACTION.toLowerCase().contains("mix")) {
            throw new AssertionError("assign start action");
        }
    }

    @Test
    public void stemMixTipStubsAreDescriptiveNotTwoWords() {
        // 2026-07-21 — Context tip stubs must read as a lesson, not "Stem pick" / "Mix".
        // Was: two-word stubs as header fallback. Reversal: short title stubs.
        assertDescriptiveTipStub(HardwareButtonGlyph.STEM_PICK_TIP_STUB, "Center");
        assertDescriptiveTipStub(HardwareButtonGlyph.STEM_FACE_TIP_STUB, "volume");
        assertDescriptiveTipStub(HardwareButtonGlyph.STEM_MIX_JOURNEY_TIP_STUB, "Volume");
        assertDescriptiveTipStub(HardwareButtonGlyph.MIX_ASSIGN_TIP_STUB, "deck");
        assertDescriptiveTipStub(HardwareButtonGlyph.MIX_FADER_TIP_STUB, "fader");
    }

    /** Fail if tip stub is a two-word title with no teaching sentence. 2026-07-21 */
    private static void assertDescriptiveTipStub(String stub, String mustContain) {
        if (stub == null || stub.trim().length() < 40) {
            throw new AssertionError("stub too short: " + stub);
        }
        if (stub.indexOf('\n') < 0) {
            throw new AssertionError("stub needs a second line: " + stub);
        }
        assertCleanTutorialProse(stub);
        if (mustContain != null && !stub.toLowerCase().contains(mustContain.toLowerCase())) {
            throw new AssertionError("missing '" + mustContain + "': " + stub);
        }
    }

    @Test
    public void stemLiveWindowStillTwoPads() {
        // 2026-07-21 — Pick queue unlimited; live pads stay MAX_SONGS=2.
        // Was: StemPickSlots.SLOT_COUNT == 2 (mark cap). Reversal: that SLOT_COUNT assert.
        if (com.solar.launcher.stem.StemSession.MAX_SONGS != 2) {
            throw new AssertionError("MAX_SONGS: " + com.solar.launcher.stem.StemSession.MAX_SONGS);
        }
        if (com.solar.launcher.stem.StemPickSlots.LIVE_WINDOW != 2) {
            throw new AssertionError("LIVE_WINDOW");
        }
    }

    /** Fail if tutorial prose embeds em-dash or the word OK (glyph supplies the button). */
    private static void assertCleanTutorialProse(String s) {
        if (s == null) throw new AssertionError("null prose");
        if (s.indexOf('\u2014') >= 0) throw new AssertionError("em-dash: " + s);
        if (s.contains("OK") || s.contains("Ok")) {
            throw new AssertionError("must not spell OK: " + s);
        }
    }
}
