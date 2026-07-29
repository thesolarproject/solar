package com.solar.input.policy;

/**
 * Shared Android Bluetooth pairing-variant routing for Solar and its Xposed bridge.
 *
 * <p>The integer variants are hidden on some of Solar's API 17/19 SDK surfaces, so keeping the
 * mapping here prevents the app receiver and system-dialog hook from drifting apart.</p>
 */
public final class BluetoothPairingVariantPolicy {

    public static final int MODE_NONE = 0;
    public static final int MODE_PIN_ENTRY = 1;
    public static final int MODE_PASSKEY_DISPLAY = 2;
    public static final int MODE_PASSKEY_CONFIRM = 3;
    public static final int MODE_CONSENT = 4;
    public static final int MODE_PASSKEY_ENTRY = 5;
    public static final int MODE_PIN_DISPLAY = 6;
    public static final int MODE_OOB_CONSENT = 7;

    public static final int VARIANT_PIN = 0;
    public static final int VARIANT_PASSKEY = 1;
    public static final int VARIANT_PASSKEY_CONFIRMATION = 2;
    public static final int VARIANT_CONSENT = 3;
    public static final int VARIANT_DISPLAY_PASSKEY = 4;
    public static final int VARIANT_DISPLAY_PIN = 5;
    public static final int VARIANT_OOB_CONSENT = 6;
    public static final int VARIANT_PIN_16_DIGITS = 7;

    private BluetoothPairingVariantPolicy() {}

    /** Maps Android's pairing request to the wheel-friendly Solar UI that must handle it. */
    public static int modeForVariant(int variant) {
        switch (variant) {
            case VARIANT_PIN:
            case VARIANT_PIN_16_DIGITS:
                return MODE_PIN_ENTRY;
            case VARIANT_PASSKEY:
                return MODE_PASSKEY_ENTRY;
            case VARIANT_PASSKEY_CONFIRMATION:
                return MODE_PASSKEY_CONFIRM;
            case VARIANT_CONSENT:
                return MODE_CONSENT;
            case VARIANT_DISPLAY_PASSKEY:
                return MODE_PASSKEY_DISPLAY;
            case VARIANT_DISPLAY_PIN:
                return MODE_PIN_DISPLAY;
            case VARIANT_OOB_CONSENT:
                return MODE_OOB_CONSENT;
            default:
                return MODE_NONE;
        }
    }

    public static boolean isCredentialEntryMode(int mode) {
        return mode == MODE_PIN_ENTRY || mode == MODE_PASSKEY_ENTRY;
    }

    public static boolean isDisplayOnlyMode(int mode) {
        return mode == MODE_PASSKEY_DISPLAY || mode == MODE_PIN_DISPLAY;
    }
}
