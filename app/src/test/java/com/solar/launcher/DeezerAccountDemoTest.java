package com.solar.launcher;

import com.solar.launcher.deezer.DeezerAccount;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Bundled tester ARL masking rules. */
public class DeezerAccountDemoTest {

    @Test
    public void bundledDemoArlIsPresent() {
        assertTrue(DeezerAccount.hasBundledDemoArl());
    }

    @Test
    public void displayLabelMasksWhenNoPrefs() {
        assertFalse(DeezerAccount.isUsingDemoArl(null));
        assertEquals("Test/Demo Account",
                DeezerAccount.displayLabel(null, "Test/Demo Account"));
    }
}
