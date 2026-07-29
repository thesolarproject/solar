package com.solar.launcher;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Manifest receiver — PAIRING_REQUEST and bond events for all apps (Settings, Rockbox, Solar).
 * Sole PAIRING_REQUEST owner; notifies the coordinator when bonding completes.
 */
public class BluetoothAudioRepairReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        BluetoothDevice device = null;
        try {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Exception ignored) {}
        BluetoothDiagnostics.recordEvent(context, intent);
        if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT,
                    BluetoothDevice.ERROR);
            int passkey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, 0);
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
        if (isExplicitConnectionCompletion(intent)) {
            BluetoothAudioRepair.requestRepair(context, device);
        } else {
            BluetoothAudioRepair.requestAutoRepair(context, device);
        }
    }

    static boolean isExplicitConnectionCompletion(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            return isExplicitConnectionCompletion(action,
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.ERROR));
        }
        if (android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            return isExplicitConnectionCompletion(action,
                    intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE,
                            android.bluetooth.BluetoothProfile.STATE_DISCONNECTED));
        }
        return false;
    }

    static boolean isExplicitConnectionCompletion(String action, int state) {
        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            return state == BluetoothDevice.BOND_BONDED;
        }
        if (android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            return state == android.bluetooth.BluetoothProfile.STATE_CONNECTING
                    || state == android.bluetooth.BluetoothProfile.STATE_CONNECTED;
        }
        return false;
    }
}
