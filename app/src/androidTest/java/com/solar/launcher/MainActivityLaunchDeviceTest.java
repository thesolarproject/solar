package com.solar.launcher;

import android.content.Intent;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Smoke: MainActivity paints home menu rows (ThemeManager fallback on emulator). */
@RunWith(AndroidJUnit4.class)
public class MainActivityLaunchDeviceTest {
    @Rule
    public ActivityTestRule<MainActivity> activityRule =
            new ActivityTestRule<>(MainActivity.class, true, false);

    @Test
    public void launch_showsHomeMenuRows() throws InterruptedException {
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.setClassName(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                "com.solar.launcher.MainActivity");
        MainActivity activity = activityRule.launchActivity(launch);
        for (int i = 0; i < 40; i++) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() { /* sync */ }
            });
            Thread.sleep(250);
            ViewGroup home = (ViewGroup) activity.findViewById(R.id.container_home_menu_items);
            if (home != null && home.getChildCount() > 0) {
                return;
            }
        }
        ViewGroup home = (ViewGroup) activity.findViewById(R.id.container_home_menu_items);
        int count = home != null ? home.getChildCount() : -1;
        throw new AssertionError("home menu empty, children=" + count);
    }
}
