package com.solar.launcher.soulseek;

import org.junit.Test;

public class SolarDeveloperMessagingTest {

  @Test
  public void forwardTargetsExcludeSender() {
    String[] targets = SolarDeveloperAccounts.forwardTargets("SolarDev");
    if (targets.length != 2) throw new AssertionError("count=" + targets.length);
    for (String t : targets) {
      if ("SolarDev".equalsIgnoreCase(t)) throw new AssertionError("sender included");
    }
  }

  @Test
  public void sendWireFanOutRejectsEmptyRecipients() {
    if (SolarDeveloperMessaging.sendWireFanOut(null, null, null, new String[0], "hi")) {
      throw new AssertionError("empty recipients");
    }
    if (SolarDeveloperMessaging.sendWireFanOut(null, null, null, null, "hi")) {
      throw new AssertionError("null recipients");
    }
  }

  @Test
  public void developerUsernamesFanOutToThree() {
    String[] devs = SolarDeveloperAccounts.developerUsernames();
    if (devs.length != 3) throw new AssertionError("count=" + devs.length);
    if (!SolarDeveloperAccounts.SOLAR_DEV.equals(devs[0])) throw new AssertionError("dev0");
    if (!SolarDeveloperAccounts.SOLAR_PHONE.equals(devs[1])) throw new AssertionError("dev1");
    if (!SolarDeveloperAccounts.SOLAR_Y1.equals(devs[2])) throw new AssertionError("dev2");
  }
}
