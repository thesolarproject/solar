package com.solar.launcher;

/**
 * 2026-07-05 — Shared wheel keyboard engine for in-app and system IME trays.
 * Layman: one carousel of letters/symbols the scroll wheel moves through; center types the pick.
 * Technical: charset index + buffer; used by MainActivity and SolarInputMethodService.
 */
public final class SolarWheelKeyboardController {

    public static final String TOKEN_SPC = "[SPC]";
    public static final String TOKEN_DEL = "[DEL]";
    public static final String TOKEN_LEFT = "[<]";
    public static final String TOKEN_RIGHT = "[>]";
    public static final String TOKEN_WORD = "[WD]";
    public static final String TOKEN_VISIBILITY = "[VIS]";
    public static final String TOKEN_CONN = "[CONN]";

    /** Wheel charset — lower, upper, digits, symbols, then editing actions. */
    public static final String[] CHARSET = {
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u",
            "v", "w", "x", "y", "z",
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U",
            "V", "W", "X", "Y", "Z",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-",
            ".", "/", ":", ";", "<", "=", ">", "?", "@", "[", "\\", "]", "^",
            "_", "`", "{", "|", "}", "~",
            TOKEN_SPC, TOKEN_DEL, TOKEN_LEFT, TOKEN_RIGHT, TOKEN_WORD, TOKEN_CONN
    };

    /** Sensitive-entry strip adds an explicit show/hide action; passwords default masked. */
    public static final String[] PASSWORD_CHARSET = {
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u",
            "v", "w", "x", "y", "z",
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U",
            "V", "W", "X", "Y", "Z",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-",
            ".", "/", ":", ";", "<", "=", ">", "?", "@", "[", "\\", "]", "^",
            "_", "`", "{", "|", "}", "~",
            TOKEN_SPC, TOKEN_DEL, TOKEN_LEFT, TOKEN_RIGHT, TOKEN_WORD,
            TOKEN_VISIBILITY, TOKEN_CONN
    };

    /** Bluetooth PIN entry: digits plus the same safe cursor/visibility actions. */
    public static final String[] PIN_CHARSET = {
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            TOKEN_DEL, TOKEN_LEFT, TOKEN_RIGHT, TOKEN_VISIBILITY, TOKEN_CONN
    };

    /** Fired when buffer or index changes — UI refresh hook. */
    public interface Listener {
        void onStateChanged();
        /** User picked [CONN] — host runs submit (Wi‑Fi connect, search, etc.). */
        void onEnterRequested();
    }

    private int index;
    private String buffer = "";
    private int cursor;
    private boolean ppLongDoCase = true;
    private boolean digitOnlyMode;
    private boolean passwordMode;
    private boolean passwordVisible;
    private boolean groupedMode = true;
    private int page = WheelKeyboardLayout.PAGE_LOWER;
    private Listener listener;
    /** Active wheel strip — full CHARSET or PIN_CHARSET for Bluetooth pairing. */
    private String[] charset = CHARSET;

    public SolarWheelKeyboardController() {
        reset();
    }

    /** Switch to digit-only strip for Bluetooth PIN entry (2026-07-05). */
    public void setDigitOnlyMode(boolean digitOnly) {
        digitOnlyMode = digitOnly;
        charset = activeCharset();
        index = 0;
        notifyChanged();
    }

    /** Password previews are masked until the user selects [VIS]. */
    public void setPasswordMode(boolean password) {
        passwordMode = password;
        passwordVisible = false;
        charset = activeCharset();
        index = 0;
        notifyChanged();
    }

    public void setGroupedMode(boolean grouped) {
        groupedMode = grouped;
        page = WheelKeyboardLayout.PAGE_LOWER;
        charset = activeCharset();
        index = 0;
        notifyChanged();
    }

    public String[] getCharset() {
        return charset;
    }

    /** Attach UI / IME host for refresh callbacks. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Clear buffer and park on first letter. */
    public void reset() {
        index = 0;
        buffer = "";
        cursor = 0;
        ppLongDoCase = true;
        passwordVisible = false;
        page = WheelKeyboardLayout.PAGE_LOWER;
        charset = activeCharset();
        notifyChanged();
    }

