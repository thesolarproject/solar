package com.solar.launcher.soulseek;

import org.junit.Test;

public class SolarDeveloperAccountsTest {

  @Test
  public void developerUsernamesMatchCaseInsensitive() {
    if (!SolarDeveloperAccounts.isDeveloper("SolarDev")) throw new AssertionError("SolarDev");
    if (!SolarDeveloperAccounts.isDeveloper("thesolarphone")) throw new AssertionError("phone");
    if (!SolarDeveloperAccounts.isDeveloper("ThesolarY1")) throw new AssertionError("Y1");
    if (SolarDeveloperAccounts.isDeveloper("randomuser")) throw new AssertionError("not dev");
  }

  @Test
  public void diagUsernameTruncatesToTwentyChars() {
    String u = SolarDeveloperAccounts.deriveDiagUsername("Y1-verylongusername-99");
    if (u.length() > 20) throw new AssertionError("len=" + u.length());
    if (!u.endsWith("-diag")) throw new AssertionError("suffix: " + u);
  }

  @Test
  public void hideFromReachUiCoversDevAndDiagNotVirtualPeer() {
    if (!SolarDeveloperAccounts.hideFromReachUi("SolarDev")) throw new AssertionError("dev");
    if (!SolarDeveloperAccounts.hideFromReachUi("user-diag")) throw new AssertionError("diag");
    if (SolarDeveloperAccounts.hideFromReachUi(SolarDeveloperAccounts.VIRTUAL_PEER)) {
      throw new AssertionError("virtual peer visible in UI");
    }
    if (SolarDeveloperAccounts.hideFromReachUi("Y1-foo-bar-03")) {
      throw new AssertionError("normal user");
    }
  }

  @Test
  public void virtualPeerConstant() {
    if (!SolarDeveloperAccounts.isVirtualPeer(SolarDeveloperAccounts.VIRTUAL_PEER)) {
      throw new AssertionError("virtual");
    }
  }

  @Test
  public void resolveContactInputMapsFriendlyNames() {
    if (!SolarDeveloperAccounts.VIRTUAL_PEER.equals(
            SolarDeveloperAccounts.resolveContactInput("Solar Development"))) {
      throw new AssertionError("display name");
    }
    if (!SolarDeveloperAccounts.VIRTUAL_PEER.equals(
            SolarDeveloperAccounts.resolveContactInput("solar dev"))) {
      throw new AssertionError("solar dev alias");
    }
    if (!SolarDeveloperAccounts.VIRTUAL_PEER.equals(
            SolarDeveloperAccounts.resolveContactInput("SolarDev"))) {
      throw new AssertionError("wire dev maps to proxy");
    }
    if (!SolarDeveloperAccounts.isAggregatedDeveloperQuery("Solar Development")) {
      throw new AssertionError("aggregated query");
    }
  }

  @Test
  public void matchesDeveloperSearchQueryIncludesWireNames() {
    if (!SolarDeveloperAccounts.matchesDeveloperSearchQuery("SolarDev")) {
      throw new AssertionError("SolarDev search");
    }
    if (!SolarDeveloperAccounts.matchesDeveloperSearchQuery("thesolarphone")) {
      throw new AssertionError("phone search");
    }
    if (!SolarDeveloperAccounts.matchesDeveloperSearchQuery("ThesolarY1")) {
      throw new AssertionError("Y1 search");
    }
    if (SolarDeveloperAccounts.matchesDeveloperSearchQuery("randomuser")) {
      throw new AssertionError("not dev");
    }
  }

  @Test
  public void previewTextStripsDevFromMarker() {
    String packed = SolarDeveloperAccounts.packDevIncoming("SolarDev", "Hello");
    String preview = SolarDeveloperAccounts.previewText(packed);
    if (!"Hello".equals(preview)) throw new AssertionError("preview=" + preview);
  }

  @Test
  public void experimentDefaultsOnWhenPrefAbsent() {
    android.content.SharedPreferences prefs = new android.content.SharedPreferences() {
      @Override public java.util.Map<String, ?> getAll() { return null; }
      @Override public String getString(String key, String defValue) { return defValue; }
      @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) { return defValues; }
      @Override public int getInt(String key, int defValue) { return defValue; }
      @Override public long getLong(String key, long defValue) { return defValue; }
      @Override public float getFloat(String key, float defValue) { return defValue; }
      @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
      @Override public boolean contains(String key) { return false; }
      @Override public Editor edit() { return null; }
      @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
      @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    };
    if (!SolarDeveloperAccounts.isExperimentEnabled(prefs)) {
      throw new AssertionError("default should be on");
    }
  }

  @Test
  public void devIncomingPackParseRoundTrip() {
    String packed = SolarDeveloperAccounts.packDevIncoming("SolarDev", "Hello there");
    if (!SolarDeveloperAccounts.isDevIncoming(packed)) throw new AssertionError("marked");
    SolarDeveloperAccounts.DevIncoming parsed = SolarDeveloperAccounts.parseDevIncoming(packed);
    if (!"SolarDev".equals(parsed.fromDev)) throw new AssertionError("from=" + parsed.fromDev);
    if (!"Hello there".equals(parsed.body)) throw new AssertionError("body=" + parsed.body);
    if (!"Hello there".equals(SolarDeveloperAccounts.displayBody(packed))) {
      throw new AssertionError("display");
    }
    SolarDeveloperAccounts.DevIncoming plain =
            SolarDeveloperAccounts.parseDevIncoming("plain text");
    if (!plain.fromDev.isEmpty()) throw new AssertionError("plain from");
    if (!"plain text".equals(plain.body)) throw new AssertionError("plain body");
  }

  @Test
  public void wireRecipientsDevSenderSkipsSelf() {
    String[] t = SolarDeveloperAccounts.wireRecipientsForSender("SolarDev");
    if (t.length != 2) throw new AssertionError("count=" + t.length);
    for (String u : t) {
      if ("SolarDev".equalsIgnoreCase(u)) throw new AssertionError("self included");
    }
    String[] all = SolarDeveloperAccounts.wireRecipientsForSender("normaluser");
    if (all.length != 3) throw new AssertionError("user fanout=" + all.length);
  }
}
