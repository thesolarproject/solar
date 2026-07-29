package com.solar.launcher;

import com.solar.input.policy.BluetoothPairingVariantPolicy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BluetoothPairingVariantPolicyTest {

    @Test
    public void mapsEveryAndroidPairingVariant() {
        assertEquals(BluetoothPairingVariantPolicy.MODE_PIN_ENTRY,
                BluetoothPairingVariantPolicy.modeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_PIN));
        assertEquals(BluetoothPairingVariantPolicy.MODE_PASSKEY_ENTRY,
                BluetoothPairingVariantPolicy.modeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_PASSKEY));
        assertEquals(BluetoothPairingVariantPolicy.MODE_PASSKEY_CONFIRM,
                BluetoothPairingVariantPolicy.modeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_PASSKEY_CONFIRMATION));
        assertEquals(BluetoothPairingVariantPolicy.MODE_CONSENT,
                BluetoothPairingVariantPolicy.modeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_CONSENT));
        assertEquals(BluetoothPairingVariantPolicy.MODE_PASSKEY_DISPLAY,
                BluetoothPairingVariantPolicy.modeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_DISPLAY_PASSKEY));
        assertEquals(BluetoothPairingVariantPolicy.MODE_PIN_DISPLAY,
                BluetoothPairingVariantPolicy.modeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_DISPLAY_PIN));
        assertEquals(BluetoothPairingVariantPolicy.MODE_OOB_CONSENT,
                BluetoothPairingVariantPolicy.modeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_OOB_CONSENT));
        assertEquals(BluetoothPairingVariantPolicy.MODE_PIN_ENTRY,
                BluetoothPairingVariantPolicy.modeForVariant(
                        BluetoothPairingVariantPolicy.VARIANT_PIN_16_DIGITS));
        assertEquals(BluetoothPairingVariantPolicy.MODE_NONE,
                BluetoothPairingVariantPolicy.modeForVariant(-1));
        assertEquals(BluetoothPairingVariantPolicy.MODE_NONE,
                BluetoothPairingVariantPolicy.modeForVariant(99));
    }

    @Test
    public void classifiesEntryAndDisplayModes() {
        assertTrue(BluetoothPairingVariantPolicy.isCredentialEntryMode(
                BluetoothPairingVariantPolicy.MODE_PIN_ENTRY));
        assertTrue(BluetoothPairingVariantPolicy.isCredentialEntryMode(
                BluetoothPairingVariantPolicy.MODE_PASSKEY_ENTRY));
        assertFalse(BluetoothPairingVariantPolicy.isCredentialEntryMode(
                BluetoothPairingVariantPolicy.MODE_PASSKEY_CONFIRM));

        assertTrue(BluetoothPairingVariantPolicy.isDisplayOnlyMode(
                BluetoothPairingVariantPolicy.MODE_PASSKEY_DISPLAY));
        assertTrue(BluetoothPairingVariantPolicy.isDisplayOnlyMode(
                BluetoothPairingVariantPolicy.MODE_PIN_DISPLAY));
        assertFalse(BluetoothPairingVariantPolicy.isDisplayOnlyMode(
                BluetoothPairingVariantPolicy.MODE_PIN_ENTRY));
    }
}
