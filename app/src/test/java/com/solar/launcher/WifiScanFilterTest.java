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
        if (WifiScanFilter.isHiddenSsid(null)) {
            throw new AssertionError("null");
        }
    }
}