    /** Seed text before show (Reach search, Wi‑Fi overlay handoff, etc.). */
    public void setBuffer(String text) {
        buffer = text != null ? text : "";
        cursor = buffer.length();
        notifyChanged();
    }

    public String getBuffer() {
        return buffer;
    }

    public int getCursor() {
        return cursor;
    }

    public void setCursor(int cursor) {
        this.cursor = WheelTextEditor.clampCursor(buffer, cursor);
        notifyChanged();
    }

    public boolean isPasswordMode() {
        return passwordMode;
    }

    public boolean isPasswordVisible() {
        return passwordVisible;
    }

    public boolean isGroupedMode() {
        return groupedMode;
    }

    public int getPage() {
        return page;
    }

    public String renderBuffer(boolean showCursor) {
        return WheelTextEditor.render(buffer, cursor,
                passwordMode && !passwordVisible, showCursor);
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int idx) {
        if (idx < 0 || idx >= charset.length) return;
        index = idx;
        notifyChanged();
    }

    /** Map [CONN] to display label (e.g. [ENT]). */
    public static String displayChar(String ch, String enterLabel) {
        return displayChar(ch, enterLabel, false);
    }

    /** Dynamic label for the password visibility action. */
    public static String displayChar(String ch, String enterLabel, boolean passwordVisible) {
        if (TOKEN_CONN.equals(ch)) return enterLabel != null ? enterLabel : "[ENT]";
        if (TOKEN_VISIBILITY.equals(ch)) return passwordVisible ? "[HID]" : "[VIS]";
        return ch;
    }

    /** Index offset for 5-slot strip (pprev/prev/current/next/nnnext). */
    public static int wrapIndex(int base, int offset) {
        return wrapIndex(base, offset, CHARSET.length);
    }

    public static int wrapIndex(int base, int offset, int len) {
        return (base + offset + len) % len;
    }

    public int wrapActiveIndex(int base, int offset) {
        return wrapIndex(base, offset, charset.length);
    }

    public static String charAtIndex(int idx) {
        return charAtIndex(idx, CHARSET);
    }

    public static String charAtIndex(int idx, String[] cs) {
        if (cs == null || idx < 0 || idx >= cs.length) return "";
        return cs[idx];
    }

    public String charAt(int idx) {
        return charAtIndex(idx, charset);
    }

    /** Wheel up — previous charset slot. */
    public void wheelUp() {
        index = wrapActiveIndex(index, -1);
        notifyChanged();
    }

    /** Wheel down — next charset slot. */
    public void wheelDown() {
        index = wrapActiveIndex(index, 1);
        notifyChanged();
    }

    /** Center / OK — type current token into buffer or submit. */
    public void centerPress() {
        String selected = charset[index];
        if (TOKEN_DEL.equals(selected)) {
            deleteLastChar();
        } else if (TOKEN_LEFT.equals(selected)) {
            moveCursor(-1);
        } else if (TOKEN_RIGHT.equals(selected)) {
            moveCursor(1);
        } else if (TOKEN_WORD.equals(selected)) {
            deleteWord();
        } else if (TOKEN_VISIBILITY.equals(selected)) {
            togglePasswordVisibility();
        } else if (TOKEN_CONN.equals(selected)) {
            if (listener != null) listener.onEnterRequested();
        } else if (TOKEN_SPC.equals(selected)) {
            insertText(" ");
        } else {
            if (selected.length() == 1) {
                char ch = selected.charAt(0);
                if (ch >= 'A' && ch <= 'Z') {
                    int lowerIndex = KeyboardCharset.lowercaseIndexForChar(ch);
                    if (lowerIndex >= 0) {
                        if (groupedMode) {
                            page = WheelKeyboardLayout.PAGE_LOWER;
                            charset = activeCharset();
                        }
                        index = Math.min(lowerIndex, charset.length - 1);
                        ppLongDoCase = true;
                    }
                }
            }
            insertText(selected);
        }
    }

    /** Prev track short — delete one character. */
    public void mediaDelete() {
        deleteLastChar();
    }

    /** Next track short — insert space. */
    public void mediaSpace() {
        insertText(" ");
    }

