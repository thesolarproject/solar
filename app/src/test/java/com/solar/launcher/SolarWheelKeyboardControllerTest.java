package com.solar.launcher;

import android.text.InputType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Wheel keyboard charset + token mapping self-check. */
public class SolarWheelKeyboardControllerTest {
    @Test
    public void selfCheck() {
        SolarWheelKeyboardController.selfCheck();
    }

    @Test
    public void editActionsUseCursorAndVisibilityIsExplicit() {
        SolarWheelKeyboardController controller = new SolarWheelKeyboardController();
        controller.setPasswordMode(true);
        controller.setBuffer("ab cd");
        controller.setCursor(2);
        controller.insertText("X");
        assertEquals("abX cd", controller.getBuffer());
        assertEquals("***|***", controller.renderBuffer(true));

        controller.moveCursor(3);
        controller.deleteWord();
        assertEquals("abX ", controller.getBuffer());
        assertFalse(controller.isPasswordVisible());
        controller.togglePasswordVisibility();
        assertTrue(controller.isPasswordVisible());
        assertEquals("abX |", controller.renderBuffer(true));
    }

    @Test
    public void imeRecognizesTextAndNumericPasswordFields() {
        assertTrue(SolarInputMethodService.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        assertTrue(SolarInputMethodService.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD));
        assertTrue(SolarInputMethodService.isPasswordInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD));
        assertTrue(SolarInputMethodService.isNumericPasswordInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD));
        assertFalse(SolarInputMethodService.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
        assertFalse(SolarInputMethodService.isNumericPasswordInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
    }

    @Test
    public void groupedHoldCyclesPagesAndUppercaseReturnsToLowercase() {
        SolarWheelKeyboardController controller = new SolarWheelKeyboardController();
        controller.setGroupedMode(true);
        assertEquals(WheelKeyboardLayout.PAGE_LOWER, controller.getPage());
        controller.playPauseLongPress();
        assertEquals(WheelKeyboardLayout.PAGE_UPPER, controller.getPage());
        controller.setIndex(1);
        controller.centerPress();
        assertEquals("B", controller.getBuffer());
        assertEquals(WheelKeyboardLayout.PAGE_LOWER, controller.getPage());
        assertEquals(1, controller.getIndex());
    }
}
