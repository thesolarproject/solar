package com.solar.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WifiAutoConnectPolicyTest {
    @Test
    public void enabledPolicyReconnectsOnlyWhileWifiIsOn() {
        assertTrue(WifiAutoConnectPolicy.shouldRequestReconnect(true, true));
        assertFalse(WifiAutoConnectPolicy.shouldRequestReconnect(true, false));
    }

    @Test
    public void disabledPolicyNeverForcesReconnect() {
        assertFalse(WifiAutoConnectPolicy.shouldRequestReconnect(false, true));
        assertFalse(WifiAutoConnectPolicy.shouldRequestReconnect(false, false));
    }
}
