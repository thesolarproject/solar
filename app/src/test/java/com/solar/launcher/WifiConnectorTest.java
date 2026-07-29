package com.solar.launcher;

import android.net.wifi.WifiConfiguration;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WifiConnectorTest {

    @Test
    public void selfCheck() {
        WifiConnector.selfCheck();
        SolarLog.selfCheck();
    }

    @Test
    public void quotedSsidAndFindSaved() {
        if (!"\"MyNet\"".equals(WifiConnector.quotedSsid("MyNet"))) {
            throw new AssertionError("quotedSsid");
        }
        List<WifiConfiguration> configs = new ArrayList<WifiConfiguration>();
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"Cafe\"";
        c.networkId = 42;
        configs.add(c);
        if (WifiConnector.findSavedNetId(configs, "Cafe") != 42) {
            throw new AssertionError("find saved");
        }
        if (WifiConnector.findSavedNetId(configs, "Other") != -1) {
            throw new AssertionError("missing ssid");
        }
        if (WifiConnector.findSavedNetId(null, "x") != -1) {
            throw new AssertionError("null list");
        }
    }

    @Test
    public void wifiStringsEscapeQuotesAndBackslashes() {
        String raw = "Cafe \"East\" \\ guest";
        String encoded = WifiConnector.quoteWifiString(raw);
        assertEquals("\"Cafe \\\"East\\\" \\\\ guest\"", encoded);
        assertEquals(raw, WifiConnector.unquoteWifiString(encoded));
        assertEquals("Cafe\\Guest", WifiConnector.unquoteWifiString("Cafe\\Guest"));

        List<WifiConfiguration> configs = new ArrayList<WifiConfiguration>();
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = encoded;
        c.networkId = 7;
        configs.add(c);
        assertEquals(7, WifiConnector.findSavedNetId(configs, raw));
    }

    @Test
    public void classifiesLegacyAndUnsupportedSecurity() {
        assertEquals(WifiConnector.Security.OPEN,
                WifiConnector.securityForCapabilities("[ESS]"));
        assertEquals(WifiConnector.Security.WPA_PERSONAL,
                WifiConnector.securityForCapabilities("[WPA2-PSK-CCMP][ESS]"));
        assertEquals(WifiConnector.Security.WEP,
                WifiConnector.securityForCapabilities("[WEP][ESS]"));
        assertEquals(WifiConnector.Security.ENTERPRISE,
                WifiConnector.securityForCapabilities("[WPA2-EAP-CCMP][ESS]"));
        assertEquals(WifiConnector.Security.MODERN_UNSUPPORTED,
                WifiConnector.securityForCapabilities("[RSN-SAE-CCMP][ESS]"));
        assertEquals(WifiConnector.Security.MODERN_UNSUPPORTED,
                WifiConnector.securityForCapabilities("[OWE][ESS]"));
    }

    @Test
    public void validatesWpaPasswordAndSsid() {
        assertEquals(WifiConnector.Failure.INVALID_PASSWORD,
                WifiConnector.validateInput("Home", "short",
                        WifiConnector.Security.WPA_PERSONAL));
        assertEquals(WifiConnector.Failure.NONE,
                WifiConnector.validateInput("Home", "eight888",
                        WifiConnector.Security.WPA_PERSONAL));
        assertEquals(WifiConnector.Failure.NONE,
                WifiConnector.validateInput("Home",
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        WifiConnector.Security.WPA_PERSONAL));
        assertEquals(WifiConnector.Failure.UNSUPPORTED_SECURITY,
                WifiConnector.validateInput("Corp", "password",
                        WifiConnector.Security.ENTERPRISE));
        assertEquals(WifiConnector.Failure.INVALID_SSID,
                WifiConnector.validateInput("abcdefghijklmnopqrstuvwxyz1234567", "",
                        WifiConnector.Security.OPEN));
    }

    @Test
    public void encodesHexPskWithoutQuotes() {
        String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(hex, WifiConnector.encodedWpaKey(hex));
        assertEquals("\"pass\\\"word\"", WifiConnector.encodedWpaKey("pass\"word"));
    }

    @Test
    public void associationRequiresMatchingNetworkSsidAndCompletedSupplicant() {
        assertTrue(WifiConnector.isExpectedAssociation(
                9, "\"Home\"", "COMPLETED", 9, "Home"));
        assertTrue(WifiConnector.isExpectedAssociation(
                9, "\"Cafe \\\"East\\\"\"", "COMPLETED", 9, "Cafe \"East\""));
        assertFalse(WifiConnector.isExpectedAssociation(
                9, "\"Home\"", "ASSOCIATING", 9, "Home"));
        assertFalse(WifiConnector.isExpectedAssociation(
                8, "\"Home\"", "COMPLETED", 9, "Home"));
        assertFalse(WifiConnector.isExpectedAssociation(
                9, "\"Other\"", "COMPLETED", 9, "Home"));
    }

    @Test
    public void mapsSpecificFailuresToSpecificMessages() {
        assertEquals(R.string.toast_wifi_invalid_password,
                WifiConnector.failureMessageResId(WifiConnector.Failure.INVALID_PASSWORD));
        assertEquals(R.string.toast_wifi_auth_failed,
                WifiConnector.failureMessageResId(WifiConnector.Failure.AUTHENTICATION_FAILED));
        assertEquals(R.string.toast_wifi_connect_timeout,
                WifiConnector.failureMessageResId(WifiConnector.Failure.TIMEOUT));
        assertEquals(R.string.toast_wifi_connect_failed,
                WifiConnector.failureMessageResId(WifiConnector.Failure.SYSTEM_ERROR));
    }
}
