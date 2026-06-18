package com.solar.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/** ponytail: app_locale pref overrides system; empty = follow system. */
public final class LocaleHelper {
    public static final String PREF_LOCALE = "app_locale";
    public static final String LOCALE_SYSTEM = "";

    private LocaleHelper() {}

    public static Context wrap(Context base) {
        SharedPreferences prefs = base.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        String tag = prefs.getString(PREF_LOCALE, LOCALE_SYSTEM);
        if ("ko".equals(tag)) {
            prefs.edit().putString(PREF_LOCALE, LOCALE_SYSTEM).commit();
            tag = LOCALE_SYSTEM;
        }
        Locale locale = localeFromTag(tag, base);
        if (locale == null) return base;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            return base.createConfigurationContext(config);
        }
        config.locale = locale;
        base.getResources().updateConfiguration(config, base.getResources().getDisplayMetrics());
        return base;
    }

    public static Locale getEffectiveLocale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("SOLAR_SETTINGS", Context.MODE_PRIVATE);
        Locale loc = localeFromTag(prefs.getString(PREF_LOCALE, LOCALE_SYSTEM), context);
        return loc != null ? loc : Locale.getDefault();
    }

    public static String displayLabel(Context context, String localeTag) {
        if (LOCALE_SYSTEM.equals(localeTag)) return context.getString(R.string.language_system);
        if ("en".equals(localeTag)) return context.getString(R.string.language_english);
        if ("ko".equals(localeTag)) return context.getString(R.string.language_korean);
        return localeTag;
    }

    private static Locale localeFromTag(String tag, Context context) {
        if (tag == null || LOCALE_SYSTEM.equals(tag)) return null;
        if ("ko".equals(tag)) return null; // disabled until Hangul wheel input
        if ("en".equals(tag)) return Locale.ENGLISH;
        return new Locale(tag);
    }
}
