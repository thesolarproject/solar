package com.solar.launcher.homehelper;

import com.solar.home.policy.HomeTargetPolicy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 2026-07-08 — normalizeSwitchArg must map stock → "stock" (was silent solar).
 * Layman: helper must not turn a Stock pick into Solar when talking to the switch script.
 * Technical: unit-test package-private mapper without root / device.
 * Reversal: delete; only HomeTargetPolicyTest covered tokens.
 */
public class LauncherSwitchExecutorTest {

    @Test
    public void normalizeSwitchArgMapsKnownTargets() {
        assertEquals("rockbox",
                LauncherSwitchExecutor.normalizeSwitchArg("rockbox"));
        assertEquals("jj",
                LauncherSwitchExecutor.normalizeSwitchArg(HomeTargetPolicy.TARGET_JJ));
        assertEquals("stock",
                LauncherSwitchExecutor.normalizeSwitchArg(HomeTargetPolicy.TARGET_STOCK));
        assertEquals("custom",
                LauncherSwitchExecutor.normalizeSwitchArg(HomeTargetPolicy.TARGET_CUSTOM));
        assertEquals("solar",
                LauncherSwitchExecutor.normalizeSwitchArg(HomeTargetPolicy.TARGET_SOLAR));
    }

    @Test
    public void normalizeSwitchArgUnknownFailsOpenToSolar() {
        assertEquals("solar", LauncherSwitchExecutor.normalizeSwitchArg(null));
        assertEquals("solar", LauncherSwitchExecutor.normalizeSwitchArg("bogus"));
    }
}
