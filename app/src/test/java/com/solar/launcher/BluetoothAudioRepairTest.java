package com.solar.launcher;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class BluetoothAudioRepairTest {

    @Test
    public void testNormalizePairingPin() {
        assertEquals("0000", BluetoothAudioRepair.normalizePairingPin(null));
        assertEquals("0000", BluetoothAudioRepair.normalizePairingPin("   "));
        assertEquals("1234", BluetoothAudioRepair.normalizePairingPin(" 1234 "));
        assertEquals("1234567890123456", BluetoothAudioRepair.normalizePairingPin("12345678901234567890"));
    }

    @Test
    public void testIsBondAuthFailure() {
        int state = BluetoothDevice.BOND_NONE;
        int prev = BluetoothDevice.BOND_BONDING;

        assertTrue(BluetoothAudioRepair.isBondAuthFailure(state, prev, BluetoothAudioRepair.BOND_REASON_AUTH_FAILED));
        assertTrue(BluetoothAudioRepair.isBondAuthFailure(state, prev, BluetoothAudioRepair.BOND_REASON_AUTH_REJECTED));
        assertTrue(BluetoothAudioRepair.isBondAuthFailure(state, prev, BluetoothAudioRepair.BOND_REASON_AUTH_TIMEOUT));
        assertFalse(BluetoothAudioRepair.isBondAuthFailure(state, prev, 0));
        assertFalse(BluetoothAudioRepair.isBondAuthFailure(BluetoothDevice.BOND_BONDED, prev, BluetoothAudioRepair.BOND_REASON_AUTH_FAILED));
    }

    /**
     * 2026-07-14 — A2DP route must keep the user's level (was quiet-floor at 75% max).
     * Reversal: restore Math.max(cur, (max*3)/4) in forceA2dpRoute + delete this assert.
     */
    @Test
    public void a2dpRoutePreservesQuietVolumeIndex() {
        assertEquals(2, BluetoothAudioRepair.preserveUserVolumeIndex(2, 15));
        assertEquals(0, BluetoothAudioRepair.preserveUserVolumeIndex(0, 15));
        assertEquals(14, BluetoothAudioRepair.preserveUserVolumeIndex(14, 15));
        // Old floor would have returned 11 for cur=2/max=15 — must not return that.
        assertTrue(BluetoothAudioRepair.preserveUserVolumeIndex(2, 15) < Math.max(1, (15 * 3) / 4));
    }

    @Test
    public void repairRetriesUseBoundedBackoff() {
        assertEquals(500L, BluetoothAudioRepairService.retryDelayMs(1));
        assertEquals(1500L, BluetoothAudioRepairService.retryDelayMs(2));
        assertEquals(2500L, BluetoothAudioRepairService.retryDelayMs(3));
        assertEquals(4000L, BluetoothAudioRepairService.retryDelayMs(4));
        assertEquals(6000L, BluetoothAudioRepairService.retryDelayMs(5));
        assertEquals(-1L, BluetoothAudioRepairService.retryDelayMs(6));
    }

    @Test
    public void autoReconnectDefaultsOnAndHonorsUserChoice() {
        assertTrue(BluetoothAudioRepair.autoReconnectEnabledForValue(null));
        assertTrue(BluetoothAudioRepair.autoReconnectEnabledForValue(Boolean.TRUE));
        assertFalse(BluetoothAudioRepair.autoReconnectEnabledForValue(Boolean.FALSE));
    }

    @Test
    public void explicitPairAndA2dpCompletionBypassAutoReconnectSetting() {
        assertTrue(BluetoothAudioRepairReceiver.isExplicitConnectionCompletion(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothDevice.BOND_BONDED));
        assertTrue(BluetoothAudioRepairReceiver.isExplicitConnectionCompletion(
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothProfile.STATE_CONNECTING));
        assertTrue(BluetoothAudioRepairReceiver.isExplicitConnectionCompletion(
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothProfile.STATE_CONNECTED));
        assertFalse(BluetoothAudioRepairReceiver.isExplicitConnectionCompletion(
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTED));
    }
}
