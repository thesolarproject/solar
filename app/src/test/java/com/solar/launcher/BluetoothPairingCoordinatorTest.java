package com.solar.launcher;

import android.bluetooth.BluetoothDevice;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Unit tests for global Bluetooth pairing coordinator routing (2026-07-05 / 2026-07-19). */
public class BluetoothPairingCoordinatorTest {

    @Test
    public void testFormatPasskey() {
        assertEquals("012345", BluetoothPairingCoordinator.formatPasskey(12345));
        assertEquals("000042", BluetoothPairingCoordinator.formatPasskey(42));
        assertEquals("000000", BluetoothPairingCoordinator.formatPasskey(-1));
    }

    @Test
    public void testOverlayModeForVariant() {
        assertEquals(BluetoothPairingCoordinator.MODE_PIN,
                BluetoothPairingCoordinator.overlayModeForVariant(
                        BluetoothPairingCoordinator.PAIRING_VARIANT_PIN));
        assertEquals(BluetoothPairingCoordinator.MODE_PASSKEY_DISPLAY,
                BluetoothPairingCoordinator.overlayModeForVariant(1));
        assertEquals(BluetoothPairingCoordinator.MODE_PASSKEY_CONFIRM,
                BluetoothPairingCoordinator.overlayModeForVariant(
                        BluetoothPairingCoordinator.PAIRING_VARIANT_PASSKEY_CONFIRMATION));
        assertEquals(BluetoothPairingCoordinator.MODE_CONSENT,
                BluetoothPairingCoordinator.overlayModeForVariant(3));
    }

    @Test
    public void testApi17AndApi19PairingVariantsUseCompatibleOverlays() {
        assertEquals(BluetoothPairingCoordinator.MODE_PIN,
                BluetoothPairingCoordinator.overlayModeForVariant(0));
        assertEquals(BluetoothPairingCoordinator.MODE_PASSKEY_DISPLAY,
                BluetoothPairingCoordinator.overlayModeForVariant(1));
        assertEquals(BluetoothPairingCoordinator.MODE_PASSKEY_CONFIRM,
                BluetoothPairingCoordinator.overlayModeForVariant(2));
        assertEquals(BluetoothPairingCoordinator.MODE_CONSENT,
                BluetoothPairingCoordinator.overlayModeForVariant(3));
        assertEquals(BluetoothPairingCoordinator.MODE_PASSKEY_DISPLAY,
                BluetoothPairingCoordinator.overlayModeForVariant(4));
        assertEquals(BluetoothPairingCoordinator.MODE_DISPLAY_PIN,
                BluetoothPairingCoordinator.overlayModeForVariant(5));
        assertEquals(BluetoothPairingCoordinator.MODE_CONSENT,
                BluetoothPairingCoordinator.overlayModeForVariant(6));
        assertEquals(BluetoothPairingCoordinator.MODE_PIN,
                BluetoothPairingCoordinator.overlayModeForVariant(7));
    }

    @Test
    public void testDisplayPinPreservesFourDigitLeadingZero() {
        assertEquals("0123", BluetoothPairingCoordinator.formatDisplayPin(123));
        assertEquals("0000", BluetoothPairingCoordinator.formatDisplayPin(0));
        assertEquals("1234", BluetoothPairingCoordinator.formatDisplayPin(1234));
    }

    @Test
    public void testNegotiationWindowIs15s() {
        assertEquals(15_000L, BluetoothPairingCoordinator.NEGOTIATION_WINDOW_MS);
    }

    @Test
    public void testSelfCheck() {
        BluetoothPairingCoordinator.selfCheck();
        SolarWheelKeyboardController.selfCheck();
    }

    @Test
    public void testSessionClear() {
        BluetoothPairingCoordinator.clearSession();
        assertFalse(BluetoothPairingCoordinator.onPairingRequest(
                null, null, BluetoothPairingCoordinator.PAIRING_VARIANT_PIN, 0, false));
    }

    @Test
    public void testPendingPinClearedOnBondedNull() {
        BluetoothPairingCoordinator.clearSession();
        BluetoothPairingCoordinator.onBonded(null);
        assertFalse(BluetoothPairingCoordinator.isPendingPinNegotiation("AA:BB:CC:DD:EE:FF"));
    }
}
