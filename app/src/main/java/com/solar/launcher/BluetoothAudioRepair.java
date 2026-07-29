package com.solar.launcher;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.util.Set;

/** MTK/Y1 A2DP repair shim: turns ACL-only headset links into routed audio links. */
final class BluetoothAudioRepair {
    static final String TAG = "SolarBtRepair";
    static final String PREFS = "SOLAR_SETTINGS";
    static final String PREF_LAST_BT_AUDIO = "last_bt_audio_address";
    static final String PREF_PAIRING_PIN = "bluetooth_pairing_pin";
    static final String PREF_PAIRING_PIN_PREFIX = "bluetooth_pairing_pin_";
    static final String PREF_AUTO_RECONNECT = "bluetooth_auto_reconnect";
    static final String EXTRA_ADDRESS = "com.solar.launcher.extra.BT_ADDRESS";
    static final String EXTRA_PAIR_PIN_PROMPT = "com.solar.launcher.extra.BT_PAIR_PIN_PROMPT";
    static final String EXTRA_PAIR_PIN_ADDRESS = "com.solar.launcher.extra.BT_PAIR_PIN_ADDRESS";
    static final String EXTRA_PAIR_PIN_NAME = "com.solar.launcher.extra.BT_PAIR_PIN_NAME";
    static final String EXTRA_BOND_REASON = "android.bluetooth.device.extra.REASON";
    static final int BOND_REASON_AUTH_FAILED = 1;
    static final int BOND_REASON_AUTH_REJECTED = 2;
    static final int BOND_REASON_AUTH_TIMEOUT = 6;

    private BluetoothAudioRepair() {}

    static void requestRepair(Context context, BluetoothDevice device) {
        if (context == null) return;
        Intent i = new Intent(context, BluetoothAudioRepairService.class);
        if (device != null) {
            i.putExtra(EXTRA_ADDRESS, device.getAddress());
        }
        context.startService(i);
    }

    static void requestAutoRepair(Context context, BluetoothDevice device) {
        if (!isAutoReconnectEnabled(context)) return;
        requestRepair(context, device);
    }

