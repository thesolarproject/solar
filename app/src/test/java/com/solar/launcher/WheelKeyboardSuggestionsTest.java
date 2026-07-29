package com.solar.launcher;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WheelKeyboardSuggestionsTest {

    @Test
    public void completesFromMostRecentPrefixWithoutChangingCase() {
        assertEquals("The Beatles",
                WheelKeyboardSuggestions.bestCompletion("the b", Arrays.asList(
                        "The Beatles", "The Beach Boys", "Miles Davis")));
        assertEquals("", WheelKeyboardSuggestions.bestCompletion(
                "The Beatles", Arrays.asList("The Beatles")));
        assertEquals("", WheelKeyboardSuggestions.bestCompletion(
                "", Arrays.asList("The Beatles")));
    }

    @Test
    public void suggestionActionIsBeforeEnterAndDoesNotMutateBase() {
        String[] base = WheelKeyboardLayout.charset(
                true, WheelKeyboardLayout.PAGE_LOWER, false, false);
        String[] withSuggestion = WheelKeyboardSuggestions.appendToken(base);
        assertEquals(base.length + 1, withSuggestion.length);
        assertEquals(SolarWheelKeyboardController.TOKEN_CONN,
                withSuggestion[withSuggestion.length - 1]);
        assertEquals(WheelKeyboardSuggestions.TOKEN_SUGGEST,
                withSuggestion[withSuggestion.length - 2]);
        assertTrue(WheelKeyboardLayout.find(
                base, WheelKeyboardSuggestions.TOKEN_SUGGEST) < 0);
    }
}
