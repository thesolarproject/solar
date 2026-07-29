package com.solar.launcher;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.solar.input.policy.BluetoothPairingVariantPolicy;

import java.lang.reflect.Method;

/**
 * Single owner for Bluetooth pairing UI and API calls (Y1 + Y2).
 *
 * <p>Every Android pairing request that requires user input is routed to Solar's wheel-friendly
 * overlay. Display-only variants still send the acknowledgement Android expects, but Solar never
 * guesses a user PIN/passkey or silently accepts a confirmation request.</p>
 */
public final class BluetoothPairingCoordinator {

    private static final String TAG = "SolarBtPair";

    /** Wheel digit keyboard for legacy PIN entry. */
    public static final int MODE_PIN = BluetoothPairingVariantPolicy.MODE_PIN_ENTRY;
    /** Show a six-digit passkey that must be typed on the remote device. */
    public static final int MODE_PASSKEY_DISPLAY =
            BluetoothPairingVariantPolicy.MODE_PASSKEY_DISPLAY;
    /** Show a passkey and ask the user to confirm it matches. */
    public static final int MODE_PASSKEY_CONFIRM =
            BluetoothPairingVariantPolicy.MODE_PASSKEY_CONFIRM;
    /** Ask the user to accept or reject a Just Works pairing request. */
    public static final int MODE_CONSENT = BluetoothPairingVariantPolicy.MODE_CONSENT;
    /** Wheel digit keyboard for a numeric passkey. */
    public static final int MODE_PASSKEY_ENTRY =
            BluetoothPairingVariantPolicy.MODE_PASSKEY_ENTRY;
    /** Show a four-digit PIN that must be typed on the remote device. */
    public static final int MODE_PIN_DISPLAY =
            BluetoothPairingVariantPolicy.MODE_PIN_DISPLAY;
    /** Ask the user to accept or reject an out-of-band pairing request. */
    public static final int MODE_OOB_CONSENT =
            BluetoothPairingVariantPolicy.MODE_OOB_CONSENT;

    /** Maximum time the Bluetooth list shows an in-flight Connecting state. */
    public static final long NEGOTIATION_WINDOW_MS = 15_000L;

    private static volatile String activeSessionAddress;
    private static volatile long activeSessionAt;

    private BluetoothPairingCoordinator() {}

    /**
     * Entry from the PAIRING_REQUEST broadcast.
     *
     * @return true when Solar owns the request; the receiver should then abort the stock dialog.
     */
    @SuppressLint("MissingPermission")
    public static boolean onPairingRequest(Context context, BluetoothDevice device,
            int variant, int pairingKey, boolean forcePinUi) {
        if (context == null || device == null) return false;
        String address = safeAddress(device);
        if (address == null) return false;
        if (isDuplicateSession(address) && !forcePinUi) {
            Log.d(TAG, "pairing de-dupe " + address);
            return true;
        }
        markSession(address);
        Context app = context.getApplicationContext();
        if (app == null) app = context;

        int mode = overlayModeForVariant(variant);
        switch (mode) {
            case MODE_PIN:
                return showCredentialOverlay(app, address, safeName(device), MODE_PIN,
                        BluetoothAudioRepair.pairingPinForDevice(context, device));
            case MODE_PASSKEY_ENTRY:
                return showCredentialOverlay(app, address, safeName(device),
                        MODE_PASSKEY_ENTRY, null);
            case MODE_PASSKEY_CONFIRM:
            case MODE_CONSENT:
            case MODE_OOB_CONSENT:
                return showReadOrConfirmOverlay(
                        app, address, safeName(device), pairingKey, mode);
            case MODE_PASSKEY_DISPLAY:
                if (!showReadOrConfirmOverlay(
                        app, address, safeName(device), pairingKey, mode)) {
                    return false;
                }
                // Stock Android acknowledges display-passkey as soon as it paints the digits.
                if (!submitConfirmation(device, true)) {
                    clearSession();
                    return false;
                }
                BluetoothAudioRepair.rememberLastAudioDevice(context, device);
                return true;
            case MODE_PIN_DISPLAY:
                if (!showReadOrConfirmOverlay(
                        app, address, safeName(device), pairingKey, mode)) {
                    return false;
                }
                // Stock Android submits the generated four-digit PIN before displaying it.
                if (!submitPin(device, formatDisplayPin(pairingKey))) {
                    clearSession();
                    return false;
                }
                BluetoothAudioRepair.rememberLastAudioDevice(context, device);
                return true;
            default:
                Log.d(TAG, "unknown variant=" + variant + " deferring to stock");
                clearSession();
                return false;
        }
    }

    /** Bond succeeded: release the active prompt de-dupe session. */
    public static void onBonded(BluetoothDevice device) {
        String address = safeAddress(device);
        if (address != null && address.equals(activeSessionAddress)) {
            Log.i(TAG, "bonded; clear pairing session " + address);
            clearSession();
        }
    }

