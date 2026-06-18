package com.solar.launcher;

/** Wheel keyboard charset blocks — lower / upper / digits / symbols. */
public final class KeyboardCharset {
    public static final int LOWER = 0;
    public static final int UPPER = 26;
    public static final int DIGIT = 52;
    public static final int SYMBOL = 62;
    public static final int SPECIAL = 76;

    private KeyboardCharset() {}

    public static int flipCaseIndex(int index) {
        if (index >= LOWER && index < UPPER) return index + (UPPER - LOWER);
        if (index >= UPPER && index < DIGIT) return index - (UPPER - LOWER);
        return index;
    }

    public static int offsetInBlock(int index) {
        if (index < UPPER) return index - LOWER;
        if (index < DIGIT) return index - UPPER;
        if (index < SYMBOL) return index - DIGIT;
        if (index < SPECIAL) return index - SYMBOL;
        return 0;
    }

    public static int blockLength(int blockStart) {
        if (blockStart == LOWER) return UPPER - LOWER;
        if (blockStart == DIGIT) return SYMBOL - DIGIT;
        if (blockStart == SYMBOL) return SPECIAL - SYMBOL;
        return UPPER - LOWER;
    }

    /** Map wheel position into another charset block (play long-press: digits, symbols, …). */
    public static int mapIndexToBlock(int index, int blockStart) {
        if (index >= SPECIAL) return blockStart;
        return blockStart + (offsetInBlock(index) % blockLength(blockStart));
    }

    public static int nextCharsetBlockStart(int index) {
        if (index < DIGIT) return DIGIT;
        if (index < SYMBOL) return SYMBOL;
        return LOWER;
    }

    public static int mapToNextCharset(int index) {
        return mapIndexToBlock(index, nextCharsetBlockStart(index));
    }

    public static int lowercaseIndexForChar(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a';
        return -1;
    }

    public static void selfCheck() {
        if (flipCaseIndex(2) != 28) throw new AssertionError("f->F");
        if (flipCaseIndex(28) != 2) throw new AssertionError("F->f");
        if (mapToNextCharset(5) != 57) throw new AssertionError("f->5");
        if (mapIndexToBlock(28, LOWER) != 2) throw new AssertionError("F block->f");
        if (lowercaseIndexForChar('M') != 12) throw new AssertionError("M");
    }
}
