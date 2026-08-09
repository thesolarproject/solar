package com.solar.launcher;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Manifest receiver — PAIRING_REQUEST and bond events for all apps (Settings, Rockbox, Solar).
 * 2026-07-19 — Sole PAIRING_REQUEST owner; notifies coordinator on BOND_BONDED to cancel delayed PIN.
 */
public class BluetoothAudioRepairReceiver extends BroadcastReceiver {
    // These extras were added to the public SDK in API 19. Keep the literal names here so
    // this receiver can still be verified and loaded on the API 17 Y1 runtime.
    private static final String EXTRA_PAIRING_VARIANT =
            "android.bluetooth.device.extra.PAIRING_VARIANT";
    private static final String EXTRA_PAIRING_KEY =
            "android.bluetooth.device.extra.PAIRING_KEY";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        BluetoothDevice device = null;
        try {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Exception ignored) {}
        if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
            int variant = intent.getIntExtra(EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
            int passkey = intent.getIntExtra(EXTRA_PAIRING_KEY, 0);
            if (BluetoothPairingCoordinator.onPairingRequest(context, device, variant, passkey, false)) {
                abortBroadcast();
            }
            return;
        }
        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            if (bondState == BluetoothDevice.BOND_BONDED) {
                BluetoothPairingCoordinator.onBonded(device);
            }
        }
        if (BluetoothAudioRepair.isBondAuthFailure(intent)) {
            BluetoothPairingCoordinator.onAuthFailure(context, device);
            return;
        }
        if (!BluetoothAudioRepair.shouldRepairEvent(intent)) return;
        if (device != null) {
            BluetoothAudioRepair.rememberLastAudioDevice(context, device);
        }
        BluetoothAudioRepair.requestRepair(context, device);
    }
}
