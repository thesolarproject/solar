package com.solar.launcher;

import android.content.Intent;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression: leaving Get Music must not duplicate home menu rows. */
@RunWith(AndroidJUnit4.class)
public class MainActivityDeezerHomeMenuDeviceTest {
    @Rule
    public ActivityTestRule<MainActivity> activityRule =
            new ActivityTestRule<>(MainActivity.class, true, false);

    @Test
    public void getMusicRoundTrip_homeMenuRowCountStable() throws InterruptedException {
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.setClassName(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                "com.solar.launcher.MainActivity");
        final MainActivity activity = activityRule.launchActivity(launch);
        waitForHomeMenu(activity);

        final int[] before = new int[1];
        final int[] after = new int[1];
        final int[] expected = new int[1];

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ViewGroup home = (ViewGroup) activity.findViewById(R.id.container_home_menu_items);
                before[0] = home != null ? home.getChildCount() : -1;
                expected[0] = activity.deviceTestExpectedHomeMenuRowCount();
                activity.deviceTestGetMusicMenuRoundTrip();
                home = (ViewGroup) activity.findViewById(R.id.container_home_menu_items);
                after[0] = home != null ? home.getChildCount() : -1;
            }
        });
        Thread.sleep(500);

        if (after[0] != expected[0]) {
            throw new AssertionError("home menu rows after Get Music exit: expected="
                    + expected[0] + " actual=" + after[0] + " before=" + before[0]);
        }
        if (after[0] != before[0] && before[0] == expected[0]) {
            throw new AssertionError("home menu row count changed: before=" + before[0]
                    + " after=" + after[0]);
        }
    }

    private static void waitForHomeMenu(MainActivity activity) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            final boolean[] ready = new boolean[1];
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ViewGroup home = (ViewGroup) activity.findViewById(R.id.container_home_menu_items);
                    ready[0] = home != null && home.getChildCount() > 0;
                }
            });
            Thread.sleep(250);
            if (ready[0]) return;
        }
        throw new AssertionError("home menu never populated");
    }
}
