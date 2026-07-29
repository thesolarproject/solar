package com.solar.launcher;

/**
 * Allocation-light text editing primitives shared by Solar's in-app and overlay keyboards.
 *
 * <p>Cursor offsets use Java/Android UTF-16 indices so they can be passed directly to an
 * {@code InputConnection}. Movement and backspace still respect complete Unicode code points.
 */
public final class WheelTextEditor {

    public static final class State {
        public final String text;
        public final int cursor;

        State(String text, int cursor) {
            this.text = text != null ? text : "";
            this.cursor = clampCursor(this.text, cursor);
        }
    }

    private WheelTextEditor() {}

    public static int clampCursor(String text, int cursor) {
        int length = text != null ? text.length() : 0;
        if (cursor < 0) return 0;
        if (cursor > length) return length;
        return cursor;
    }

    public static State insert(String text, int cursor, String insertion) {
        String value = text != null ? text : "";
        String added = insertion != null ? insertion : "";
        int at = clampCursor(value, cursor);
        if (added.length() == 0) return new State(value, at);
        return new State(value.substring(0, at) + added + value.substring(at),
                at + added.length());
    }

    public static State deleteBeforeCursor(String text, int cursor) {
        String value = text != null ? text : "";
        int at = clampCursor(value, cursor);
        if (at <= 0) return new State(value, at);
        int start;
        try {
            start = value.offsetByCodePoints(at, -1);
        } catch (Exception ignored) {
            start = at - 1;
        }
        return new State(value.substring(0, start) + value.substring(at), start);
    }

    /**
     * Delete the whitespace before the cursor and the preceding word/punctuation run.
     * This mirrors Ctrl+Backspace while remaining predictable for URLs and filenames.
     */
    public static State deleteWordBeforeCursor(String text, int cursor) {
        String value = text != null ? text : "";
        int end = clampCursor(value, cursor);
        if (end <= 0) return new State(value, end);
        int start = end;

        while (start > 0) {
            int previous = previousCodePointStart(value, start);
            int codePoint = value.codePointAt(previous);
            if (!Character.isWhitespace(codePoint)) break;
            start = previous;
        }
        if (start <= 0) return new State(value.substring(end), 0);

        int previous = previousCodePointStart(value, start);
        boolean deleteWordRun = isWordCodePoint(value.codePointAt(previous));
        while (start > 0) {
            previous = previousCodePointStart(value, start);
            int codePoint = value.codePointAt(previous);
            if (Character.isWhitespace(codePoint)
                    || isWordCodePoint(codePoint) != deleteWordRun) {
                break;
            }
            start = previous;
        }
        return new State(value.substring(0, start) + value.substring(end), start);
    }

    public static State moveCursor(String text, int cursor, int direction) {
        String value = text != null ? text : "";
        int at = clampCursor(value, cursor);
        if (direction == 0 || value.length() == 0) return new State(value, at);
        int next = at;
        int remaining = Math.abs(direction);
        while (remaining-- > 0) {
            if (direction < 0) {
                if (next <= 0) break;
                next = previousCodePointStart(value, next);
            } else {
                if (next >= value.length()) break;
                try {
                    next = value.offsetByCodePoints(next, 1);
                } catch (Exception ignored) {
                    next++;
                }
            }
        }
        return new State(value, next);
    }

    /**
     * Render the current edit location. Passwords retain only their length unless visibility is
     * explicitly enabled by the user.
     */
    public static String render(String text, int cursor, boolean mask, boolean showCursor) {
        String value = text != null ? text : "";
        int at = clampCursor(value, cursor);
        String visible = mask ? maskByCodePoint(value) : value;
        if (!showCursor) return visible;
        int visibleCursor = mask ? value.codePointCount(0, at) : at;
        return visible.substring(0, visibleCursor) + "|"
                + visible.substring(visibleCursor);
    }

    public static int deletedBeforeCursorCount(State before, State after) {
        if (before == null || after == null) return 0;
        return Math.max(0, before.cursor - after.cursor);
    }

    private static String maskByCodePoint(String value) {
        int count = value.codePointCount(0, value.length());
        StringBuilder masked = new StringBuilder(count);
        for (int i = 0; i < count; i++) masked.append('*');
        return masked.toString();
    }

    private static int previousCodePointStart(String value, int cursor) {
        try {
            return value.offsetByCodePoints(cursor, -1);
        } catch (Exception ignored) {
            return Math.max(0, cursor - 1);
        }
    }

    private static boolean isWordCodePoint(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
                || codePoint == '_' || codePoint == '\'' || codePoint == '-';
    }
}
