package com.solar.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** On Solar ROM boot: set Solar as default HOME and open the launcher when enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        LauncherDefault.ensureDefaultHome(context);
        try {
            if (!context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).enabled) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
