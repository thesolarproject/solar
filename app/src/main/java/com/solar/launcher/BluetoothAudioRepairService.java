package com.solar.launcher;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

/** Short-lived A2DP repair loop for MTK Android 4.2 Bluetooth Settings connections. */
public class BluetoothAudioRepairService extends Service {
    private final Handler handler = new Handler();
    private BluetoothA2dp a2dp;
    private BluetoothDevice target;
    private boolean profileRequested;
    private boolean shuttingDown;
    private int attempts;

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.A2DP) return;
            a2dp = (BluetoothA2dp) proxy;
            scheduleRepair(0);
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.A2DP) return;
            a2dp = null;
            profileRequested = false;
            if (shuttingDown) return;
            BluetoothDiagnostics.recordProfileFailure(BluetoothAudioRepairService.this);
            if (attempts < 5) scheduleRepair(500L);
        }
    };

    private final Runnable repairRunnable = new Runnable() {
        @Override public void run() {
            repairOnce();
        }
    };

    private final Runnable stopRunnable = new Runnable() {
        @Override public void run() {
            stopSelf();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        shuttingDown = false;
        BluetoothDevice resolved = BluetoothAudioRepair.resolveDevice(this, intent);
        if (resolved != null && BluetoothAudioRepair.isLikelyAudioSink(resolved)) {
            target = resolved;
            BluetoothAudioRepair.rememberLastAudioDevice(this, target);
        }
        BluetoothAudioRepair.cancelDiscovery();
        BluetoothAudioRepair.forceA2dpRoute(this);
        attempts = 0;
        ensureProfile();
        scheduleRepair(0);
        handler.removeCallbacks(stopRunnable);
        handler.postDelayed(stopRunnable, 15000L);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        handler.removeCallbacks(repairRunnable);
        handler.removeCallbacks(stopRunnable);
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && a2dp != null) {
                adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            }
        } catch (Exception ignored) {}
        a2dp = null;
        profileRequested = false;
        super.onDestroy();
    }

    private void ensureProfile() {
        if (a2dp != null || profileRequested) return;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            profileRequested = adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP);
        } catch (Exception ignored) {}
    }

    private void scheduleRepair(long delayMs) {
        handler.removeCallbacks(repairRunnable);
        handler.postDelayed(repairRunnable, delayMs);
    }

    @SuppressLint("MissingPermission")
    private void repairOnce() {
        BluetoothAudioRepair.cancelDiscovery();
        BluetoothAudioRepair.forceA2dpRoute(this);
        if (target == null) {
            target = BluetoothAudioRepair.lastRememberedDevice(this);
        }
        if (a2dp == null) {
            ensureProfile();
            retryLater();
            return;
        }
        if (target != null) {
            boolean ok = BluetoothAudioRepair.connectA2dp(this, a2dp, target, true);
            boolean connected = BluetoothAudioRepair.isA2dpConnected(a2dp, target);
            BluetoothDiagnostics.recordReconnectAttempt(
                    this, target, attempts + 1, ok, connected);
            Log.i(BluetoothAudioRepair.TAG, "repair attempt=" + attempts
                    + " ok=" + ok + " connected=" + connected
                    + " address=" + target.getAddress());
            BluetoothAudioRepair.forceA2dpRoute(this);
            if (connected) {
                stopSelf();
                return;
            }
        }
        retryLater();
    }

    private void retryLater() {
        attempts++;
        long delay = retryDelayMs(attempts);
        if (delay < 0L) return;
        scheduleRepair(delay);
    }

    static long retryDelayMs(int attempt) {
        if (attempt == 1) return 500L;
        if (attempt == 2) return 1500L;
        if (attempt == 3) return 2500L;
        if (attempt == 4) return 4000L;
        if (attempt == 5) return 6000L;
        return -1L;
    }
}
