package com.solar.launcher.xposed.bridge;

import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 2026-07-05 — Replace stock Bluetooth pairing dialogs with Solar global overlay tiers.
 * 2026-07-19 — Align with coordinator: silent Just Works / PIN; overlay only for passkey display.
 * Layman: AirPods pair without a PIN screen; stock Holo dialog is dismissed quietly.
 * Technical: CONSENT/confirm → setPairingConfirmation; PIN → setPin(0000); passkey display → overlay.
 * Reversal: remove install calls; stock pairing dialogs return on Y1/Y2.
 */
final class BluetoothPairingHooks {

    private static final String SETTINGS_PAIRING_DIALOG =
            "com.android.settings.bluetooth.BluetoothPairingDialog";
    private static final String BLUETOOTH_PAIRING_DIALOG =
            "com.android.bluetooth.BluetoothPairingDialog";

    private BluetoothPairingHooks() {}

    /** Install in Settings and Bluetooth packages (API 17 + 19). */
    static void install(LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        if ("com.android.settings".equals(lpparam.packageName)) {
            hookPairingDialog(lpparam, SETTINGS_PAIRING_DIALOG);
        }
        if ("com.android.bluetooth".equals(lpparam.packageName)) {
            hookPairingDialog(lpparam, BLUETOOTH_PAIRING_DIALOG);
        }
    }

    private static void hookPairingDialog(LoadPackageParam lpparam, String className) {
        try {
            Class<?> dialogClass = XposedHelpers.findClass(className, lpparam.classLoader);
            XposedHookKit.hookAll(dialogClass, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Dialog)) return;
                    Dialog dialog = (Dialog) param.thisObject;
                    Context ctx = dialog.getContext();
                    if (ctx == null) return;
                    PairingExtract extract = PairingExtract.fromDialog(dialog);
                    if (extract == null || extract.address == null || extract.device == null) return;
                    // 2026-07-19 — Silent paths match BluetoothPairingCoordinator (no overlay).
                    if (extract.mode == 3 /* MODE_PASSKEY_CONFIRM */
                            || extract.mode == 4 /* MODE_CONSENT */) {
                        if (silentConfirm(extract.device, true)) {
                            try {
                                dialog.dismiss();
                            } catch (Throwable ignored) {}
                            XposedHookKit.skipMethod(param);
                            SolarContextBridge.log("BluetoothPairingDialog silent confirm mode="
                                    + extract.mode + " addr=" + extract.address);
                        }
                        // If API 17 exposes no confirmation method, leave stock dialog active.
                        return;
                    }
                    if (extract.mode == 1 /* MODE_PIN */) {
                        if (silentSetPin(extract.device, "0000")) {
                            try {
                                dialog.dismiss();
                            } catch (Throwable ignored) {}
                            XposedHookKit.skipMethod(param);
                            SolarContextBridge.log("BluetoothPairingDialog silent PIN addr="
                                    + extract.address);
                        }
                        return;
                    }

                    // Passkey display only — user must read digits on Solar overlay.
                    if (!SolarOverlayClient.canDeliverOverlay(ctx)) return;
                    boolean ok = SolarOverlayClient.showBluetoothPairing(ctx, extract.mode,
                            extract.address, extract.name, extract.passkey, extract.pinPrefill);
                    if (ok) {
                        try {
                            dialog.dismiss();
                        } catch (Throwable ignored) {}
                        XposedHookKit.skipMethod(param);
                        SolarContextBridge.log("BluetoothPairingDialog replaced mode="
                                + extract.mode + " addr=" + extract.address);
                    }
                }
            });
            SolarContextBridge.log("hooked " + className);
        } catch (Throwable t) {
            SolarContextBridge.log("BluetoothPairing hook skip " + className + ": "
                    + t.getClass().getSimpleName());
        }
    }

    private static boolean silentConfirm(BluetoothDevice device, boolean accept) {
        if (device == null) return false;
        try {
            Method m = device.getClass().getMethod("setPairingConfirmation", boolean.class);
            Object result = m.invoke(device, accept);
            return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
        } catch (Throwable t) {
            SolarContextBridge.log("silentConfirm unavailable/failed: "
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean silentSetPin(BluetoothDevice device, String pin) {
        if (device == null) return false;
        try {
            byte[] bytes;
            try {
                Method convert = BluetoothDevice.class.getMethod("convertPinToBytes", String.class);
                bytes = (byte[]) convert.invoke(null, pin);
            } catch (Throwable ignored) {
                bytes = pin.getBytes();
            }
            Object result = device.getClass().getMethod("setPin", byte[].class).invoke(device, bytes);
            return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
        } catch (Throwable t) {
            SolarContextBridge.log("silentSetPin failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Reflect pairing dialog fields — mDevice / mType / mPairingKey on AOSP 4.2/4.4. */
    private static final class PairingExtract {
        final int mode;
        final String address;
        final String name;
        final int passkey;
        final String pinPrefill;
        final BluetoothDevice device;

        PairingExtract(int mode, String address, String name, int passkey, String pinPrefill,
                BluetoothDevice device) {
            this.mode = mode;
            this.address = address;
            this.name = name;
            this.passkey = passkey;
            this.pinPrefill = pinPrefill;
            this.device = device;
        }

        static PairingExtract fromDialog(Dialog dialog) {
            if (dialog == null) return null;
            Object self = dialog;
            BluetoothDevice device = (BluetoothDevice) readField(self, "mDevice");
            if (device == null) {
                device = (BluetoothDevice) readField(self, "mBluetoothDevice");
            }
            if (device == null) return null;
            int variant = readIntField(self, "mType", -1);
            if (variant < 0) variant = readIntField(self, "mPairingVariant", -1);
            int passkey = readIntField(self, "mPairingKey", 0);
            if (passkey == 0) passkey = readIntField(self, "mPasskey", 0);
            String address = safeAddress(device);
            String name = safeName(device);
            int mode = mapVariantToMode(variant);
            return new PairingExtract(mode, address, name, passkey, null, device);
        }

        private static int mapVariantToMode(int variant) {
            // Pairing variant values are stable protocol values across API 17/19.
            // Keep literals here because this bridge is compiled against old framework stubs.
            if (variant == 0 || variant == 7) return 1; // PIN / 16-digit PIN → MODE_PIN
            if (variant == 1 || variant == 4) return 2; // passkey/display-passkey
            if (variant == 2) return 3; // passkey confirmation
            if (variant == 3 || variant == 6) return 4; // consent / OOB consent
            if (variant == 5) return 5; // display-PIN → informational pairing overlay
            return 4; // unknown → consent-safe fallback
        }

        private static Object readField(Object obj, String name) {
            try {
                return XposedHelpers.getObjectField(obj, name);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static int readIntField(Object obj, String name, int fallback) {
            try {
                Object v = XposedHelpers.getObjectField(obj, name);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {}
            return fallback;
        }

        private static String safeAddress(BluetoothDevice device) {
            try {
                return device.getAddress();
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static String safeName(BluetoothDevice device) {
            try {
                String n = device.getName();
                return n != null ? n : device.getAddress();
            } catch (Throwable ignored) {
                return safeAddress(device);
            }
        }
    }
}