    /**
     * Authentication has already failed and the stack is no longer accepting pairing input.
     *
     * <p>Do not open a credential keyboard here: submitting PIN/passkey data after BOND_NONE
     * cannot affect the dead attempt and makes Just Works headsets look like they require a PIN.
     * Genuine credential prompts are owned by ACTION_PAIRING_REQUEST above.</p>
     */
    @SuppressLint("MissingPermission")
    public static void onAuthFailure(Context context, BluetoothDevice device) {
        String address = safeAddress(device);
        Log.i(TAG, "authentication failed; discard expired pairing session addr=" + address);
        clearSession();
    }

    /**
     * Kept for the Bluetooth-list timeout callback. Prompts are immediate now, so a row timeout
     * must not replace an active credential/confirmation screen with another prompt.
     */
    public static void onNegotiationTimeout(Context context, String address) {
        Log.d(TAG, "connect timeout; pairing prompt was immediate addr=" + address);
    }

    /** User finished PIN entry on the overlay keyboard. */
    @SuppressLint("MissingPermission")
    public static boolean submitPinFromOverlay(Context context, String address, String pin) {
        if (context == null) return false;
        BluetoothDevice device = BluetoothAudioRepair.deviceForAddress(address);
        if (device == null) {
            clearSession();
            return false;
        }
        String cleaned = BluetoothAudioRepair.normalizePairingPin(pin);
        BluetoothAudioRepair.savePairingPin(context, address, cleaned);
        boolean submitted = submitPin(device, cleaned);
        if (submitted) {
            BluetoothAudioRepair.rememberLastAudioDevice(context, device);
            clearSession();
        }
        return submitted;
    }

    /** User finished numeric passkey entry on the overlay keyboard. */
    @SuppressLint("MissingPermission")
    public static boolean submitPasskeyFromOverlay(
            Context context, String address, String value) {
        int passkey = parsePasskey(value);
        if (context == null || passkey < 0) return false;
        BluetoothDevice device = BluetoothAudioRepair.deviceForAddress(address);
        if (device == null) {
            clearSession();
            return false;
        }
        boolean submitted = submitPasskey(device, passkey);
        if (submitted) {
            BluetoothAudioRepair.rememberLastAudioDevice(context, device);
            clearSession();
        }
        return submitted;
    }

    /** Passkey match / Just Works consent row picked on the overlay. */
    @SuppressLint("MissingPermission")
    public static boolean submitConfirmationFromOverlay(
            Context context, String address, boolean accept) {
        if (context == null) return false;
        BluetoothDevice device = BluetoothAudioRepair.deviceForAddress(address);
        if (device == null) {
            clearSession();
            return false;
        }
        boolean submitted = submitConfirmation(device, accept);
        if (submitted && accept) {
            BluetoothAudioRepair.rememberLastAudioDevice(context, device);
        }
        if (submitted) clearSession();
        return submitted;
    }

    /** Out-of-band consent uses a distinct hidden Bluetooth API on the stock stack. */
    @SuppressLint("MissingPermission")
    public static boolean submitOobConsentFromOverlay(
            Context context, String address, boolean accept) {
        if (!accept) {
            cancelPairing(context, address);
            return true;
        }
        if (context == null) return false;
        BluetoothDevice device = BluetoothAudioRepair.deviceForAddress(address);
        if (device == null) {
            clearSession();
            return false;
        }
        boolean submitted = submitRemoteOutOfBandData(device);
        if (submitted) {
            BluetoothAudioRepair.rememberLastAudioDevice(context, device);
            clearSession();
        }
        return submitted;
    }

    /** Back / Cancel on a pairing overlay rejects the bond attempt. */
    @SuppressLint("MissingPermission")
    public static void cancelPairing(Context context, String address) {
        BluetoothDevice device = BluetoothAudioRepair.deviceForAddress(address);
        if (device != null) {
            submitConfirmation(device, false);
            try {
                Method cancel = device.getClass().getMethod("cancelPairingUserInput");
                cancel.invoke(device);
            } catch (Exception ignored) {}
        }
        clearSession();
    }

    /** Informational passkey/PIN display dismissed; the stack already received its acknowledgement. */
    public static void dismissPasskeyDisplaySession() {
        clearSession();
    }

    static String formatPasskey(int passkey) {
        String raw = String.valueOf(Math.max(0, passkey));
        while (raw.length() < 6) raw = "0" + raw;
        if (raw.length() > 6) raw = raw.substring(raw.length() - 6);
        return raw;
    }

    static String formatDisplayPin(int pin) {
        String raw = String.valueOf(Math.max(0, pin));
        while (raw.length() < 4) raw = "0" + raw;
        if (raw.length() > 4) raw = raw.substring(raw.length() - 4);
        return raw;
    }

