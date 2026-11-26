package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 2026-11-26 — USB mass storage is back on for Y2 as well as Y1.
 * Layman: plug the cable in and the player shows up as a drive with Internal and SD on the PC
 * (macOS Finder, Windows Explorer and Linux all read plain disks; MTP only works on Windows).
 * Tech: gates UI + {@link UsbMassStorageController}; {@link #SYSPROP} = 1 lets the Y2 Xposed
 * bridge install {@code UsbMassStorageServerHooks} so system_server shares Internal + MicroSD.
 * Was: 2026-07-10 Y2 hard-off after 4.4.2 share failures. Reversal: force {@link #SYSPROP} 0
 * and return {@code !DeviceFeatures.isY2()} from {@link #isEnabled(SharedPreferences)}.
 */
public final class UsbMassStorageExperiment {

    public static final String PREF_USB_MASS_STORAGE_EXPERIMENT = "solar_y2_usb_mass_storage_experiment";
    /** Read by SolarContextBridgeY2 {@code UsbMassStorageServerHooks} — always 0 on Y2 product. */
    public static final String SYSPROP = "sys.solar.ums.experiment";
    /**
     * 2026-11-26 — Durable copy of {@link #SYSPROP}. The bridge reads its gate once, while
     * system_server starts, so the volatile prop alone never survives the reboot it takes to
     * reach that gate; this one does.
     */
    public static final String SYSPROP_PERSIST = "persist.sys.solar.ums.experiment";

    private UsbMassStorageExperiment() {}

    /** UMS row available on both families again (2026-11-26). */
    public static boolean isAvailableOnDevice() {
        return true;
    }

    /** Y1 and Y2 both export disks (2026-11-26). */
    public static boolean isEnabled(SharedPreferences prefs) {
        return true;
    }

    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return !DeviceFeatures.isY2();
        return isEnabled(ctx.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE));
    }

    /**
     * Y2 — publish the experiment flag for the Xposed bridge, and keep the cable path armed.
     * Layman: the player is ready to show its two drives the moment the cable goes in.
     * Tech: sysprop 1 → {@code UsbMassStorageServerHooks.installY2}; Auto-Connect on →
     *   {@code UsbHostWakeReceiver} arms the LUNs on plug-in without a prompt.
     */
    public static void syncExperimentSysprop(Context ctx) {
        if (ctx == null || !DeviceFeatures.isY2()) return;
        boolean enabled = isEnabled(ctx);
        if (!RootShell.canRun()) return;
        String value = enabled ? "1" : "0";
        RootShell.run("setprop " + SYSPROP + " " + value);
        RootShell.run("setprop " + SYSPROP_PERSIST + " " + value);
        if (enabled) {
            UsbStorageSessionFlags.setAutoConnect(ctx, true);
            return;
        }
        UsbMassStorageController.ensureStockMtpWhenExperimentOff(ctx);
    }

    /** Y2 — store the pref and re-sync the bridge flag / Auto-Connect. */
    public static void setEnabled(Context ctx, boolean enable) {
        if (ctx == null || !DeviceFeatures.isY2()) return;
        ctx.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_USB_MASS_STORAGE_EXPERIMENT, enable)
                .commit();
        syncExperimentSysprop(ctx);
    }

    /** Test hook — family only; Y2 is enabled again (2026-11-26). */
    static boolean isEnabledForFamily(String family, boolean experimentPref) {
        return true;
    }

    /** Connections → USB (UMS) submenu: both families (2026-11-26). */
    static boolean connectionsUsbMenuVisibleForFamily(String family, boolean experimentPref) {
        return true;
    }
}
