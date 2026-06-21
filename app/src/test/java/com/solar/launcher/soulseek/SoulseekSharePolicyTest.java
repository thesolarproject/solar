package com.solar.launcher.soulseek;

import org.junit.Test;

public class SoulseekSharePolicyTest {

  @Test
  public void offWhenUserDisabled() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.setUserEnabled(false);
    p.update(true, true, true);
    if (p.state() != SoulseekSharePolicy.State.OFF) throw new AssertionError("user off");
    if (p.announceShares()) throw new AssertionError("no announce");
    if (p.acceptNewUploads()) throw new AssertionError("no uploads");
  }

  @Test
  public void activeWhenChargingAndWifi() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(true, true, false);
    if (p.state() != SoulseekSharePolicy.State.ACTIVE) throw new AssertionError("charging");
    if (!p.acceptNewUploads()) throw new AssertionError("accept");
    if (!p.announceShares()) throw new AssertionError("announce");
  }

  @Test
  public void activeWhenReachUiAndWifi() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(false, true, true);
    if (p.state() != SoulseekSharePolicy.State.ACTIVE) throw new AssertionError("reach ui");
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
  public void staysActiveWhenLeaveReachWhileCharging() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(true, true, true);
    p.update(true, true, false);
    if (p.state() != SoulseekSharePolicy.State.ACTIVE) throw new AssertionError("charging without reach");
    if (!p.acceptNewUploads()) throw new AssertionError("accept while charging");
  }

  @Test
  public void drainingWhenLeaveReachOnBattery() {
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
