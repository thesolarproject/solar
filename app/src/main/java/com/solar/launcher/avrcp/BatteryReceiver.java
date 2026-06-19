package com.solar.launcher.avrcp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/** Maps battery level to AVRCP battery_status byte + playstate wake. */
public final class BatteryReceiver extends BroadcastReceiver {
    private static BatteryReceiver registered;

    static void register(Context context) {
        if (registered != null) return;
        registered = new BatteryReceiver();
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        context.getApplicationContext().registerReceiver(registered, f);
    }

    static void unregister() {
        // ponytail: leave registered for launcher lifetime
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        byte avrcp;
        if (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL) {
            avrcp = 4;
        } else if (scale > 0 && level * 100 / scale <= 15) {
            avrcp = 2;
        } else if (scale > 0 && level * 100 / scale <= 30) {
            avrcp = 1;
        } else {
            avrcp = 0;
        }
        TrackInfoWriter.INSTANCE.setBattery(avrcp);
    }
}