    static boolean isAutoReconnectEnabled(Context context) {
        if (context == null) return true;
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_AUTO_RECONNECT, true);
    }

    static boolean autoReconnectEnabledForValue(Boolean storedValue) {
        return storedValue == null || storedValue;
    }

    static BluetoothDevice resolveDevice(Context context, Intent intent) {
        BluetoothDevice fromIntent = null;
        if (intent != null) {
            try {
                fromIntent = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            } catch (Exception ignored) {}
            if (fromIntent != null) return fromIntent;
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            BluetoothDevice fromAddress = deviceForAddress(address);
            if (fromAddress != null) return fromAddress;
        }
        return lastRememberedDevice(context);
    }

    static BluetoothDevice lastRememberedDevice(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return deviceForAddress(prefs.getString(PREF_LAST_BT_AUDIO, null));
    }

    static BluetoothDevice deviceForAddress(String address) {
        if (address == null || address.length() == 0) return null;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return null;
            return adapter.getRemoteDevice(address);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String normalizePairingPin(String pin) {
        String cleaned = pin != null ? pin.trim() : "";
        if (cleaned.length() == 0) return "0000";
        if (cleaned.length() > 16) return cleaned.substring(0, 16);
        return cleaned;
    }

    static String pairingPinForAddress(Context context, String address) {
        if (context == null) return "0000";
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String fallback = prefs.getString(PREF_PAIRING_PIN, "0000");
        if (address == null || address.length() == 0) return normalizePairingPin(fallback);
        return normalizePairingPin(prefs.getString(PREF_PAIRING_PIN_PREFIX + address, fallback));
    }

    static String pairingPinForDevice(Context context, BluetoothDevice device) {
        String address = null;
        try {
            address = device != null ? device.getAddress() : null;
        } catch (Exception ignored) {}
        return pairingPinForAddress(context, address);
    }

    static void savePairingPin(Context context, String address, String pin) {
        if (context == null) return;
        String cleaned = normalizePairingPin(pin);
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (address != null && address.length() > 0) {
            edit.putString(PREF_PAIRING_PIN_PREFIX + address, cleaned);
        } else {
            edit.putString(PREF_PAIRING_PIN, cleaned);
        }
        edit.apply();
    }

    static boolean isBondAuthFailure(Intent intent) {
        if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
            return false;
        }
        int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
        int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
        int reason = intent.getIntExtra(EXTRA_BOND_REASON, Integer.MIN_VALUE);
        return isBondAuthFailure(state, previous, reason);
    }

    static boolean isBondAuthFailure(int state, int previous, int reason) {
        return state == BluetoothDevice.BOND_NONE
                && previous == BluetoothDevice.BOND_BONDING
                && (reason == BOND_REASON_AUTH_FAILED || reason == BOND_REASON_AUTH_REJECTED || reason == BOND_REASON_AUTH_TIMEOUT);
    }

    static void launchPairPinPrompt(Context context, BluetoothDevice device) {
        if (context == null || device == null) return;
        BluetoothPairingCoordinator.onAuthFailure(context, device);
    }

    /** @deprecated Use {@link BluetoothPairingCoordinator#onPairingRequest} — kept for legacy callers. */
    @Deprecated
    @SuppressLint("MissingPermission")
    static boolean handlePairingRequest(Context context, BluetoothDevice device, int variant) {
        return BluetoothPairingCoordinator.onPairingRequest(context, device, variant, 0, false);
    }

    /** Reflection helper shared with coordinator. */
    static byte[] bluetoothPinBytes(String pin) {
        try {
            Method convert = BluetoothDevice.class.getMethod("convertPinToBytes", String.class);
            return (byte[]) convert.invoke(null, normalizePairingPin(pin));
        } catch (Exception e) {
            return normalizePairingPin(pin).getBytes();
        }
    }

    @SuppressLint("MissingPermission")
    static void rememberLastAudioDevice(Context context, BluetoothDevice device) {
        if (context == null || device == null || !isLikelyAudioSink(device)) return;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(PREF_LAST_BT_AUDIO, device.getAddress())
                    .apply();
        } catch (Exception ignored) {}
    }

    /** Forget per-device PIN/reconnect state only after Android accepts bond removal. */
    static void clearRememberedDevice(Context context, String address) {
        if (context == null || address == null || address.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit()
                .remove(PREF_PAIRING_PIN_PREFIX + address);
        if (address.equals(prefs.getString(PREF_LAST_BT_AUDIO, ""))) {
            edit.remove(PREF_LAST_BT_AUDIO);
        }
        edit.apply();
        BluetoothDiagnostics.clearForgottenDevice(context, address);
    }

    static boolean shouldRepairEvent(Intent intent) {
        if (intent == null) return true;
        String action = intent.getAction();
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            return state == BluetoothAdapter.STATE_ON;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) return true;
        BluetoothDevice device = null;
        try {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Exception ignored) {}
        if (device != null && !isLikelyAudioSink(device)) return false;
        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            return intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    == BluetoothDevice.BOND_BONDED;
        }
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) return true;
        if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            return state == BluetoothProfile.STATE_CONNECTING
                    || state == BluetoothProfile.STATE_CONNECTED;
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    static boolean isLikelyAudioSink(BluetoothDevice device) {
        if (device == null) return false;
        try {
            BluetoothClass cls = device.getBluetoothClass();
            if (cls == null) return true;
            return cls.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
        } catch (Exception ignored) {
            return true;
        }
    }

    @SuppressLint("MissingPermission")
    static void cancelDiscovery() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    static boolean connectA2dp(Context context, BluetoothA2dp a2dp,
            BluetoothDevice device, boolean exclusive) {
        if (context == null || a2dp == null || device == null) return false;
        cancelDiscovery();
        rememberLastAudioDevice(context, device);
        if (exclusive) disconnectOtherSinks(a2dp, device);
        setA2dpPriority(context, a2dp, device, 1000);
        try {
            int state = a2dp.getConnectionState(device);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                forceA2dpRoute(context);
                return true;
            }
        } catch (Exception ignored) {}
        try {
            Method connect = a2dp.getClass().getMethod("connect", BluetoothDevice.class);
            Object ok = connect.invoke(a2dp, device);
            boolean accepted = !(ok instanceof Boolean) || (Boolean) ok;
            Log.i(TAG, "connectA2dp accepted=" + accepted + " address=" + device.getAddress());
            forceA2dpRoute(context);
            return accepted;
        } catch (Exception e) {
            Log.w(TAG, "connectA2dp failed " + device.getAddress(), e);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    static boolean isA2dpConnected(BluetoothA2dp a2dp, BluetoothDevice device) {
        if (a2dp == null || device == null) return false;
        try {
            return a2dp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static void disconnectOtherSinks(BluetoothA2dp a2dp, BluetoothDevice target) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return;
            Method disconnect = a2dp.getClass().getMethod("disconnect", BluetoothDevice.class);
            for (BluetoothDevice other : bonded) {
                if (other == null || target.getAddress().equals(other.getAddress())) continue;
                try {
                    if (a2dp.getConnectionState(other) == BluetoothProfile.STATE_CONNECTED) {
                        disconnect.invoke(a2dp, other);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    private static void setA2dpPriority(Context context, BluetoothA2dp a2dp,
            BluetoothDevice device, int priority) {
        try {
            Method setPriority = a2dp.getClass().getMethod("setPriority",
                    BluetoothDevice.class, int.class);
            setPriority.invoke(a2dp, device, priority);
        } catch (Exception ignored) {}
        String key = "bluetooth_a2dp_sink_priority_" + device.getAddress();
        if (!putGlobalIntPrivileged(key, priority)) {
            try {
                if (Build.VERSION.SDK_INT >= 17) {
                    Settings.Global.putInt(context.getContentResolver(), key, priority);
                }
            } catch (Exception ignored) {}
            try {
                Settings.Secure.putInt(context.getContentResolver(), key, priority);
            } catch (Exception ignored) {}
            try {
                Settings.System.putInt(context.getContentResolver(), key, priority);
            } catch (Exception ignored) {}
        }
    }

    static void forceA2dpRoute(Context context) {
        AudioManager am = context != null
                ? (AudioManager) context.getSystemService(Context.AUDIO_SERVICE) : null;
        forceA2dpRoute(context, am);
    }

    /**
     * 2026-07-14 — Identity for STREAM_MUSIC during A2DP route; never raise the user's level.
     * Was: Math.max(cur, (max*3)/4) floor in forceA2dpRoute. Reversal: return that floor again.
     */
    static int preserveUserVolumeIndex(int cur, int max) {
        if (max <= 0) return Math.max(0, cur);
        return Math.max(0, Math.min(cur, max));
    }

    /**
     * 2026-07-14 — Push music out the BT speaker path without touching loudness.
     * Was: also raised STREAM_MUSIC to ≥75% max when quiet — track/A2DP repair felt like
     * volume shooting to full. Reversal: setStreamVolume(..., preserve floor (max*3)/4, 0).
     * 2026-07-19 — Rate-limit HAL knob spray (min 2.5s between calls).
     */
    private static volatile long lastForceA2dpAtMs;

    @SuppressWarnings("deprecation")
    static void forceA2dpRoute(Context context, AudioManager audioManager) {
        if (audioManager == null) return;
        long now = System.currentTimeMillis();
        if (now - lastForceA2dpAtMs < 2500L) return;
        lastForceA2dpAtMs = now;
        try { audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) {}
        try { audioManager.setSpeakerphoneOn(false); } catch (Exception ignored) {}
        try { audioManager.stopBluetoothSco(); } catch (Exception ignored) {}
        try { audioManager.setBluetoothScoOn(false); } catch (Exception ignored) {}
        try { audioManager.setBluetoothA2dpOn(true); } catch (Exception ignored) {}
        try { audioManager.setParameters("A2dpSuspended=false"); } catch (Exception ignored) {}
        try { audioManager.setParameters("A2dpSuspended=0"); } catch (Exception ignored) {}
        try { audioManager.setParameters("bluetooth_enabled=true"); } catch (Exception ignored) {}
        try { audioManager.setParameters("routing=128"); } catch (Exception ignored) {}
        // 2026-07-14 — Route only. Never rewrite user volume (repair runs on connect + track edges).
    }

    private static boolean putGlobalIntPrivileged(String key, int value) {
        if (key == null || key.trim().isEmpty()) return false;
        return runSuCommandSilently("settings put global " + shQuote(key) + " " + value);
    }

    private static boolean runSuCommandSilently(String command) {
        for (String su : new String[] {"/system/xbin/su", "su"}) {
            DataOutputStream os = null;
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(su);
                os = new DataOutputStream(process.getOutputStream());
                os.writeBytes(command + "\n");
                os.writeBytes("exit\n");
                os.flush();
                int exit = process.waitFor();
                if (exit == 0) return true;
            } catch (Throwable ignored) {
            } finally {
                if (os != null) {
                    try { os.close(); } catch (Exception ignored) {}
                }
                if (process != null) process.destroy();
            }
        }
        return false;
    }

    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
