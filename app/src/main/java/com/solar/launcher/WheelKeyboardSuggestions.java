package com.solar.launcher;

import java.util.List;
import java.util.Locale;

/** Prefix completion from already-local search history; never performs network or database work. */
public final class WheelKeyboardSuggestions {

    public static final String TOKEN_SUGGEST = "[SUG]";

    private WheelKeyboardSuggestions() {}

    public static String[] appendToken(String[] charset) {
        if (charset == null) return new String[] { TOKEN_SUGGEST };
        if (WheelKeyboardLayout.find(charset, TOKEN_SUGGEST) >= 0) return charset;
        String[] result = new String[charset.length + 1];
        // Keep Enter last so open networks and fast-confirm behavior remain unchanged.
        int enter = WheelKeyboardLayout.find(
                charset, SolarWheelKeyboardController.TOKEN_CONN);
        if (enter < 0) {
            System.arraycopy(charset, 0, result, 0, charset.length);
            result[charset.length] = TOKEN_SUGGEST;
            return result;
        }
        System.arraycopy(charset, 0, result, 0, enter);
        result[enter] = TOKEN_SUGGEST;
        System.arraycopy(charset, enter, result, enter + 1,
                charset.length - enter);
        return result;
    }

    /** Return the most-recent prefix match, or an empty string for no useful completion. */
    public static String bestCompletion(String input, List<String> recent) {
        if (input == null || input.length() == 0 || recent == null) return "";
        String prefix = input.toLowerCase(Locale.US);
        for (String candidate : recent) {
            if (candidate == null) continue;
            String value = candidate.trim();
            if (value.length() <= input.length()) continue;
            if (value.toLowerCase(Locale.US).startsWith(prefix)) return value;
        }
        return "";
    }
}
