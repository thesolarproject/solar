package com.solar.launcher.soulseek;

import org.junit.Test;

public class SoulseekSharePolicyTest {

  @Test
  public void offWhenUserDisabled() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.setUserEnabled(false);
    p.update(true, true, false);
    if (p.state() != SoulseekSharePolicy.State.OFF) throw new AssertionError("user off");
    if (p.announceShares()) throw new AssertionError("no announce");
    if (p.acceptNewUploads()) throw new AssertionError("no uploads");
  }

  @Test
  public void offWhenReachMasterDisabled() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.setReachMasterEnabled(false);
    p.update(true, true, false);
    if (p.state() != SoulseekSharePolicy.State.OFF) throw new AssertionError("master off");
  }

  @Test
  public void activeOnWifiWithNatRegardlessOfBattery() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(true, true, false);
    if (p.state() != SoulseekSharePolicy.State.ACTIVE) throw new AssertionError("wifi+nat");
    if (!p.acceptNewUploads()) throw new AssertionError("accept");
    if (!p.announceShares()) throw new AssertionError("announce");
  }

  @Test
  public void staysActiveOnBatteryWithoutReachUi() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(true, true, false);
    if (p.state() != SoulseekSharePolicy.State.ACTIVE) throw new AssertionError("battery bg");
    if (!p.acceptNewUploads()) throw new AssertionError("accept on battery");
  }

  @Test
  public void offWithoutNatEvenOnBattery() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(true, false, false);
    if (p.state() != SoulseekSharePolicy.State.OFF) throw new AssertionError("no nat");
    if (p.announceShares()) throw new AssertionError("no announce");
    if (p.acceptNewUploads()) throw new AssertionError("no uploads");
  }

  @Test
  public void drainingWhenNatLostDuringUpload() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(true, true, false);
    p.update(true, false, true);
    if (p.state() != SoulseekSharePolicy.State.DRAINING) throw new AssertionError("drain");
    if (p.acceptNewUploads()) throw new AssertionError("no new uploads");
    if (!p.processUploadQueue()) throw new AssertionError("finish queued");
    p.onUploadQueueEmpty();
    if (p.state() != SoulseekSharePolicy.State.OFF) throw new AssertionError("off after upload");
  }

  @Test
  public void offWithoutWifi() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(true, true, false);
    p.update(false, true, false);
    if (p.state() != SoulseekSharePolicy.State.OFF) throw new AssertionError("no wifi");
    if (p.acceptNewUploads()) throw new AssertionError("deny");
  }

  @Test
  public void noAnnounceWithoutWifiOrNat() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.update(false, true, false);
    if (p.announceShares()) throw new AssertionError("no wifi");
    p.update(true, false, false);
    if (p.announceShares()) throw new AssertionError("no nat");
  }

  @Test
  public void messagingKeepsClientAliveWhenSharingOff() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.setUserEnabled(false);
    p.setMessagingEnabled(true);
    p.update(true, true, false);
    if (p.announceShares()) throw new AssertionError("user sharing off");
    if (!p.shouldKeepClientAlive()) throw new AssertionError("messaging keepalive");
  }

  @Test
  public void messagingKeepsAliveWithoutNat() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.setMessagingEnabled(true);
    p.update(true, false, false);
    if (!p.shouldKeepClientAlive()) throw new AssertionError("messaging needs server not NAT");
  }

  @Test
  public void sharingKeepsAliveWithoutNatForNatProbe() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.setMessagingEnabled(false);
    p.update(true, false, false);
    if (!p.shouldKeepClientAlive()) throw new AssertionError("sharing needs connect for NAT probe");
    if (p.announceShares()) throw new AssertionError("no server announce without nat");
    if (!p.serveSharesToPeer()) throw new AssertionError("serve browse when sharing on");
  }

  @Test
  public void serveSharesToPeerFalseWhenSharingDisabled() {
    SoulseekSharePolicy p = new SoulseekSharePolicy();
    p.setUserEnabled(false);
    p.update(true, true, false);
    if (p.serveSharesToPeer()) throw new AssertionError("user sharing off");
  }
}
