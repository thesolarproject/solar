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
    SolarDeveloperMessaging.FanOutResult empty =
            SolarDeveloperMessaging.sendWireFanOut(null, null, null, new String[0], "hi");
    if (empty.allSucceeded()) throw new AssertionError("empty recipients");
    SolarDeveloperMessaging.FanOutResult nil =
            SolarDeveloperMessaging.sendWireFanOut(null, null, null, null, "hi");
    if (nil.allSucceeded()) throw new AssertionError("null recipients");
  }

  @Test
  public void fanOutResultTracksPartialFailure() {
    String[] devs = SolarDeveloperAccounts.developerUsernames();
    boolean[] per = new boolean[] { true, false, true };
    SolarDeveloperMessaging.FanOutResult r =
            SolarDeveloperMessaging.FanOutResult.from(devs, per);
    if (r.allSucceeded()) throw new AssertionError("partial should fail");
    String[] failed = r.failedRecipients();
    if (failed.length != 1) throw new AssertionError("failed count=" + failed.length);
    if (!"thesolarphone".equals(failed[0])) throw new AssertionError("failed=" + failed[0]);
  }

  @Test
  public void fanOutResultMergeCombinesPaths() {
    String[] devs = SolarDeveloperAccounts.developerUsernames();
    boolean[] diag = new boolean[] { true, false, false };
    boolean[] main = new boolean[] { false, true, true };
    SolarDeveloperMessaging.FanOutResult merged =
            SolarDeveloperMessaging.FanOutResult.from(devs, diag)
                    .merge(SolarDeveloperMessaging.FanOutResult.from(devs, main));
    if (!merged.allSucceeded()) throw new AssertionError("merge should cover all");
  }

  /** Diag-first fan-out: empty recipients still fails cleanly (diag path entry point). */
  @Test
  public void sendWireFanOutDiagFirstOnEmptyStillFails() {
    SolarDeveloperMessaging.FanOutResult r =
            SolarDeveloperMessaging.sendWireFanOut(null, null, null,
                    SolarDeveloperAccounts.developerUsernames(), "hi");
    if (r.allSucceeded()) throw new AssertionError("no session");
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
