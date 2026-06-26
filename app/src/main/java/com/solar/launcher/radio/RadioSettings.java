package com.solar.launcher.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;

import java.util.Locale;

/** FM + internet radio user prefs — band region, country filter, buffer location. */
public final class RadioSettings {
  public static final String PREF_FM_BAND_REGION = "fm_band_region";
  public static final String PREF_INTERNET_RADIO_COUNTRY = "internet_radio_country";
  public static final String PREF_AUTO_DETECT_REGION = "auto_detect_region";
  public static final String PREF_BUFFER_ON_SD = "buffer_on_sd";

  private static final String PREFS = "radio_settings";
  private static final String DEFAULT_REGION = "US";
  private static final String DEFAULT_COUNTRY = "US";

  private RadioSettings() {}

  private static SharedPreferences prefs(Context ctx) {
    return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public static String getFmBandRegion(Context ctx) {
    return normalizeRegion(prefs(ctx).getString(PREF_FM_BAND_REGION, DEFAULT_REGION));
  }

  public static void setFmBandRegion(Context ctx, String region) {
    prefs(ctx).edit().putString(PREF_FM_BAND_REGION, normalizeRegion(region)).commit();
  }

  public static String getInternetRadioCountry(Context ctx) {
    return normalizeCountry(prefs(ctx).getString(PREF_INTERNET_RADIO_COUNTRY, DEFAULT_COUNTRY));
  }

  public static void setInternetRadioCountry(Context ctx, String country) {
    prefs(ctx).edit().putString(PREF_INTERNET_RADIO_COUNTRY, normalizeCountry(country)).commit();
  }

  public static boolean getAutoDetectRegion(Context ctx) {
    return prefs(ctx).getBoolean(PREF_AUTO_DETECT_REGION, true);
  }

  public static void setAutoDetectRegion(Context ctx, boolean enabled) {
    prefs(ctx).edit().putBoolean(PREF_AUTO_DETECT_REGION, enabled).commit();
  }

  public static boolean getBufferOnSd(Context ctx) {
    return prefs(ctx).getBoolean(PREF_BUFFER_ON_SD, true);
  }

  public static void setBufferOnSd(Context ctx, boolean onSd) {
    prefs(ctx).edit().putBoolean(PREF_BUFFER_ON_SD, onSd).commit();
  }

  /**
   * Guess FM band from SIM/network ISO, then device locale.
   * ponytail: coarse ISO→region map — add per-country overrides if users report wrong band.
   */
  public static String detectFmBandFromLocale(Context ctx) {
    String iso = null;
    if (ctx != null) {
      try {
        TelephonyManager tm =
            (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
          String network = tm.getNetworkCountryIso();
          if (network != null && network.length() == 2) iso = network;
          if (iso == null || iso.length() != 2) {
            String sim = tm.getSimCountryIso();
            if (sim != null && sim.length() == 2) iso = sim;
          }
        }
      } catch (Exception ignored) {}
    }
    if (iso == null || iso.length() != 2) {
      Locale locale = Locale.getDefault();
      if (locale != null && locale.getCountry() != null && locale.getCountry().length() == 2) {
        iso = locale.getCountry();
      }
    }
    return isoToFmRegion(iso);
  }

  /** ponytail: testable without TelephonyManager */
  static String isoToFmRegion(String iso) {
    if (iso == null || iso.length() != 2) return DEFAULT_REGION;
    String c = iso.toUpperCase(Locale.US);
    if ("US".equals(c) || "CA".equals(c) || "MX".equals(c)) return "US";
    if ("JP".equals(c)) return "JP";
    if ("AU".equals(c) || "NZ".equals(c)) return "AU";
    if ("KR".equals(c)) return "KR";
    if ("RU".equals(c)) return "RU";
    // EU + most of world uses 87.5–108 MHz plan
    return "EU";
  }

  static String normalizeRegion(String region) {
    if (region == null || region.trim().isEmpty()) return DEFAULT_REGION;
    return region.trim().toUpperCase(Locale.US);
  }

  static String normalizeCountry(String country) {
    if (country == null || country.trim().isEmpty()) return DEFAULT_COUNTRY;
    return country.trim().toUpperCase(Locale.US);
  }
}
