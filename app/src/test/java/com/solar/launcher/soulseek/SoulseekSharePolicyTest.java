package com.solar.launcher.soulseek;

import org.junit.Test;

public class SoulseekSharePolicyTest {

  @Test
  public void activeWhenChargingAndWifi() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(true, true, false);
    if (p.state() != SoulseekSharePolicy.State.ACTIVE) throw new AssertionError("charging");
    if (!p.acceptNewUploads()) throw new AssertionError("accept");
    if (!p.announceShares()) throw new AssertionError("announce");
  }

  @Test
  public void activeWhenSolarInUseAndWifi() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(false, true, true);
    if (p.state() != SoulseekSharePolicy.State.ACTIVE) throw new AssertionError("solar in use");
  }

  @Test
  public void offWithoutWifi() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(true, true, true);
    p.update(true, false, true);
    if (p.state() != SoulseekSharePolicy.State.OFF) throw new AssertionError("no wifi");
    if (p.acceptNewUploads()) throw new AssertionError("deny");
  }

  @Test
  public void staysActiveWhenScreenOffWhileCharging() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(true, true, true);
    p.update(true, true, false);
    if (p.state() != SoulseekSharePolicy.State.ACTIVE) throw new AssertionError("charging without solar");
    if (!p.acceptNewUploads()) throw new AssertionError("accept while charging");
  }

  @Test
  public void drainingWhenSolarInactiveOnBattery() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(false, true, true);
    p.update(false, true, false);
    if (p.state() != SoulseekSharePolicy.State.DRAINING) throw new AssertionError("drain");
    if (p.acceptNewUploads()) throw new AssertionError("no new");
    if (!p.processUploadQueue()) throw new AssertionError("finish queued");
    p.onUploadQueueEmpty();
    if (p.state() != SoulseekSharePolicy.State.OFF) throw new AssertionError("off after drain");
  }
}
