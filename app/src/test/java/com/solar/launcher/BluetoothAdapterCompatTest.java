package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-08-05 — Gating tests for the KitKat BluetoothAdapter null-mService repair.
 * The reflection rebind itself needs the Android framework, so the pure-JVM surface tested
 * here is the SDK gating: the repair must only ever run on the poisoned API band (18-22).
 */
public class BluetoothAdapterCompatTest {

    @Test
    public void repairOnlyOnPoisonedSdkBand() {
        // Y1 / API 17 binds the bluetooth service directly — never poisoned, never touched.
        assertFalse(BluetoothAdapterCompat.shouldRepair(17));
        // 4.3 / 4.4 (Y2) / early Lollipop carry the null-mService singleton bug.
        assertTrue(BluetoothAdapterCompat.shouldRepair(18));
        assertTrue(BluetoothAdapterCompat.shouldRepair(19));
        assertTrue(BluetoothAdapterCompat.shouldRepair(20));
        assertTrue(BluetoothAdapterCompat.shouldRepair(21));
        assertTrue(BluetoothAdapterCompat.shouldRepair(22));
        // Marshmallow+ self-heal via BluetoothServiceConnection — never touched.
        assertFalse(BluetoothAdapterCompat.shouldRepair(23));
        assertFalse(BluetoothAdapterCompat.shouldRepair(35));
    }

    @Test
    public void repairIsBoundaryCorrect() {
        assertFalse(BluetoothAdapterCompat.shouldRepair(0));
        assertFalse(BluetoothAdapterCompat.shouldRepair(1));
        assertFalse(BluetoothAdapterCompat.shouldRepair(16));
    }
}
