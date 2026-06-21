package com.solar.launcher.soulseek;

import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/** Stores custom or auto-generated Soulseek credentials. */
public final class SoulseekAccount {
    public static final String PREF_USER = "soulseek_user";
    public static final String PREF_PASS = "soulseek_pass";
    public static final String PREF_CUSTOM = "soulseek_custom";
    public static final String PREF_HIDE_FLAC = "soulseek_hide_flac";
    public static final String PREF_SHARING_ENABLED = "soulseek_sharing_enabled";

    private static final Pattern USERNAME_OK =
            Pattern.compile("^[A-Za-z0-9_-]{1,20}$");

    public final String username;
    public final String password;
    public final boolean custom;

    private SoulseekAccount(String username, String password, boolean custom) {
        this.username = username;
        this.password = password;
        this.custom = custom;
    }

    public static SoulseekAccount load(SharedPreferences prefs) {
        boolean custom = prefs.getBoolean(PREF_CUSTOM, false);
        String user = prefs.getString(PREF_USER, "");
        String pass = prefs.getString(PREF_PASS, "");
        if (custom && user != null && !user.trim().isEmpty() && pass != null && !pass.isEmpty()) {
            return new SoulseekAccount(user.trim(), pass, true);
        }
        if (user == null || user.isEmpty() || pass == null || pass.isEmpty()) {
            user = generateUsername();
            pass = generatePassword();
            prefs.edit()
                    .putString(PREF_USER, user)
                    .putString(PREF_PASS, pass)
                    .putBoolean(PREF_CUSTOM, false)
                    .commit();
        }
        return new SoulseekAccount(user, pass, false);
    }

    public static boolean isValidUsername(String username) {
        if (username == null) return false;
        return USERNAME_OK.matcher(username.trim()).matches();
    }

    public static void saveCustom(SharedPreferences prefs, String username, String password) {
        prefs.edit()
                .putString(PREF_USER, username.trim())
                .putString(PREF_PASS, password)
                .putBoolean(PREF_CUSTOM, true)
                .commit();
    }

    public static void clearCustom(SharedPreferences prefs) {
        prefs.edit()
                .remove(PREF_USER)
                .remove(PREF_PASS)
                .putBoolean(PREF_CUSTOM, false)
                .commit();
    }

    /** Clear custom creds and persist a fresh auto-generated account. */
    public static SoulseekAccount resetToAuto(SharedPreferences prefs) {
        clearCustom(prefs);
        return load(prefs);
    }

    static String generateUsername() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder("solar");
        for (int i = 0; i < 8; i++) sb.append((char) ('a' + r.nextInt(26)));
        return sb.toString();
    }

    static String generatePassword() {
        SecureRandom r = new SecureRandom();
        String chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    public static String displayLabel(SoulseekAccount account) {
        if (account == null) return "Auto account";
        if (account.custom) return account.username;
        return account.username + " (auto)";
    }
}
