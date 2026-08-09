package com.solar.launcher;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 2026-07-05 — Single owner for Bluetooth pairing UI and API calls (Y1 + Y2).
 * 2026-07-19 — Silent Just Works + deferred PIN: 15s Connecting window before PIN overlay.
 * Layman: AirPods/Beats pair without a PIN screen; only stubborn gadgets get a keyboard later.
 * Technical: CONSENT/confirm → setPairingConfirmation; PIN → silent setPin then delayed MODE_PIN.
 * Reversal: restore immediate consent overlay + MainActivity setPin(0000) dual handler.
 */
public final class BluetoothPairingCoordinator {

    private static final String TAG = "SolarBtPair";

    /** Overlay tier — wheel digit keyboard for PIN entry. */
    public static final int MODE_PIN = 1;
    /** Overlay tier — show passkey; user types it on the remote device. */
    public static final int MODE_PASSKEY_DISPLAY = 2;
    /** Overlay tier — passkey on screen; user confirms it matches the remote. */
    public static final int MODE_PASSKEY_CONFIRM = 3;
    /** Overlay tier — Just Works / consent confirm (legacy UI; silent path preferred). */
    public static final int MODE_CONSENT = 4;
    /** Overlay tier — API 19 display-PIN; show the value without passkey padding. */
    public static final int MODE_DISPLAY_PIN = 5;

    /**
     * Pairing variant values are stable framework protocol values, but most named constants
     * were only added to the public SDK in API 19. Keep them local so this class can load on
     * API 17 without a verifier/linkage failure.
     */
    static final int PAIRING_VARIANT_PIN = 0;
    static final int PAIRING_VARIANT_PASSKEY = 1;
    static final int PAIRING_VARIANT_PASSKEY_CONFIRMATION = 2;
    static final int PAIRING_VARIANT_CONSENT = 3;
    static final int PAIRING_VARIANT_DISPLAY_PASSKEY = 4;
    static final int PAIRING_VARIANT_DISPLAY_PIN = 5;
    static final int PAIRING_VARIANT_OOB_CONSENT = 6;
    static final int PAIRING_VARIANT_PIN_16_DIGITS = 7;

    /** Hold silent PIN negotiation before showing overlay keyboard (plan: 15s Connecting…). */
    public static final long NEGOTIATION_WINDOW_MS = 15_000L;

