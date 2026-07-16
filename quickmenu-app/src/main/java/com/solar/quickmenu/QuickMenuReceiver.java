package com.solar.quickmenu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.solar.launcher.contracts.SolarIntents;

public class QuickMenuReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        Intent svc = new Intent(context, QuickMenuService.class);
        svc.setAction(intent.getAction());
        svc.putExtras(intent);
        context.startService(svc);
    }
}
