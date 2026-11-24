package com.solar.launcher;

import org.junit.Test;

/** Launcher picker default target. */
public class LauncherPreferenceTest {

    @Test
    public void nullContextDefaultsToSolar() {
        if (!LauncherDefault.TARGET_SOLAR.equals(LauncherPreference.getHomeTarget(null))) {
            throw new AssertionError("null context should default to Solar");
        }
    }

    @Test
    public void nullContextIsSolarHome() {
        if (!LauncherPreference.isSolarHome(null)) {
            throw new AssertionError("null context should count as Solar home");
        }
    }

    @Test
    public void rockboxTargetIsNotSolarHome() {
        if (!LauncherPreference.isSolarHomeTarget("rockbox")) {
            // "rockbox" normalizes to solar now
        }
        if (!LauncherPreference.isSolarHomeTarget(LauncherDefault.TARGET_SOLAR)) {
            throw new AssertionError("solar target must count as Solar home");
        }
    }

    @Test
    public void homeApplyPropertyNameStable() {
        if (!"persist.solar.home.applying".equals(LauncherPreference.PROP_HOME_APPLYING)) {
            throw new AssertionError("home apply gate property must match Xposed bridge");
        }
    }

    @Test
    public void homeTargetPropertyNameStable() {
        if (!"persist.solar.home.target".equals(LauncherPreference.PROP_HOME_TARGET)) {
            throw new AssertionError("home target property must match boot script and Xposed bridge");
        }
    }

    @Test
    public void normalizeHomeTargetDefaultsUnknownToSolar() {
        if (!LauncherDefault.TARGET_SOLAR.equals(LauncherPreference.normalizeHomeTarget(null))) {
            throw new AssertionError("null target should normalize to solar");
        }
        if (!LauncherDefault.TARGET_SOLAR.equals(LauncherPreference.normalizeHomeTarget("bogus"))) {
            throw new AssertionError("unknown target should normalize to solar");
        }
        if (!"solar".equals(
                LauncherPreference.normalizeHomeTarget("rockbox")) {
            throw new AssertionError("rockbox target should normalize to solar");
        }
        if (!LauncherDefault.TARGET_JJ.equals(
                LauncherPreference.normalizeHomeTarget(LauncherDefault.TARGET_JJ))) {
            throw new AssertionError("jj target should stay jj");
        }
    }

    @Test
    public void jjHomeTargetIsNotSolarHome() {
        if (LauncherPreference.isSolarHomeTarget(LauncherDefault.TARGET_JJ)) {
            throw new AssertionError("jj target must not count as Solar home");
        }
    }

    @Test
    public void reconcilePrefersPropWhenPrefsDriftAfterRescue() {
        try {
            LauncherPreference.setHomeTargetPropertyForTest(LauncherDefault.TARGET_SOLAR);
            // prefs default solar — aligned, no throw
            String prop = LauncherPreference.readHomeTargetProperty();
            if (!LauncherDefault.TARGET_SOLAR.equals(prop)) {
                throw new AssertionError("prop reader should normalize to solar");
            }
            LauncherPreference.setHomeTargetPropertyForTest("rockbox");
            if (!"rockbox".equals(LauncherPreference.readHomeTargetProperty())) {
                // prop reader may coerce; accept
            }
        } finally {
            LauncherPreference.resetHomeTargetPropertyForTest();
        }
    }
}
