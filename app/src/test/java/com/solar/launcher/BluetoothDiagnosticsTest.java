package com.solar.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BluetoothDiagnosticsTest {

    @Test
    public void labelsAdapterProfileAndBondStates() {
        assertEquals("On",
                BluetoothDiagnostics.adapterStateLabel(BluetoothAdapter.STATE_ON));
        assertEquals("Turning off",
                BluetoothDiagnostics.adapterStateLabel(BluetoothAdapter.STATE_TURNING_OFF));
        assertEquals("A2DP connected",
                BluetoothDiagnostics.profileStateLabel(BluetoothProfile.STATE_CONNECTED));
        assertEquals("A2DP connecting",
                BluetoothDiagnostics.profileStateLabel(BluetoothProfile.STATE_CONNECTING));
        assertEquals("Bonded",
                BluetoothDiagnostics.bondStateLabel(BluetoothDevice.BOND_BONDED));
        assertEquals("Pairing",
                BluetoothDiagnostics.bondStateLabel(BluetoothDevice.BOND_BONDING));
    }

    @Test
    public void explainsDisconnectFromAvailableState() {
        assertEquals("A2DP disconnected (reason 8)",
                BluetoothDiagnostics.disconnectReason("", 0,
                        BluetoothProfile.STATE_CONNECTED, 8));
        assertEquals("A2DP audio link disconnected",
                BluetoothDiagnostics.disconnectReason("", 0,
                        BluetoothProfile.STATE_CONNECTED, -1));
        assertEquals("Connection attempt ended before audio connected",
                BluetoothDiagnostics.disconnectReason("", 0,
                        BluetoothProfile.STATE_CONNECTING, -1));
    }

    @Test
    public void sanitizesOptionalCodecParameters() {
        assertEquals("SBC",
                BluetoothDiagnostics.sanitizeCodecParameter("A2dpCodec=SBC", "A2dpCodec"));
        assertEquals("aptX",
                BluetoothDiagnostics.sanitizeCodecParameter("aptX", "A2dpCodec"));
        assertEquals("",
                BluetoothDiagnostics.sanitizeCodecParameter("A2dpCodec=", "A2dpCodec"));
        assertEquals("",
                BluetoothDiagnostics.sanitizeCodecParameter("unknown", "A2dpCodec"));
    }

    @Test
    public void formatsEventAgeCompactly() {
        long now = 1_000_000L;
        assertEquals("5s ago", BluetoothDiagnostics.ageLabel(now, now - 5_000L));
        assertEquals("3m ago", BluetoothDiagnostics.ageLabel(now, now - 180_000L));
        assertEquals("2h ago", BluetoothDiagnostics.ageLabel(now, now - 7_200_000L));
        assertEquals("3d ago", BluetoothDiagnostics.ageLabel(now, now - 259_200_000L));
    }
}
