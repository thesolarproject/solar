package com.solar.launcher.keyboard;

/** Shared wheel keyboard state machine — used by in-app full and IME compact views. */
public final class KeyboardEngine {
    public interface Callback {
        void onTextChanged(String text);
        void onCommit(String text);
    }

    private final Callback callback;
    private final int maxLength;
    private final boolean password;
    private StringBuilder buffer = new StringBuilder();
    private int wheelIndex = KeyboardCharset.LOWER;

    public KeyboardEngine(Callback callback, String initial, int maxLength, boolean password) {
        this.callback = callback;
        this.maxLength = maxLength;
        this.password = password;
        if (initial != null && initial.length() > 0) {
            buffer.append(initial.length() > maxLength ? initial.substring(0, maxLength) : initial);
        }
    }

    public String getText() { return buffer.toString(); }
    public int getWheelIndex() { return wheelIndex; }
    public boolean isPassword() { return password; }

    public void setWheelIndex(int index) { wheelIndex = index; }

    public void cycleCharset() {
        wheelIndex = KeyboardCharset.mapToNextCharset(wheelIndex);
    }

    public void flipCase() {
        wheelIndex = KeyboardCharset.flipCaseIndex(wheelIndex);
    }

    public void appendCurrentChar(char[][] charsetTable, int row, int col) {
        if (buffer.length() >= maxLength) return;
        char c = charsetTable[row][col];
        if (c == 0) return;
        buffer.append(c);
        if (callback != null) callback.onTextChanged(buffer.toString());
    }

    public void backspace() {
        if (buffer.length() == 0) return;
        buffer.deleteCharAt(buffer.length() - 1);
        if (callback != null) callback.onTextChanged(buffer.toString());
    }

    public void commit() {
        if (callback != null) callback.onCommit(buffer.toString());
    }

    public String displayText() {
        if (!password) return buffer.toString();
        StringBuilder masked = new StringBuilder(buffer.length());
        for (int i = 0; i < buffer.length(); i++) masked.append('*');
        return masked.toString();
    }
}
