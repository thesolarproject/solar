package com.solar.launcher.soulseek;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class SoulseekAccountTest {

  private static final class MemPrefs implements SharedPreferences {
    final Map<String, Object> map = new HashMap<String, Object>();

    @Override public Map<String, ?> getAll() { return map; }
    @Override public String getString(String key, String def) {
      Object v = map.get(key);
      return v instanceof String ? (String) v : def;
    }
    @Override public int getInt(String key, int def) { return def; }
    @Override public long getLong(String key, long def) { return def; }
    @Override public float getFloat(String key, float def) { return def; }
    @Override public boolean getBoolean(String key, boolean def) {
      Object v = map.get(key);
      return v instanceof Boolean ? (Boolean) v : def;
    }
    @Override public Set<String> getStringSet(String key, Set<String> def) { return def; }
    @Override public boolean contains(String key) { return map.containsKey(key); }
    @Override public Editor edit() {
      return new Editor() {
        @Override public Editor putString(String key, String value) { map.put(key, value); return this; }
        @Override public Editor putInt(String key, int value) { map.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { map.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { map.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { map.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> values) { return this; }
        @Override public Editor remove(String key) { map.remove(key); return this; }
        @Override public Editor clear() { map.clear(); return this; }
        @Override public boolean commit() { return true; }
        @Override public void apply() {}
      };
    }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
  }

  @Test
  public void usernameValidation() {
    if (!SoulseekAccount.isValidUsername("solarabcd")) throw new AssertionError("valid");
    if (SoulseekAccount.isValidUsername("")) throw new AssertionError("empty");
    if (SoulseekAccount.isValidUsername("bad name")) throw new AssertionError("space");
    if (SoulseekAccount.isValidUsername("Dré")) throw new AssertionError("accent");
    if (SoulseekAccount.isValidUsername("thisusernameiswaytoolong")) throw new AssertionError("len");
  }

  @Test
  public void resetToAutoRegenerates() {
    MemPrefs prefs = new MemPrefs();
    SoulseekAccount.saveCustom(prefs, "customuser", "secretpass");
    SoulseekAccount reset = SoulseekAccount.resetToAuto(prefs);
    if (reset.custom) throw new AssertionError("should be auto");
    if (!reset.username.startsWith("solar")) throw new AssertionError("username");
    if (reset.password.length() < 8) throw new AssertionError("password");
  }

  @Test
  public void loadReusesStoredAutoAccount() {
    MemPrefs prefs = new MemPrefs();
    prefs.edit()
        .putString(SoulseekAccount.PREF_USER, "solarabcdef")
        .putString(SoulseekAccount.PREF_PASS, "TestPass1234")
        .putBoolean(SoulseekAccount.PREF_CUSTOM, false)
        .commit();
    SoulseekAccount first = SoulseekAccount.load(prefs);
    SoulseekAccount second = SoulseekAccount.load(prefs);
    if (!"solarabcdef".equals(first.username)) throw new AssertionError("username");
    if (!first.username.equals(second.username)) throw new AssertionError("reuse user");
    if (!first.password.equals(second.password)) throw new AssertionError("reuse pass");
    if (first.custom) throw new AssertionError("auto");
  }
}
