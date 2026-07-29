package com.solar.launcher;

import android.bluetooth.BluetoothDevice;
import com.solar.input.policy.BluetoothPairingVariantPolicy;
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
                        BluetoothDevice.PAIRING_VARIANT_PIN));
        assertEquals(BluetoothPairingCoordinator.MODE_PASSKEY_ENTRY,
                BluetoothPairingCoordinator.overlayModeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_PASSKEY));
        assertEquals(BluetoothPairingCoordinator.MODE_PASSKEY_CONFIRM,
                BluetoothPairingCoordinator.overlayModeForVariant(
                        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION));
        assertEquals(BluetoothPairingCoordinator.MODE_CONSENT,
                BluetoothPairingCoordinator.overlayModeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_CONSENT));
        assertEquals(BluetoothPairingCoordinator.MODE_PASSKEY_DISPLAY,
                BluetoothPairingCoordinator.overlayModeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_DISPLAY_PASSKEY));
        assertEquals(BluetoothPairingCoordinator.MODE_PIN_DISPLAY,
                BluetoothPairingCoordinator.overlayModeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_DISPLAY_PIN));
        assertEquals(BluetoothPairingCoordinator.MODE_OOB_CONSENT,
                BluetoothPairingCoordinator.overlayModeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_OOB_CONSENT));
        assertEquals(BluetoothPairingVariantPolicy.MODE_NONE,
                BluetoothPairingCoordinator.overlayModeForVariant(99));
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
                null, null, BluetoothDevice.PAIRING_VARIANT_PIN, 0, false));
    }

    @Test
    public void testPendingPinClearedOnBondedNull() {
        BluetoothPairingCoordinator.clearSession();
        BluetoothPairingCoordinator.onBonded(null);
    }

    @Test
    public void testPairingKeyFormattingAndValidation() {
        assertEquals("0042", BluetoothPairingCoordinator.formatDisplayPin(42));
        assertEquals(42, BluetoothPairingCoordinator.parsePasskey("000042"));
        assertEquals(999999, BluetoothPairingCoordinator.parsePasskey("999999"));
        assertEquals(-1, BluetoothPairingCoordinator.parsePasskey(""));
        assertEquals(-1, BluetoothPairingCoordinator.parsePasskey("1234567"));
        assertEquals(-1, BluetoothPairingCoordinator.parsePasskey("12A4"));
    }
}
