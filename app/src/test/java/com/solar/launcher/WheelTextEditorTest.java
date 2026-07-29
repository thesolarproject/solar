package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WheelTextEditorTest {

    @Test
    public void insertsAndDeletesAtCursorInsteadOfOnlyAtEnd() {
        WheelTextEditor.State inserted = WheelTextEditor.insert("abcd", 2, "X");
        assertEquals("abXcd", inserted.text);
        assertEquals(3, inserted.cursor);

        WheelTextEditor.State deleted =
                WheelTextEditor.deleteBeforeCursor(inserted.text, inserted.cursor);
        assertEquals("abcd", deleted.text);
        assertEquals(2, deleted.cursor);
    }

    @Test
    public void deletesOneWholeWordAndLeadingSpace() {
        WheelTextEditor.State deleted =
                WheelTextEditor.deleteWordBeforeCursor("artist name  remix", 18);
        assertEquals("artist name  ", deleted.text);
        assertEquals(13, deleted.cursor);

        deleted = WheelTextEditor.deleteWordBeforeCursor("artist name  ", 13);
        assertEquals("artist ", deleted.text);
        assertEquals(7, deleted.cursor);
    }

    @Test
    public void movementAndBackspacePreserveUnicodeCodePoints() {
        String text = "a\uD83C\uDFB5b";
        WheelTextEditor.State left = WheelTextEditor.moveCursor(text, 3, -1);
        assertEquals(1, left.cursor);
        WheelTextEditor.State right = WheelTextEditor.moveCursor(text, 1, 1);
        assertEquals(3, right.cursor);
        WheelTextEditor.State deleted = WheelTextEditor.deleteBeforeCursor(text, 3);
        assertEquals("ab", deleted.text);
        assertEquals(1, deleted.cursor);
    }

    @Test
    public void passwordRenderingNeverLeaksCharactersUnlessRequested() {
        assertEquals("**|**", WheelTextEditor.render("p@ss", 2, true, true));
        assertEquals("p@|ss", WheelTextEditor.render("p@ss", 2, false, true));
        assertEquals("****", WheelTextEditor.render("p@ss", 4, true, false));
    }
}
