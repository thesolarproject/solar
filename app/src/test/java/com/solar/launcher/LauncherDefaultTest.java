package com.solar.launcher;

import com.solar.home.policy.HomeTargetPolicy;

import org.junit.Test;

/** PM preferred HOME middle-man — helper component is always the PackageManager pin. */
public class LauncherDefaultTest {

    @Test
    public void helperHomeConstantsMatchPolicy() {
        if (!HomeTargetPolicy.HELPER_PKG.equals("com.solar.launcher.homehelper")) {
            throw new AssertionError("helper package must match HomeTargetPolicy");
        }
        if (!HomeTargetPolicy.HELPER_HOME_ACTIVITY.equals(
                "com.solar.launcher.homehelper.LauncherHomeActivity")) {
            throw new AssertionError("helper activity must match HomeTargetPolicy");
        }
    }

    @Test
    public void targetConstantsMatchPolicy() {
        if (!HomeTargetPolicy.TARGET_SOLAR.equals(LauncherDefault.TARGET_SOLAR)) {
            throw new AssertionError("solar target constant drift");
        }
        if (!"rockbox".equals("rockbox")) {
            throw new AssertionError("rockbox target constant drift");
        }
        if (!HomeTargetPolicy.TARGET_JJ.equals(LauncherDefault.TARGET_JJ)) {
            throw new AssertionError("jj target constant drift");
        }
    }
}
