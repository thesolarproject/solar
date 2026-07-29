package com.solar.launcher;

/**
 * Deterministic interaction-cost harness for limited-input keyboard layouts.
 *
 * <p>One wheel notch and one center press cost one action. A held page change costs three actions
 * to account for hold time. Unsupported characters are skipped rather than improving either score.
 */
public final class WheelKeyboardLayoutBenchmark {

    static final int PAGE_CHANGE_COST = 3;

    public static final class Result {
        public final int alphabetRingActions;
        public final int groupedPageActions;
        public final int supportedCharacters;

        Result(int alphabetRingActions, int groupedPageActions, int supportedCharacters) {
            this.alphabetRingActions = alphabetRingActions;
            this.groupedPageActions = groupedPageActions;
            this.supportedCharacters = supportedCharacters;
        }

        public String recommendedMode() {
            return groupedPageActions < alphabetRingActions
                    ? WheelKeyboardLayout.MODE_GROUPED_PAGES
                    : WheelKeyboardLayout.MODE_ALPHABET_RING;
        }
    }

    private WheelKeyboardLayoutBenchmark() {}

    public static Result compare(String[] samples) {
        int alphabet = 0;
        int grouped = 0;
        int supported = 0;
        if (samples == null) samples = new String[0];
        for (String sample : samples) {
            String value = sample != null ? sample : "";
            AlphabetState alphabetState = new AlphabetState();
            GroupedState groupedState = new GroupedState();
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                offset += Character.charCount(codePoint);
                String character = new String(Character.toChars(codePoint));
                int alphabetCost = alphabetState.type(character);
                int groupedCost = groupedState.type(character);
                if (alphabetCost >= 0 && groupedCost >= 0) {
                    alphabet += alphabetCost;
                    grouped += groupedCost;
                    supported++;
                }
            }
        }
        return new Result(alphabet, grouped, supported);
    }

    private static int circularDistance(int from, int to, int length) {
        int direct = Math.abs(to - from);
        return Math.min(direct, length - direct);
    }

    private static final class AlphabetState {
        int index;

        int type(String character) {
            String target = " ".equals(character)
                    ? SolarWheelKeyboardController.TOKEN_SPC : character;
            int next = WheelKeyboardLayout.find(
                    SolarWheelKeyboardController.CHARSET, target);
            if (next < 0) return -1;
            int actions = circularDistance(index, next,
                    SolarWheelKeyboardController.CHARSET.length) + 1;
            index = next;
            return actions;
        }
    }

    private static final class GroupedState {
        int page = WheelKeyboardLayout.PAGE_LOWER;
        int index;

        int type(String character) {
            int targetPage = " ".equals(character) ? page
                    : WheelKeyboardLayout.pageForCharacter(character, page);
            String[] oldCharset = WheelKeyboardLayout.charset(
                    true, page, false, false);
            int pageChanges = 0;
            while (page != targetPage && pageChanges < WheelKeyboardLayout.PAGE_COUNT) {
                int nextPage = WheelKeyboardLayout.nextPage(page);
                String[] nextCharset = WheelKeyboardLayout.charset(
                        true, nextPage, false, false);
                index = WheelKeyboardLayout.mapIndexToPage(
                        oldCharset, index, page, nextCharset, nextPage);
                page = nextPage;
                oldCharset = nextCharset;
                pageChanges++;
            }
            String target = " ".equals(character)
                    ? SolarWheelKeyboardController.TOKEN_SPC : character;
            int next = WheelKeyboardLayout.find(oldCharset, target);
            if (next < 0) return -1;
            int actions = pageChanges * PAGE_CHANGE_COST
                    + circularDistance(index, next, oldCharset.length) + 1;
            index = next;
            if (targetPage == WheelKeyboardLayout.PAGE_UPPER
                    && character.length() == 1) {
                String[] lower = WheelKeyboardLayout.charset(
                        true, WheelKeyboardLayout.PAGE_LOWER, false, false);
                index = WheelKeyboardLayout.mapIndexToPage(
                        oldCharset, index, page, lower, WheelKeyboardLayout.PAGE_LOWER);
                page = WheelKeyboardLayout.PAGE_LOWER;
            }
            return actions;
        }
    }
}
