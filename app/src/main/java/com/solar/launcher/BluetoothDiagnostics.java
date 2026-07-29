package com.solar.launcher;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;

import java.util.Locale;
import java.util.Set;

/** Persistent, text-only Bluetooth health state for the Y1 diagnostics screen. */
public final class BluetoothDiagnostics {

    private static final String PREFS = BluetoothAudioRepair.PREFS;
    private static final String KEY_LAST_DEVICE_ADDRESS = "bt_diag_last_device_address";
    private static final String KEY_LAST_DEVICE_NAME = "bt_diag_last_device_name";
    private static final String KEY_LAST_EVENT = "bt_diag_last_event";
    private static final String KEY_LAST_EVENT_AT = "bt_diag_last_event_at";
    private static final String KEY_LAST_DISCONNECT = "bt_diag_last_disconnect";
    private static final String KEY_LAST_DISCONNECT_AT = "bt_diag_last_disconnect_at";
    private static final String KEY_RECONNECT_ATTEMPTS = "bt_diag_reconnect_attempts";
    private static final String KEY_RECONNECT_RESULT = "bt_diag_reconnect_result";
    private static final String EXTRA_REASON = "android.bluetooth.device.extra.REASON";

    public static final class Snapshot {
        public final boolean adapterPresent;
        public final boolean adapterEnabled;
        public final String adapterState;
        public final String deviceName;
        public final String lastAddress;
        public final String bondState;
        public final String a2dpState;
        public final String activeRoute;
        public final String supportedProfiles;
        public final String codec;
        public final String lastDisconnect;
        public final String reconnectSummary;
        public final String lastEvent;

        Snapshot(boolean adapterPresent, boolean adapterEnabled, String adapterState,
                String deviceName, String lastAddress, String bondState, String a2dpState,
                String activeRoute, String supportedProfiles, String codec,
                String lastDisconnect, String reconnectSummary, String lastEvent) {
            this.adapterPresent = adapterPresent;
            this.adapterEnabled = adapterEnabled;
            this.adapterState = fallback(adapterState);
            this.deviceName = fallback(deviceName);
            this.lastAddress = lastAddress != null ? lastAddress : "";
            this.bondState = fallback(bondState);
            this.a2dpState = fallback(a2dpState);
            this.activeRoute = fallback(activeRoute);
            this.supportedProfiles = fallback(supportedProfiles);
            this.codec = fallback(codec);
            this.lastDisconnect = fallback(lastDisconnect);
            this.reconnectSummary = fallback(reconnectSummary);
            this.lastEvent = fallback(lastEvent);
        }
    }

    private BluetoothDiagnostics() {}

