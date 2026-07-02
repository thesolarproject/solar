package com.solar.launcher.soulseek;

import org.junit.Test;

public class SolarDevelopmentBootstrapTest {

  @Test
  public void onlinePingUsesMarkerOnly() {
    if (!ReachIntroMessage.MARKER.equals(ReachIntroMessage.MARKER)) {
      throw new AssertionError("marker");
    }
    if (ReachIntroMessage.isIntro(ReachIntroMessage.MARKER)) {
      return;
    }
    throw new AssertionError("marker should register as intro");
  }

  @Test
  public void resetForTestClearsStartupGuard() {
    SolarDevelopmentBootstrap.resetForTest();
  }
}