    /** Returns 0..999999, or -1 when a numeric passkey is not valid. */
    static int parsePasskey(String value) {
        if (value == null) return -1;
        String cleaned = value.trim();
        if (cleaned.length() < 1 || cleaned.length() > 6) return -1;
        for (int i = 0; i < cleaned.length(); i++) {
            if (!Character.isDigit(cleaned.charAt(i))) return -1;
        }
        try {
            int passkey = Integer.parseInt(cleaned);
            return passkey <= 999999 ? passkey : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static int overlayModeForVariant(int variant) {
        return BluetoothPairingVariantPolicy.modeForVariant(variant);
    }

    private static boolean showCredentialOverlay(Context context, String address, String name,
            int mode, String prefill) {
        return startPairingOverlay(context, address, name, 0, mode, prefill);
    }

    private static boolean showReadOrConfirmOverlay(Context context, String address, String name,
            int pairingKey, int mode) {
        return startPairingOverlay(context, address, name, pairingKey, mode, null);
    }

    private static boolean startPairingOverlay(Context context, String address, String name,
            int pairingKey, int mode, String pinPrefill) {
        try {
            Intent service = new Intent(context, SolarOverlayService.class);
            service.setComponent(new ComponentName(context.getPackageName(),
                    SolarOverlayService.class.getName()));
            service.setAction(OverlayTriggers.ACTION_SHOW_OVERLAY_BT_PAIRING);
            service.putExtra(OverlayTriggers.EXTRA_BT_PAIRING_MODE, mode);
            service.putExtra(OverlayTriggers.EXTRA_BT_PAIRING_ADDRESS, address);
            service.putExtra(OverlayTriggers.EXTRA_BT_PAIRING_NAME, name != null ? name : "");
            service.putExtra(OverlayTriggers.EXTRA_BT_PAIRING_PASSKEY, pairingKey);
            if (pinPrefill != null) {
                service.putExtra(OverlayTriggers.EXTRA_BT_PAIRING_PIN_PREFILL, pinPrefill);
            }
            context.startService(service);
            Log.i(TAG, "pairing overlay mode=" + mode + " addr=" + address);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "pairing overlay failed", e);
            clearSession();
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean submitPin(BluetoothDevice device, String pin) {
        try {
            byte[] bytes = BluetoothAudioRepair.bluetoothPinBytes(pin);
            Object ok = device.getClass().getMethod("setPin", byte[].class)
                    .invoke(device, bytes);
            Log.i(TAG, "setPin ok=" + ok + " addr=" + device.getAddress());
            return !(ok instanceof Boolean) || (Boolean) ok;
        } catch (Exception e) {
            Log.w(TAG, "setPin failed " + safeAddress(device), e);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean submitPasskey(BluetoothDevice device, int passkey) {
        try {
            Object ok = device.getClass().getMethod("setPasskey", int.class)
                    .invoke(device, passkey);
            Log.i(TAG, "setPasskey ok=" + ok + " addr=" + device.getAddress());
            return !(ok instanceof Boolean) || (Boolean) ok;
        } catch (Exception e) {
            Log.w(TAG, "setPasskey failed " + safeAddress(device), e);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean submitConfirmation(BluetoothDevice device, boolean accept) {
        try {
            Object ok = device.getClass().getMethod(
                    "setPairingConfirmation", boolean.class).invoke(device, accept);
            Log.i(TAG, "setPairingConfirmation(" + accept + ") ok=" + ok
                    + " addr=" + device.getAddress());
            return !(ok instanceof Boolean) || (Boolean) ok;
        } catch (Exception e) {
            Log.w(TAG, "setPairingConfirmation failed", e);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean submitRemoteOutOfBandData(BluetoothDevice device) {
        try {
            Object ok = device.getClass().getMethod("setRemoteOutOfBandData").invoke(device);
            Log.i(TAG, "setRemoteOutOfBandData ok=" + ok + " addr=" + device.getAddress());
            return !(ok instanceof Boolean) || (Boolean) ok;
        } catch (Exception e) {
            Log.w(TAG, "setRemoteOutOfBandData failed", e);
            return false;
        }
    }

    private static boolean isDuplicateSession(String address) {
        if (activeSessionAddress == null || !activeSessionAddress.equals(address)) return false;
        return System.currentTimeMillis() - activeSessionAt < 30_000L;
    }

    private static void markSession(String address) {
        activeSessionAddress = address;
        activeSessionAt = System.currentTimeMillis();
    }

    static void clearSession() {
        activeSessionAddress = null;
        activeSessionAt = 0L;
    }

    private static String safeAddress(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name != null ? name : device.getAddress();
        } catch (Exception ignored) {
            return safeAddress(device);
        }
    }

    static void selfCheck() {
        if (overlayModeForVariant(BluetoothPairingVariantPolicy.VARIANT_PIN) != MODE_PIN) {
            throw new AssertionError("pin entry mode");
        }
        if (overlayModeForVariant(BluetoothPairingVariantPolicy.VARIANT_PASSKEY)
                != MODE_PASSKEY_ENTRY) {
            throw new AssertionError("passkey entry mode");
        }
        if (!"012345".equals(formatPasskey(12345))) throw new AssertionError("passkey pad");
        if (!"000042".equals(formatPasskey(42))) throw new AssertionError("passkey pad2");
        if (!"0042".equals(formatDisplayPin(42))) throw new AssertionError("pin display pad");
        if (NEGOTIATION_WINDOW_MS != 15_000L) throw new AssertionError("connect window");
    }
}
