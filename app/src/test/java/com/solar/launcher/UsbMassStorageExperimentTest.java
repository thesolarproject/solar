package com.solar.launcher;

import org.junit.Test;

/**
 * USB mass storage gating — unit checks without a device (2026-07-05).
 * 2026-11-26 — Y2 exports disks again (MTP-only policy retired), so both families allow UMS
 * and the Connections → USB submenu is visible on both. Was: Y2 always blocked.
 */
public class UsbMassStorageExperimentTest {

    @Test
    public void y1AlwaysAllowsUms() {
        if (!UsbMassStorageExperiment.isEnabledForFamily("y1", false)) {
            throw new AssertionError("Y1 must allow UMS regardless of experiment pref");
        }
    }

    @Test
    public void y2AllowsUms() {
        if (!UsbMassStorageExperiment.isEnabledForFamily("y2", false)) {
            throw new AssertionError("Y2 must allow UMS (disk mode is a product path again)");
        }
    }

    @Test
    public void connectionsUsbVisibleOnY2() {
        if (!UsbMassStorageExperiment.connectionsUsbMenuVisibleForFamily("y2", true)) {
            throw new AssertionError("Connections USB submenu must show on Y2");
        }
    }

    @Test
    public void connectionsUsbVisibleOnY1Always() {
        if (!UsbMassStorageExperiment.connectionsUsbMenuVisibleForFamily("y1", false)) {
            throw new AssertionError("Y1 Connections USB submenu must stay visible");
        }
    }
}