    private static volatile String activeSessionAddress;
    private static volatile long activeSessionAt;
    /** Address waiting on silent PIN — PIN overlay arms when this timer fires. */
    private static volatile String pendingPinAddress;
    private static volatile String pendingPinName;
    private static volatile Context pendingPinAppContext;
    private static Handler mainHandler;
    private static final Runnable delayedPinRunnable = new Runnable() {
        @Override
        public void run() {
            Context app = pendingPinAppContext;
            String address = pendingPinAddress;
            String name = pendingPinName;
            if (app == null || address == null) return;
            BluetoothDevice device = BluetoothAudioRepair.deviceForAddress(address);
            // Already bonded — nothing to ask the user.
            if (device != null) {
                try {
                    if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        Log.i(TAG, "delayed PIN skipped — bonded " + address);
                        clearPendingPin();
                        clearSession();
                        return;
                    }
                } catch (Exception ignored) {}
            }
            Log.i(TAG, "negotiation window elapsed — PIN overlay " + address);
            showPinOverlay(app, address, name != null ? name : address,
                    BluetoothAudioRepair.pairingPinForAddress(app, address));
        }
    };

    private BluetoothPairingCoordinator() {}

    /** Lazy main-looper handler — unit tests may lack a prepared Looper at class init. */
    private static Handler mainHandler() {
        if (mainHandler == null) {
            synchronized (BluetoothPairingCoordinator.class) {
                if (mainHandler == null) {
                    mainHandler = new Handler(Looper.getMainLooper());
                }
            }
        }
        return mainHandler;
    }
    /**
     * Entry from PAIRING_REQUEST broadcast — returns true when Solar owns the request
     * (silent auth, delayed PIN armed, overlay shown, or de-duped); caller should abortBroadcast().
     */
    @SuppressLint("MissingPermission")
    public static boolean onPairingRequest(Context context, BluetoothDevice device,
            int variant, int passkey, boolean forcePinUi) {
        if (context == null || device == null) return false;
        String address = safeAddress(device);
        if (address == null) return false;
        if (isDuplicateSession(address) && !forcePinUi) {
            Log.d(TAG, "pairing de-dupe " + address);
            return true;
        }
        markSession(address);
        Context app = context.getApplicationContext();

        // PIN — silent first; only forcePinUi / timeout shows keyboard.
        if (variant == PAIRING_VARIANT_PIN || variant == PAIRING_VARIANT_PIN_16_DIGITS) {
            String pin = BluetoothAudioRepair.pairingPinForDevice(context, device);
            if (forcePinUi) {
                cancelDelayedPin();
                return showPinOverlay(app, address, safeName(device), pin);
            }
            boolean submitted = submitPin(context, device, pin);
            Log.i(TAG, "silent setPin submitted=" + submitted + " addr=" + address);
            BluetoothAudioRepair.rememberLastAudioDevice(context, device);
            // Keep session; arm 15s PIN fallback if bond does not complete.
            scheduleDelayedPin(app, address, safeName(device));
            return true;
        }

        // Legacy passkey entry plus API 19 display-passkey/display-PIN requests use the
        // informational passkey overlay; the remote keypad performs the actual entry.
        if (variant == PAIRING_VARIANT_PASSKEY
                || variant == PAIRING_VARIANT_DISPLAY_PASSKEY
                || variant == PAIRING_VARIANT_DISPLAY_PIN) {
            cancelDelayedPin();
            return showPasskeyOverlay(app, address, safeName(device), passkey,
                    variant == PAIRING_VARIANT_DISPLAY_PIN
                            ? MODE_DISPLAY_PIN : MODE_PASSKEY_DISPLAY);
        }

        // Passkey confirm, Just Works consent, and API 19 OOB consent — accept through the
        // same reflection path. On API 17 the method is absent and the call safely fails closed.
        if (variant == PAIRING_VARIANT_PASSKEY_CONFIRMATION
                || variant == PAIRING_VARIANT_CONSENT
                || variant == PAIRING_VARIANT_OOB_CONSENT) {
            if (!submitConfirmation(device, true)) {
                // API 17 has no public confirmation method. Leave the broadcast available to
                // the stock Bluetooth UI instead of claiming ownership and stalling pairing.
                clearSession();
                return false;
            }
            BluetoothAudioRepair.rememberLastAudioDevice(context, device);
            Log.i(TAG, "silent setPairingConfirmation(true) variant=" + variant + " addr=" + address);
            // Brief session hold for de-dupe; no overlay.
            return true;
        }

        Log.d(TAG, "unknown variant=" + variant + " deferring to stock");
        clearSession();
        return false;
    }

    /** Bond succeeded — cancel deferred PIN keyboard. */
    public static void onBonded(BluetoothDevice device) {
        String address = safeAddress(device);
        if (address == null) return;
        if (address.equals(pendingPinAddress) || address.equals(activeSessionAddress)) {
            Log.i(TAG, "bonded — clear pending PIN " + address);
            clearPendingPin();
            clearSession();
        }
    }

    /** Auth failure during bond — force PIN keyboard even when a default PIN exists. */
    @SuppressLint("MissingPermission")
    public static void onAuthFailure(Context context, BluetoothDevice device) {
        if (context == null || device == null) return;
        String address = safeAddress(device);
        if (address == null) return;
        cancelDelayedPin();
        markSession(address);
        showPinOverlay(context.getApplicationContext(), address, safeName(device),
                BluetoothAudioRepair.pairingPinForDevice(context, device));
    }

    /**
     * Connect throbber timed out still unpaired — escalate to PIN overlay if a silent PIN
     * session was armed for this address.
     */
    public static void onNegotiationTimeout(Context context, String address) {
        if (context == null || address == null) return;
        if (pendingPinAddress == null || !address.equals(pendingPinAddress)) return;
        mainHandler().removeCallbacks(delayedPinRunnable);
        delayedPinRunnable.run();
    }

    /** User finished PIN entry on overlay keyboard — save and submit to stack. */
    @SuppressLint("MissingPermission")
    public static void submitPinFromOverlay(Context context, String address, String pin) {
        if (context == null) return;
        BluetoothDevice device = BluetoothAudioRepair.deviceForAddress(address);
        if (device == null) return;
        String cleaned = BluetoothAudioRepair.normalizePairingPin(pin);
        BluetoothAudioRepair.savePairingPin(context, address, cleaned);
        submitPin(context, device, cleaned);
        BluetoothAudioRepair.rememberLastAudioDevice(context, device);
        clearPendingPin();
        clearSession();
    }

    /** Passkey match / consent row picked on overlay. */
    @SuppressLint("MissingPermission")
    public static void submitConfirmationFromOverlay(Context context, String address, boolean accept) {
        if (context == null) return;
        BluetoothDevice device = BluetoothAudioRepair.deviceForAddress(address);
        if (device == null) return;
        submitConfirmation(device, accept);
        if (accept) {
            BluetoothAudioRepair.rememberLastAudioDevice(context, device);
        }
        clearSession();
    }

    /** Back / Cancel on pairing overlay — reject bond attempt. */
    @SuppressLint("MissingPermission")
    public static void cancelPairing(Context context, String address) {
        cancelDelayedPin();
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

    /** Informational passkey display dismissed — session ends; stack waits for remote. */
    public static void dismissPasskeyDisplaySession() {
        clearSession();
    }

    static String formatPasskey(int passkey) {
        String raw = String.valueOf(Math.max(0, passkey));
        while (raw.length() < 6) raw = "0" + raw;
        if (raw.length() > 6) raw = raw.substring(raw.length() - 6);
        return raw;
    }

    /** Display-PIN values are four-digit codes, not six-digit passkeys. */
    static String formatDisplayPin(int pin) {
        String raw = String.valueOf(Math.max(0, pin));
        while (raw.length() < 4) raw = "0" + raw;
        return raw;
    }

    static int overlayModeForVariant(int variant) {
        if (variant == PAIRING_VARIANT_PIN || variant == PAIRING_VARIANT_PIN_16_DIGITS) {
            return MODE_PIN;
        }
        if (variant == PAIRING_VARIANT_DISPLAY_PIN) return MODE_DISPLAY_PIN;
        if (variant == PAIRING_VARIANT_PASSKEY || variant == PAIRING_VARIANT_DISPLAY_PASSKEY) {
            return MODE_PASSKEY_DISPLAY;
        }
        if (variant == PAIRING_VARIANT_PASSKEY_CONFIRMATION) return MODE_PASSKEY_CONFIRM;
        if (variant == PAIRING_VARIANT_CONSENT || variant == PAIRING_VARIANT_OOB_CONSENT) {
            return MODE_CONSENT;
        }
        return MODE_CONSENT;
    }

    /** True when silent PIN negotiation is still waiting on this address. */
    static boolean isPendingPinNegotiation(String address) {
        return address != null && address.equals(pendingPinAddress);
    }

    private static void scheduleDelayedPin(Context app, String address, String name) {
        cancelDelayedPin();
        pendingPinAppContext = app;
        pendingPinAddress = address;
        pendingPinName = name;
        mainHandler().postDelayed(delayedPinRunnable, NEGOTIATION_WINDOW_MS);
        Log.d(TAG, "armed delayed PIN in " + NEGOTIATION_WINDOW_MS + "ms for " + address);
    }

    private static void cancelDelayedPin() {
        mainHandler().removeCallbacks(delayedPinRunnable);
        clearPendingPin();
    }

    private static void clearPendingPin() {
        pendingPinAddress = null;
        pendingPinName = null;
        pendingPinAppContext = null;
    }

    /** PIN keyboard via overlay MODE_PIN (not MainActivity Settings keyboard). */
    private static boolean showPinOverlay(Context context, String address, String name, String prefill) {
        return startPairingOverlay(context, address, name, 0, MODE_PIN, prefill);
    }

    private static boolean showPasskeyOverlay(Context context, String address, String name,
            int passkey, int mode) {
        return startPairingOverlay(context, address, name, passkey, mode, null);
    }

    private static boolean startPairingOverlay(Context context, String address, String name,
            int passkey, int mode, String pinPrefill) {
        try {
            Intent svc = new Intent(context, SolarOverlayService.class);
            svc.setComponent(new ComponentName(context.getPackageName(),
                    SolarOverlayService.class.getName()));
            svc.setAction(OverlayTriggers.ACTION_SHOW_OVERLAY_BT_PAIRING);
            svc.putExtra(OverlayTriggers.EXTRA_BT_PAIRING_MODE, mode);
            svc.putExtra(OverlayTriggers.EXTRA_BT_PAIRING_ADDRESS, address);
            svc.putExtra(OverlayTriggers.EXTRA_BT_PAIRING_NAME, name != null ? name : "");
            svc.putExtra(OverlayTriggers.EXTRA_BT_PAIRING_PASSKEY, passkey);
            if (pinPrefill != null) {
                svc.putExtra(OverlayTriggers.EXTRA_BT_PAIRING_PIN_PREFILL, pinPrefill);
            }
            context.startService(svc);
            Log.i(TAG, "pairing overlay mode=" + mode + " addr=" + address);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "pairing overlay failed", e);
            clearSession();
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean submitPin(Context context, BluetoothDevice device, String pin) {
        try {
            byte[] bytes = BluetoothAudioRepair.bluetoothPinBytes(pin);
            Object ok = device.getClass().getMethod("setPin", byte[].class).invoke(device, bytes);
            Log.i(TAG, "setPin ok=" + ok + " addr=" + device.getAddress());
            return !(ok instanceof Boolean) || (Boolean) ok;
        } catch (Exception e) {
            Log.w(TAG, "setPin failed " + device.getAddress(), e);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean submitConfirmation(BluetoothDevice device, boolean accept) {
        if (device == null) return false;
        try {
            Object result = device.getClass().getMethod("setPairingConfirmation", boolean.class)
                    .invoke(device, accept);
            Log.i(TAG, "setPairingConfirmation(" + accept + ") addr=" + device.getAddress());
            return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
        } catch (Throwable e) {
            Log.w(TAG, "setPairingConfirmation unavailable/failed", e);
            return false;
        }
    }

    private static boolean isDuplicateSession(String address) {
        if (activeSessionAddress == null || !activeSessionAddress.equals(address)) return false;
        return System.currentTimeMillis() - activeSessionAt < 30000L;
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

    /** Unit-test guard — variant → overlay mode mapping + passkey pad. */
    static void selfCheck() {
        if (overlayModeForVariant(PAIRING_VARIANT_PIN) != MODE_PIN) {
            throw new AssertionError("pin mode");
        }
        if (!"012345".equals(formatPasskey(12345))) throw new AssertionError("passkey pad");
        if (!"000042".equals(formatPasskey(42))) throw new AssertionError("passkey pad2");
        if (NEGOTIATION_WINDOW_MS != 15_000L) throw new AssertionError("negotiation window");
    }
}
