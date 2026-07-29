package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Character-page policy for the shared wheel keyboard. */
public final class WheelKeyboardLayout {

    public static final String PREF_LAYOUT = "keyboard_layout";
    public static final String MODE_ALPHABET_RING = "alphabet_ring";
    public static final String MODE_GROUPED_PAGES = "grouped_pages";

    public static final int PAGE_LOWER = 0;
    public static final int PAGE_UPPER = 1;
    public static final int PAGE_DIGITS = 2;
    public static final int PAGE_SYMBOLS = 3;
    public static final int PAGE_COUNT = 4;

    private static final String[] LOWER_CHARS = {
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z"
    };
    private static final String[] UPPER_CHARS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
    };
    private static final String[] DIGIT_CHARS = {
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"
    };
    private static final String[] SYMBOL_CHARS = {
            "!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-",
            ".", "/", ":", ";", "<", "=", ">", "?", "@", "[", "\\", "]", "^",
            "_", "`", "{", "|", "}", "~"
    };

    private static final String[][] TEXT_PAGES = {
            withActions(LOWER_CHARS, false),
            withActions(UPPER_CHARS, false),
            withActions(DIGIT_CHARS, false),
            withActions(SYMBOL_CHARS, false)
    };
    private static final String[][] PASSWORD_PAGES = {
            withActions(LOWER_CHARS, true),
            withActions(UPPER_CHARS, true),
            withActions(DIGIT_CHARS, true),
            withActions(SYMBOL_CHARS, true)
    };

    private WheelKeyboardLayout() {}

    /** Grouped pages won the deterministic action-count harness and are the safe new default. */
    public static boolean isGrouped(SharedPreferences prefs) {
        if (prefs == null) return true;
        return MODE_GROUPED_PAGES.equals(
                prefs.getString(PREF_LAYOUT, MODE_GROUPED_PAGES));
    }

    public static boolean isGrouped(Context context) {
        if (context == null) return true;
        return isGrouped(context.getSharedPreferences(
                BluetoothAudioRepair.PREFS, Context.MODE_PRIVATE));
    }

    public static String mode(SharedPreferences prefs) {
        return isGrouped(prefs) ? MODE_GROUPED_PAGES : MODE_ALPHABET_RING;
    }

    public static int labelRes(SharedPreferences prefs) {
        return isGrouped(prefs)
                ? R.string.settings_keyboard_layout_grouped
                : R.string.settings_keyboard_layout_ring;
    }

    public static String toggledMode(SharedPreferences prefs) {
        return isGrouped(prefs) ? MODE_ALPHABET_RING : MODE_GROUPED_PAGES;
    }

    public static String[] charset(boolean grouped, int page,
            boolean password, boolean digitOnly) {
        if (digitOnly) return SolarWheelKeyboardController.PIN_CHARSET;
        if (!grouped) {
            return password ? SolarWheelKeyboardController.PASSWORD_CHARSET
                    : SolarWheelKeyboardController.CHARSET;
        }
        int safePage = normalizePage(page);
        return password ? PASSWORD_PAGES[safePage] : TEXT_PAGES[safePage];
    }

    public static int nextPage(int page) {
        return (normalizePage(page) + 1) % PAGE_COUNT;
    }

    public static int pageForCharacter(String character, int fallbackPage) {
        if (find(LOWER_CHARS, character) >= 0) return PAGE_LOWER;
        if (find(UPPER_CHARS, character) >= 0) return PAGE_UPPER;
        if (find(DIGIT_CHARS, character) >= 0) return PAGE_DIGITS;
        if (find(SYMBOL_CHARS, character) >= 0) return PAGE_SYMBOLS;
        return normalizePage(fallbackPage);
    }

    public static int characterCount(int page) {
        if (page == PAGE_DIGITS) return DIGIT_CHARS.length;
        if (page == PAGE_SYMBOLS) return SYMBOL_CHARS.length;
        return LOWER_CHARS.length;
    }

    /**
     * Preserve letter/symbol offset or the same action token when switching grouped pages.
     */
    public static int mapIndexToPage(String[] oldCharset, int oldIndex,
            int oldPage, String[] newCharset, int newPage) {
        if (newCharset == null || newCharset.length == 0) return 0;
        if (oldCharset == null || oldIndex < 0 || oldIndex >= oldCharset.length) return 0;
        int oldCharacterCount = characterCount(normalizePage(oldPage));
        if (oldIndex < oldCharacterCount) {
            return Math.min(oldIndex, characterCount(normalizePage(newPage)) - 1);
        }
        int matchingAction = find(newCharset, oldCharset[oldIndex]);
        return matchingAction >= 0 ? matchingAction : 0;
    }

    public static int find(String[] charset, String value) {
        if (charset == null || value == null) return -1;
        for (int i = 0; i < charset.length; i++) {
            if (value.equals(charset[i])) return i;
        }
        return -1;
    }

    static String[] pageCharacters(int page) {
        if (page == PAGE_UPPER) return UPPER_CHARS;
        if (page == PAGE_DIGITS) return DIGIT_CHARS;
        if (page == PAGE_SYMBOLS) return SYMBOL_CHARS;
        return LOWER_CHARS;
    }

    private static int normalizePage(int page) {
        if (page < 0 || page >= PAGE_COUNT) return PAGE_LOWER;
        return page;
    }

    private static String[] withActions(String[] characters, boolean password) {
        int actionCount = password ? 7 : 6;
        String[] result = new String[characters.length + actionCount];
        System.arraycopy(characters, 0, result, 0, characters.length);
        int i = characters.length;
        result[i++] = SolarWheelKeyboardController.TOKEN_SPC;
        result[i++] = SolarWheelKeyboardController.TOKEN_DEL;
        result[i++] = SolarWheelKeyboardController.TOKEN_LEFT;
        result[i++] = SolarWheelKeyboardController.TOKEN_RIGHT;
        result[i++] = SolarWheelKeyboardController.TOKEN_WORD;
        if (password) result[i++] = SolarWheelKeyboardController.TOKEN_VISIBILITY;
        result[i] = SolarWheelKeyboardController.TOKEN_CONN;
        return result;
    }
}
