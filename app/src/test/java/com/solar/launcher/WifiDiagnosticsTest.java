package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WifiDiagnosticsTest {

    @Test
    public void formatsLittleEndianDhcpAddresses() {
        assertEquals("192.168.1.1", WifiDiagnostics.ipv4(0x0101A8C0));
        assertEquals("8.8.8.8", WifiDiagnostics.ipv4(0x08080808));
        assertEquals("—", WifiDiagnostics.ipv4(0));
    }

    @Test
    public void labelsSignalWithoutPretendingUnknownIsWeak() {
        assertEquals("—", WifiDiagnostics.signalLabel(-127));
        assertEquals("Strong", WifiDiagnostics.signalLabel(-55));
        assertEquals("Good", WifiDiagnostics.signalLabel(-70));
        assertEquals("Weak", WifiDiagnostics.signalLabel(-82));
        assertEquals("Very weak", WifiDiagnostics.signalLabel(-95));
    }

    @Test
    public void formatsDhcpLeaseCompactly() {
        assertEquals("—", WifiDiagnostics.leaseLabel(0));
        assertEquals("1m", WifiDiagnostics.leaseLabel(30));
        assertEquals("5m", WifiDiagnostics.leaseLabel(300));
        assertEquals("2h 5m", WifiDiagnostics.leaseLabel(7500));
    }
}