    /** Observe public stack broadcasts; no device discovery or connection work is done here. */
    public static void recordEvent(Context rawContext, Intent intent) {
        if (rawContext == null || intent == null) return;
        Context context = rawContext.getApplicationContext();
        String action = intent.getAction();
        BluetoothDevice device = null;
        try {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (SecurityException denied) {
            device = null;
        } catch (Exception ignored) {}
        String address = safeAddress(device);
        String name = safeName(device);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor edit = prefs(context).edit();
        if (address != null) edit.putString(KEY_LAST_DEVICE_ADDRESS, address);
        if (name != null) edit.putString(KEY_LAST_DEVICE_NAME, name);

        if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            int previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                edit.putString(KEY_LAST_EVENT, "A2DP connected")
                        .putString(KEY_RECONNECT_RESULT, "Connected")
                        .putLong(KEY_LAST_EVENT_AT, now);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                int reason = intent.getIntExtra(EXTRA_REASON, -1);
                String disconnect = disconnectReason(action, state, previous, reason);
                edit.putString(KEY_LAST_EVENT, "A2DP disconnected")
                        .putLong(KEY_LAST_EVENT_AT, now)
                        .putString(KEY_LAST_DISCONNECT, disconnect)
                        .putLong(KEY_LAST_DISCONNECT_AT, now);
            } else {
                edit.putString(KEY_LAST_EVENT, profileStateLabel(state))
                        .putLong(KEY_LAST_EVENT_AT, now);
            }
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            int reason = intent.getIntExtra(EXTRA_REASON, -1);
            String disconnect = reason >= 0
                    ? "Radio link lost (reason " + reason + ")" : "Radio link lost";
            edit.putString(KEY_LAST_EVENT, "ACL disconnected")
                    .putLong(KEY_LAST_EVENT_AT, now)
                    .putString(KEY_LAST_DISCONNECT, disconnect)
                    .putLong(KEY_LAST_DISCONNECT_AT, now);
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            edit.putString(KEY_LAST_EVENT, "Radio link connected")
                    .putLong(KEY_LAST_EVENT_AT, now);
        } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR);
            int reason = intent.getIntExtra(EXTRA_REASON, -1);
            String event = bondStateLabel(state);
            if (state == BluetoothDevice.BOND_NONE && reason >= 0) {
                event += " (reason " + reason + ")";
            }
            edit.putString(KEY_LAST_EVENT, event).putLong(KEY_LAST_EVENT_AT, now);
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR);
            edit.putString(KEY_LAST_EVENT, "Adapter " + adapterStateLabel(state))
                    .putLong(KEY_LAST_EVENT_AT, now);
        }
        edit.apply();
    }

    static void recordReconnectAttempt(Context context, BluetoothDevice device,
            int attemptNumber, boolean accepted, boolean connected) {
        if (context == null) return;
        String address = safeAddress(device);
        String name = safeName(device);
        String result = connected ? "Connected"
                : (accepted ? "Waiting for audio route" : "Request rejected");
        SharedPreferences.Editor edit = prefs(context).edit()
                .putInt(KEY_RECONNECT_ATTEMPTS, Math.max(1, attemptNumber))
                .putString(KEY_RECONNECT_RESULT, result)
                .putString(KEY_LAST_EVENT, "Reconnect " + result.toLowerCase(Locale.US))
                .putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis());
        if (address != null) edit.putString(KEY_LAST_DEVICE_ADDRESS, address);
        if (name != null) edit.putString(KEY_LAST_DEVICE_NAME, name);
        edit.apply();
    }

    static void recordProfileFailure(Context context) {
        if (context == null) return;
        prefs(context).edit()
                .putString(KEY_LAST_EVENT, "A2DP service disconnected; reacquiring")
                .putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis())
                .apply();
    }

    static void recordConnectionTimeout(Context context, String address) {
        if (context == null) return;
        SharedPreferences.Editor edit = prefs(context).edit()
                .putString(KEY_RECONNECT_RESULT, "Timed out")
                .putString(KEY_LAST_EVENT, "Connection timed out")
                .putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis());
        if (address != null) edit.putString(KEY_LAST_DEVICE_ADDRESS, address);
        edit.apply();
    }

    static void clearForgottenDevice(Context context, String address) {
        if (context == null || address == null) return;
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor edit = p.edit();
        if (address.equals(p.getString(KEY_LAST_DEVICE_ADDRESS, ""))) {
            edit.remove(KEY_LAST_DEVICE_ADDRESS)
                    .remove(KEY_LAST_DEVICE_NAME)
                    .remove(KEY_RECONNECT_ATTEMPTS)
                    .remove(KEY_RECONNECT_RESULT);
        }
        edit.putString(KEY_LAST_EVENT, "Bond removed")
                .putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis())
                .apply();
    }

    @SuppressWarnings("deprecation")
    public static Snapshot capture(Context rawContext, String connectedAddress) {
        Context context = rawContext != null ? rawContext.getApplicationContext() : null;
        SharedPreferences p = context != null ? prefs(context) : null;
        BluetoothAdapter adapter = null;
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
        } catch (SecurityException denied) {
            adapter = null;
        } catch (Exception ignored) {}
        boolean present = adapter != null;
        boolean enabled = false;
        int adapterState = BluetoothAdapter.ERROR;
        int a2dpState = BluetoothProfile.STATE_DISCONNECTED;
        try {
            if (adapter != null) {
                enabled = adapter.isEnabled();
                adapterState = adapter.getState();
                a2dpState = adapter.getProfileConnectionState(BluetoothProfile.A2DP);
            }
        } catch (SecurityException denied) {
            enabled = false;
            adapterState = BluetoothAdapter.ERROR;
            a2dpState = BluetoothProfile.STATE_DISCONNECTED;
        } catch (Exception ignored) {}

        String lastAddress = connectedAddress;
        if ((lastAddress == null || lastAddress.isEmpty()) && p != null) {
            lastAddress = p.getString(KEY_LAST_DEVICE_ADDRESS,
                    p.getString(BluetoothAudioRepair.PREF_LAST_BT_AUDIO, ""));
        }
        BluetoothDevice device = findBondedDevice(adapter, lastAddress);
        String name = safeName(device);
        if ((name == null || name.isEmpty()) && p != null) {
            name = p.getString(KEY_LAST_DEVICE_NAME, "");
        }
        String bond = device != null ? bondStateLabel(safeBondState(device)) : "Unknown";

        AudioManager audio = context != null
                ? (AudioManager) context.getSystemService(Context.AUDIO_SERVICE) : null;
        boolean a2dpRoute = false;
        try {
            a2dpRoute = audio != null && audio.isBluetoothA2dpOn();
        } catch (Exception ignored) {}
        String codec = negotiatedCodec(audio);

        String lastDisconnect = p != null
                ? p.getString(KEY_LAST_DISCONNECT, "None recorded") : "None recorded";
        long disconnectAt = p != null ? p.getLong(KEY_LAST_DISCONNECT_AT, 0L) : 0L;
        if (disconnectAt > 0L) {
            lastDisconnect += " · " + ageLabel(System.currentTimeMillis(), disconnectAt);
        }
        int attempts = p != null ? p.getInt(KEY_RECONNECT_ATTEMPTS, 0) : 0;
        String reconnectResult = p != null
                ? p.getString(KEY_RECONNECT_RESULT, "No attempt recorded")
                : "No attempt recorded";
        String reconnect = attempts > 0
                ? attempts + " · " + reconnectResult : reconnectResult;
        String lastEvent = p != null ? p.getString(KEY_LAST_EVENT, "None recorded")
                : "None recorded";
        long lastEventAt = p != null ? p.getLong(KEY_LAST_EVENT_AT, 0L) : 0L;
        if (lastEventAt > 0L) {
            lastEvent += " · " + ageLabel(System.currentTimeMillis(), lastEventAt);
        }

        return new Snapshot(present, enabled,
                present ? adapterStateLabel(adapterState) : "Not present",
                name, lastAddress, bond, profileStateLabel(a2dpState),
                a2dpRoute ? "Bluetooth A2DP" : "Not active",
                "A2DP output · AVRCP media control",
                codec, lastDisconnect, reconnect, lastEvent);
    }

    static String adapterStateLabel(int state) {
        if (state == BluetoothAdapter.STATE_ON) return "On";
        if (state == BluetoothAdapter.STATE_TURNING_ON) return "Turning on";
        if (state == BluetoothAdapter.STATE_TURNING_OFF) return "Turning off";
        if (state == BluetoothAdapter.STATE_OFF) return "Off";
        return "Unknown";
    }

    static String profileStateLabel(int state) {
        if (state == BluetoothProfile.STATE_CONNECTED) return "A2DP connected";
        if (state == BluetoothProfile.STATE_CONNECTING) return "A2DP connecting";
        if (state == BluetoothProfile.STATE_DISCONNECTING) return "A2DP disconnecting";
        if (state == BluetoothProfile.STATE_DISCONNECTED) return "A2DP disconnected";
        return "A2DP unknown";
    }

    static String bondStateLabel(int state) {
        if (state == BluetoothDevice.BOND_BONDED) return "Bonded";
        if (state == BluetoothDevice.BOND_BONDING) return "Pairing";
        if (state == BluetoothDevice.BOND_NONE) return "Not bonded";
        return "Unknown";
    }

    static String disconnectReason(String action, int state, int previous, int reason) {
        if (reason >= 0) return "A2DP disconnected (reason " + reason + ")";
        if (previous == BluetoothProfile.STATE_CONNECTING) {
            return "Connection attempt ended before audio connected";
        }
        if (previous == BluetoothProfile.STATE_CONNECTED) {
            return "A2DP audio link disconnected";
        }
        return "A2DP disconnected";
    }

    static String negotiatedCodec(AudioManager audio) {
        if (audio == null) return "Not exposed by Android";
        for (String key : new String[] {"A2dpCodec", "bt_a2dp_codec", "a2dp_codec"}) {
            try {
                String raw = audio.getParameters(key);
                String value = sanitizeCodecParameter(raw, key);
                if (!value.isEmpty()) return value;
            } catch (Exception ignored) {}
        }
        return "Not exposed by Android";
    }

    static String sanitizeCodecParameter(String raw, String key) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty() || value.equals(key + "=")) return "";
        int equals = value.indexOf('=');
        if (equals >= 0 && equals + 1 < value.length()) {
            value = value.substring(equals + 1).trim();
        }
        if (value.isEmpty() || "null".equalsIgnoreCase(value)
                || "unknown".equalsIgnoreCase(value)) return "";
        return value.length() > 40 ? value.substring(0, 40) : value;
    }

    static String ageLabel(long nowMs, long eventMs) {
        long seconds = Math.max(0L, (nowMs - eventMs) / 1000L);
        if (seconds < 60L) return seconds + "s ago";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + "m ago";
        long hours = minutes / 60L;
        if (hours < 48L) return hours + "h ago";
        return (hours / 24L) + "d ago";
    }

    private static BluetoothDevice findBondedDevice(BluetoothAdapter adapter, String address) {
        if (adapter == null || address == null || address.isEmpty()) return null;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return null;
            for (BluetoothDevice device : bonded) {
                if (device != null && address.equals(safeAddress(device))) return device;
            }
        } catch (SecurityException denied) {
            return null;
        } catch (Exception ignored) {}
        return null;
    }

    private static int safeBondState(BluetoothDevice device) {
        try {
            return device != null ? device.getBondState() : BluetoothDevice.ERROR;
        } catch (SecurityException denied) {
            return BluetoothDevice.ERROR;
        } catch (Exception ignored) {
            return BluetoothDevice.ERROR;
        }
    }

    private static String safeAddress(BluetoothDevice device) {
        try {
            return device != null ? device.getAddress() : null;
        } catch (SecurityException denied) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeName(BluetoothDevice device) {
        try {
            if (device == null) return null;
            String name = device.getName();
            return name != null && !name.isEmpty() ? name : device.getAddress();
        } catch (SecurityException denied) {
            return safeAddress(device);
        } catch (Exception ignored) {
            return safeAddress(device);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String fallback(String value) {
        return value != null && !value.isEmpty() ? value : "—";
    }
}
