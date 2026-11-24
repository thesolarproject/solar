package com.solar.launcher.homehelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import com.solar.home.policy.HomeTargetPolicy;

/**
 * 2026-07-06 — Brief banner during HOME switch or restart — replaces crash/ANR dialogs.
 * Layman: tells user "Switching to Rockbox…" while packages settle.
 * Technical: transparent activity auto-finishes after 2s; only UI allowed during transition.
 * Reversal: remove; rely on stock AMS error dialogs again.
 */
public final class LauncherSwitchPromptActivity extends Activity {

    private static final long DISMISS_MS = 2000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String msg = getIntent() != null ? getIntent().getStringExtra("message") : null;
        if (msg == null || msg.length() == 0) {
            msg = getString(R.string.prompt_switching_default);
        }
        TextView label = new TextView(this);
        label.setText(msg);
        label.setPadding(24, 24, 24, 24);
        setContentView(label);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, DISMISS_MS);
    }

    /** Show switch banner for Solar / Rockbox / JJ target. */
    public static void showSwitching(Context context, String target) {
        show(context, labelForTarget(context, target, false));
    }

    /** Show restart banner for current target. */
    public static void showRestarting(Context context, String target) {
        show(context, labelForTarget(context, target, true));
    }

    private static void show(Context context, String message) {
        if (context == null || message == null) return;
        try {
            Intent i = new Intent(context, LauncherSwitchPromptActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            i.putExtra("message", message);
            context.startActivity(i);
        } catch (Exception ignored) {}
    }

    private static String labelForTarget(Context context, String target, boolean restart) {
        String t = HomeTargetPolicy.normalizeTarget(target);
        int res;
        if (restart) {
            if (HomeTargetPolicy.TARGET_JJ.equals(t)) {
                res = R.string.prompt_restarting_jj;
            } else {
                res = R.string.prompt_restarting_solar;
            }
        } else {
            if (HomeTargetPolicy.TARGET_JJ.equals(t)) {
                res = R.string.prompt_switching_jj;
            } else {
                res = R.string.prompt_switching_solar;
            }
        }
        return context.getString(res);
    }
}
