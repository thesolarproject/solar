package com.solar.launcher;

import org.junit.Test;

public class WifiScanFilterTest {
    @Test
    public void hidesNvramWarningSsids() {
        if (!WifiScanFilter.isHiddenSsid("NVRAM WARNING: ERR=0x10")) {
            throw new AssertionError("nvram should hide");
        }
        if (WifiScanFilter.isHiddenSsid("MyNetwork")) {
            throw new AssertionError("normal ssid");
        }
        if (!WifiScanFilter.isHiddenSsid(null)) {
            throw new AssertionError("null is not displayable");
        }
    }

    @Test
    public void hidesHexPlaceholderSsids() {
        if (!WifiScanFilter.isHiddenSsid("0x")) {
            throw new AssertionError("bare 0x");
        }
        if (!WifiScanFilter.isHiddenSsid("0xdeadbeef")) {
            throw new AssertionError("0x prefix");
        }
        if (WifiScanFilter.isHiddenSsid("Net0x01")) {
            throw new AssertionError("0x mid-string is not hidden");
        }
    }

    @Test
    public void displayableConnectedSsidStripsPhantoms() {
        if (!"".equals(WifiScanFilter.displayableConnectedSsid("\"0x\""))) {
            throw new AssertionError("quoted hex placeholder");
        }
        if (!"".equals(WifiScanFilter.displayableConnectedSsid("<unknown ssid>"))) {
            throw new AssertionError("unknown ssid");
        }
        if (!"HomeNet".equals(WifiScanFilter.displayableConnectedSsid("\"HomeNet\""))) {
            throw new AssertionError("real ssid");
        }
        if (!"Cafe \"East\"".equals(
                WifiScanFilter.displayableConnectedSsid("\"Cafe \\\"East\\\"\""))) {
            throw new AssertionError("escaped quote in real ssid");
        }
        if (!" Guest ".equals(WifiScanFilter.displayableConnectedSsid("\" Guest \""))) {
            throw new AssertionError("valid surrounding spaces");
        }
    }
}
