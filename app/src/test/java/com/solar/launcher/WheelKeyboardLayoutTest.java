package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WheelKeyboardLayoutTest {

    @Test
    public void groupedPagesKeepEveryPrintableAsciiCharacter() {
        String printable = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "0123456789 !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
        for (int i = 0; i < printable.length(); i++) {
            String value = String.valueOf(printable.charAt(i));
            int page = WheelKeyboardLayout.pageForCharacter(
                    value, WheelKeyboardLayout.PAGE_LOWER);
            String target = " ".equals(value)
                    ? SolarWheelKeyboardController.TOKEN_SPC : value;
            assertTrue("missing " + value, WheelKeyboardLayout.find(
                    WheelKeyboardLayout.charset(true, page, false, false), target) >= 0);
        }
    }

    @Test
    public void pageSwitchPreservesCharacterOffsetAndActions() {
        String[] lower = WheelKeyboardLayout.charset(
                true, WheelKeyboardLayout.PAGE_LOWER, false, false);
        String[] upper = WheelKeyboardLayout.charset(
                true, WheelKeyboardLayout.PAGE_UPPER, false, false);
        assertEquals(12, WheelKeyboardLayout.mapIndexToPage(
                lower, 12, WheelKeyboardLayout.PAGE_LOWER,
                upper, WheelKeyboardLayout.PAGE_UPPER));
        int delete = WheelKeyboardLayout.find(lower,
                SolarWheelKeyboardController.TOKEN_DEL);
        assertEquals(WheelKeyboardLayout.find(upper,
                        SolarWheelKeyboardController.TOKEN_DEL),
                WheelKeyboardLayout.mapIndexToPage(lower, delete,
                        WheelKeyboardLayout.PAGE_LOWER, upper,
                        WheelKeyboardLayout.PAGE_UPPER));
    }

    @Test
    public void groupedPagesWinRepresentativeWheelActionHarness() {
        WheelKeyboardLayoutBenchmark.Result result =
                WheelKeyboardLayoutBenchmark.compare(new String[] {
                        "the beatles",
                        "wifi password 2026",
                        "https://example.com/music",
                        "Miles Davis - Kind of Blue",
                        "artist_album.flac"
                });
        assertTrue(result.supportedCharacters > 80);
        assertTrue("grouped=" + result.groupedPageActions
                        + " ring=" + result.alphabetRingActions,
                result.groupedPageActions < result.alphabetRingActions);
        assertEquals(WheelKeyboardLayout.MODE_GROUPED_PAGES,
                result.recommendedMode());
    }
}
