package com.solar.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 2026-07-06 — Keeps JJ/Rockbox wheel handoff alive without MainActivity on screen.
 * Layman: tiny background helper so wheel keys still work when JJ is your home app.
 * Technical: START_STICKY; arms MediaBtnReceiver + MODE_JJ inject via ExternalInputHandoff.
 * Reversal: delete service; handoff only when Solar UI was opened once.
 */
public final class SolarHandoffService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        MediaButtonRegistrar.ensureRegistered(this);
        ExternalInputHandoff.armForForegroundPackage(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && JjHandoffReceiver.ACTION_ARM_JJ_HANDOFF.equals(intent.getAction())) {
            ExternalInputHandoff.armJjShim(this);
        } else {
            String target = LauncherPreference.getHomeTarget(this);
            if (LauncherDefault.TARGET_JJ.equals(target)) {
                ExternalInputHandoff.armForForegroundPackage(this);
                MediaButtonRegistrar.ensureRegistered(this);
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
