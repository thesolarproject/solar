package com.solar.launcher;

import android.bluetooth.BluetoothAdapter;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 2026-08-05 — KitKat BluetoothAdapter null-mService poisoning repair (Y2 / API 19 and friends).
 *
 * The Y2 (MT6582, Android 4.4.2) ships the classic KitKat BluetoothAdapter flaw:
 * {@code BluetoothAdapter.getDefaultAdapter()} caches a process-wide singleton whose private
 * {@code mService} field is only bound while Bluetooth is ON. When the FIRST call happens while
 * Bluetooth is OFF — which is exactly what happens at boot on the Y2, before the user enables
 * it — the singleton keeps {@code mService = null} for the lifetime of the process. Every
 * adapter API then silently no-ops:
 *   • getState()          → STATE_OFF
 *   • isEnabled()         → false
 *   • startDiscovery()    → does nothing (no ACTION_FOUND ever fires)
 *   • getBondedDevices()  → empty
 * so Solar's Bluetooth menu shows only the power toggle and never lists a single device, and
 * the in-app power toggle can't turn the radio on either. A fresh process started while
 * Bluetooth is ON binds fine (verified live on the Y2).
 *
 * Y1 (API 17 / 4.2) never suffers this: 4.2 binds the "bluetooth" service directly regardless
 * of radio power. AOSP 4.4 normally re-binds {@code mService} through an IBluetoothManager
 * callback, but the MTK fork does not deliver it, so the only reliable fix is to re-fetch the
 * service in place, which is what this class does via reflection.
 *
 * Every call site shares the same singleton, so one heal fixes the full menu, the context
 * tier and the pairing coordinator at once. Safe by construction: every failure path is
 * swallowed (the framework's broken state is preserved), and only API 18-22 is touched —
 * Y1 (17) and modern phones (23+) are never modified.
 *
 * Reversal: delete this class and the BluetoothAdapterCompat.repairIfPoisoned() call sites.
 */
public final class BluetoothAdapterCompat {

    private static final String TAG = "SolarBT";

    private BluetoothAdapterCompat() {}

    /** True on API levels that carry the KitKat singleton-poisoning bug (4.3–5.1). */
    static boolean shouldRepair(int sdkInt) {
        return sdkInt >= 18 && sdkInt <= 22;
    }

    /**
     * Heal the process-wide BluetoothAdapter singleton when its service binding is stale.
     * No-op when the adapter already has a live service; safe to call on every menu rebuild,
     * power toggle, scan trigger and STATE_ON broadcast. Returns true when the adapter is
     * usable afterwards (Bluetooth actually on), false when it isn't (or repair is a no-op).
     */
    public static boolean repairIfPoisoned() {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return false;
        if (!shouldRepair(Build.VERSION.SDK_INT)) {
            return adapter.isEnabled() || adapter.getState() == BluetoothAdapter.STATE_ON;
        }
        try {
            // Fast path — healthy binding already present: single state read, no reflection.
            if (adapter.getState() != BluetoothAdapter.STATE_OFF) {
                return adapter.isEnabled();
            }
            // Re-run what the framework's constructor did: mManagerService.getAdapter() → mService.
            final Field mgrField = BluetoothAdapter.class.getDeclaredField("mManagerService");
            mgrField.setAccessible(true);
            final Object manager = mgrField.get(adapter);
            if (manager == null) return false;
            final Method getAdapter = manager.getClass().getMethod("getAdapter");
            final Object fresh = getAdapter.invoke(manager); // IBluetoothService, null while off
            final Field svcField = BluetoothAdapter.class.getDeclaredField("mService");
            svcField.setAccessible(true);
            Object lock = null;
            try {
                final Field lockField = BluetoothAdapter.class.getDeclaredField("mServiceLock");
                lockField.setAccessible(true);
                lock = lockField.get(adapter);
            } catch (Throwable ignored) {
                // 4.2-style adapters have no lock object; fall back to a plain write.
            }
            if (lock != null) {
                synchronized (lock) {
                    svcField.set(adapter, fresh);
                }
            } else {
                svcField.set(adapter, fresh);
            }
            if (fresh != null) {
                Log.i(TAG, "repaired KitKat BluetoothAdapter mService binding (poisoned singleton)");
            }
            return fresh != null;
        } catch (Throwable t) {
            Log.w(TAG, "BluetoothAdapter repair unavailable: " + t);
            return false;
        }
    }

    /**
     * getDefaultAdapter() that heals the KitKat singleton first. Prefer at Bluetooth entry
     * points so the very first state read already sees a live service. Returns the framework
     * singleton, or null when no adapter exists.
     */
    public static BluetoothAdapter getDefaultAdapter() {
        repairIfPoisoned();
        return BluetoothAdapter.getDefaultAdapter();
    }
}
