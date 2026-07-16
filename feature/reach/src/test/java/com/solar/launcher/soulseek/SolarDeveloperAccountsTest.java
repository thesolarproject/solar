package com.solar.launcher.soulseek;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SolarDeveloperAccountsTest {

    @Test
    public void mapDeveloperWireNamesToCanonical() {
        assertEquals("SolarDeveloper",
                SolarDeveloperAccounts.mapToConversationPeer("SolarDev"));
        assertEquals("SolarDeveloper",
                SolarDeveloperAccounts.mapToConversationPeer("thesolarphone"));
        assertEquals("SolarDeveloper",
                SolarDeveloperAccounts.mapToConversationPeer("__solar_developer__"));
        assertEquals("alice",
                SolarDeveloperAccounts.mapToConversationPeer("alice"));
    }

    @Test
    public void stripDiagTokens() {
        assertTrue(SolarDeveloperAccounts.isAutoDiagnosticText("solar_diag_boot"));
        assertTrue(SolarDeveloperAccounts.isAutoDiagnosticText(
                SolarDeveloperAccounts.DIAG_MARKER + "event: x"));
        String stripped = SolarDeveloperAccounts.stripDiagnosticText("hello solar_diag_x there")
                .replaceAll("\\s+", " ").trim();
        assertTrue(stripped.contains("hello"));
        assertTrue(stripped.contains("there"));
        assertFalse(stripped.toLowerCase().contains("solar_diag"));
        String mixed = SolarDeveloperAccounts.stripDiagnosticText("hi solar_diag\nmore");
        assertTrue(mixed.contains("hi") || mixed.contains("more"));
        assertFalse(mixed.toLowerCase().contains("solar_diag"));
    }

    @Test
    public void displayName() {
        assertEquals("@SolarDeveloper",
                SolarDeveloperAccounts.displayNameForPeer("SolarDeveloper"));
        assertEquals("@SolarDeveloper",
                SolarDeveloperAccounts.displayNameForPeer("SolarDev"));
    }

    @Test
    public void welcomeNotEmpty() {
        assertTrue(SolarDeveloperAccounts.welcomeMessageBody().contains("github.com/thesolarproject/solar"));
    }
}
