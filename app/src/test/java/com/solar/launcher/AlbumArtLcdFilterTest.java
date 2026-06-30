package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AlbumArtLcdFilterTest {

    @Test
    public void ditherLevel_darkPixelIsOff() {
        assertEquals(0, AlbumArtLcdFilter.ditherLevel(0, 1, 1));
    }

    @Test
    public void ditherLevel_brightPixelIsOn() {
        assertEquals(2, AlbumArtLcdFilter.ditherLevel(250, 0, 0));
    }

    @Test
    public void paletteOffPixelsAreTransparent() {
        LcdArtPalette palette = LcdArtPalette.of(0xFF224422, 0xFF88FF88);
        assertEquals(0, palette.offColor);
    }

    @Test
    public void paletteUsesThemeBlendForMidTone() {
        LcdArtPalette palette = LcdArtPalette.of(0xFF002200, 0xFF00FF00);
        int mid = palette.midColor;
        assertEquals(0, mid >>> 24);
        assertEquals(0, palette.offColor);
        assertTrue((mid & 0x0000FF00) != 0);
    }

    @Test
    public void midAlpha_fadesDarkPixelsToTransparent() {
        assertEquals(0, AlbumArtLcdFilter.midAlphaForLuminance(0, 0));
        assertEquals(0, AlbumArtLcdFilter.midAlphaForLuminance(40, 0));
        assertTrue(AlbumArtLcdFilter.midAlphaForLuminance(120, 0) > 0);
        assertTrue(AlbumArtLcdFilter.midAlphaForLuminance(120, 0)
                < AlbumArtLcdFilter.midAlphaForLuminance(192, 0));
        assertEquals(0x99, AlbumArtLcdFilter.midAlphaForLuminance(250, 0));
    }

    @Test
    public void midAlpha_crushedWhenCoverIsDarkDominant() {
        int lightDominance = AlbumArtLcdFilter.midAlphaForLuminance(140, 0);
        int heavyDominance = AlbumArtLcdFilter.midAlphaForLuminance(140, 200);
        assertTrue(heavyDominance < lightDominance);
    }

    @Test
    public void pixelArgb_darkMidIsTransparent() {
        LcdArtPalette palette = LcdArtPalette.of(0xFF002200, 0xFF00FF00);
        assertEquals(0, AlbumArtLcdFilter.pixelArgb(0, 10, palette, 0));
        assertEquals(0, AlbumArtLcdFilter.pixelArgb(1, 30, palette, 0));
        assertTrue((AlbumArtLcdFilter.pixelArgb(2, 250, palette, 0) >>> 24) == 255);
        assertEquals(0, AlbumArtLcdFilter.pixelArgb(2, 80, palette, 220));
    }

    @Test
    public void shouldInvert_lightCover() {
        int[] whiteField = new int[80 * 80];
        for (int i = 0; i < whiteField.length; i++) whiteField[i] = 240;
        assertTrue(AlbumArtLcdFilter.shouldInvertLuminance(whiteField));
    }

    @Test
    public void shouldInvert_darkCover() {
        int[] darkField = new int[80 * 80];
        for (int i = 0; i < darkField.length; i++) darkField[i] = 30;
        assertFalse(AlbumArtLcdFilter.shouldInvertLuminance(darkField));
    }

    @Test
    public void darkDominance_mostlyDark() {
        int[] lums = new int[] {20, 30, 40, 200};
        assertTrue(AlbumArtLcdFilter.darkDominance(lums) > 180);
    }
}