    public void insertText(String text) {
        WheelTextEditor.State state = WheelTextEditor.insert(buffer, cursor, text);
        applyEdit(state);
    }

    public void moveCursor(int direction) {
        applyEdit(WheelTextEditor.moveCursor(buffer, cursor, direction));
    }

    public void deleteWord() {
        applyEdit(WheelTextEditor.deleteWordBeforeCursor(buffer, cursor));
    }

    public void togglePasswordVisibility() {
        if (!passwordMode) return;
        passwordVisible = !passwordVisible;
        notifyChanged();
    }

    public void requestEnter() {
        if (listener != null) listener.onEnterRequested();
    }

    /** 2026-07-20 — Play long-press: Capitals ↔ Symbols blocks (was Aa / # label). */
    public void playPauseLongPress() {
        if (digitOnlyMode) return;
        if (groupedMode) {
            String[] oldCharset = charset;
            int oldPage = page;
            page = WheelKeyboardLayout.nextPage(page);
            charset = activeCharset();
            index = WheelKeyboardLayout.mapIndexToPage(
                    oldCharset, index, oldPage, charset, page);
            notifyChanged();
            return;
        }
        if (ppLongDoCase) {
            int flipped = KeyboardCharset.flipCaseIndex(index);
            index = flipped != index ? flipped : KeyboardCharset.mapToNextCharset(index);
        } else {
            index = KeyboardCharset.mapToNextCharset(index);
        }
        ppLongDoCase = !ppLongDoCase;
        notifyChanged();
    }

    /** True when current slot is [DEL] — MainActivity soulseek auto-username path. */
    public boolean isDeleteTokenSelected() {
        return index >= 0 && index < charset.length && TOKEN_DEL.equals(charset[index]);
    }

    /** Replace buffer entirely (soulseek auto-username clear on first DEL). */
    public void clearBuffer() {
        buffer = "";
        cursor = 0;
        notifyChanged();
    }

    private void deleteLastChar() {
        applyEdit(WheelTextEditor.deleteBeforeCursor(buffer, cursor));
    }

    private String[] activeCharset() {
        return WheelKeyboardLayout.charset(
                groupedMode, page, passwordMode, digitOnlyMode);
    }

    private void applyEdit(WheelTextEditor.State state) {
        if (state == null) return;
        boolean changed = !buffer.equals(state.text) || cursor != state.cursor;
        buffer = state.text;
        cursor = state.cursor;
        if (changed) notifyChanged();
    }

    private void notifyChanged() {
        if (listener != null) listener.onStateChanged();
    }

    /** Unit-test guard — charset + token mapping invariants. */
    public static void selfCheck() {
        KeyboardCharset.selfCheck();
        if (CHARSET.length != 100) throw new AssertionError("charset len");
        if (PASSWORD_CHARSET.length != 101) throw new AssertionError("password charset len");
        if (PIN_CHARSET.length != 15) throw new AssertionError("pin charset len");
        if (!TOKEN_CONN.equals(CHARSET[CHARSET.length - 1])) throw new AssertionError("CONN last");
        if (!"[ENT]".equals(displayChar(TOKEN_CONN, "[ENT]"))) throw new AssertionError("ENT label");
        SolarWheelKeyboardController c = new SolarWheelKeyboardController();
        c.centerPress();
        if (!"a".equals(c.getBuffer())) throw new AssertionError("type a");
        if (c.getCursor() != 1) throw new AssertionError("cursor after type");
        c.wheelDown();
        if (c.getIndex() != 1) throw new AssertionError("wheel");
        c.setBuffer("ac");
        c.setCursor(1);
        c.insertText("b");
        if (!"abc".equals(c.getBuffer()) || c.getCursor() != 2) {
            throw new AssertionError("cursor insert");
        }
        SolarWheelKeyboardController pin = new SolarWheelKeyboardController();
        pin.setDigitOnlyMode(true);
        if (!"0".equals(pin.charAt(0))) throw new AssertionError("pin start");
        pin.centerPress();
        if (!"0".equals(pin.getBuffer())) throw new AssertionError("pin type 0");
    }
}
