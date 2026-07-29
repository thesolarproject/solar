package com.solar.launcher.xposed.bridge;

import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import com.solar.input.policy.BluetoothPairingVariantPolicy;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Replace stock Bluetooth pairing dialogs with Solar's wheel-friendly global overlay.
 *
 * <p>Credential entry, numeric confirmation, consent, and OOB consent stay interactive. Android's
 * display-only variants are acknowledged after the overlay has been delivered, matching the
 * framework dialog. If Solar cannot accept the handoff, the stock dialog is left untouched.</p>
 *
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
                    if (extract.mode == BluetoothPairingVariantPolicy.MODE_NONE) return;
                    if (!SolarOverlayClient.canDeliverOverlay(ctx)) return;
                    boolean ok = SolarOverlayClient.showBluetoothPairing(ctx, extract.mode,
                            extract.address, extract.name, extract.passkey, extract.pinPrefill);
                    if (!ok) return;

                    // Match AOSP's automatic acknowledgement for display-only pairing variants.
                    if (extract.mode == BluetoothPairingVariantPolicy.MODE_PASSKEY_DISPLAY) {
                        silentConfirm(extract.device, true);
                    } else if (extract.mode == BluetoothPairingVariantPolicy.MODE_PIN_DISPLAY) {
                        silentSetPin(extract.device, formatDisplayPin(extract.passkey));
                    }

                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {}
                    XposedHookKit.skipMethod(param);
                    SolarContextBridge.log("BluetoothPairingDialog replaced mode="
                            + extract.mode + " addr=" + extract.address);
                }
            });
            SolarContextBridge.log("hooked " + className);
        } catch (Throwable t) {
            SolarContextBridge.log("BluetoothPairing hook skip " + className + ": "
                    + t.getClass().getSimpleName());
        }
    }

    private static void silentConfirm(BluetoothDevice device, boolean accept) {
        try {
            Method m = device.getClass().getMethod("setPairingConfirmation", boolean.class);
            m.invoke(device, accept);
        } catch (Throwable t) {
            SolarContextBridge.log("silentConfirm failed: " + t.getClass().getSimpleName());
        }
    }

    private static void silentSetPin(BluetoothDevice device, String pin) {
        try {
            byte[] bytes;
            try {
                Method convert = BluetoothDevice.class.getMethod("convertPinToBytes", String.class);
                bytes = (byte[]) convert.invoke(null, pin);
            } catch (Throwable ignored) {
                bytes = pin.getBytes();
            }
            Method m = device.getClass().getMethod("setPin", byte[].class);
            m.invoke(device, bytes);
        } catch (Throwable t) {
            SolarContextBridge.log("silentSetPin failed: " + t.getClass().getSimpleName());
        }
    }

    private static String formatDisplayPin(int pin) {
        int normalized = Math.abs(pin) % 10000;
        return String.format(java.util.Locale.US, "%04d", normalized);
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
            int mode = BluetoothPairingVariantPolicy.modeForVariant(variant);
            return new PairingExtract(mode, address, name, passkey, null, device);
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
