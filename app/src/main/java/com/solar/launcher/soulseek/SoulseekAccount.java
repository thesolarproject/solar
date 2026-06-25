package com.solar.launcher.soulseek;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.solar.launcher.DeviceFeatures;

import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/** Stores custom or auto-generated Soulseek credentials (friend-code style for auto accounts). */
public final class SoulseekAccount {
    public static final String PREF_USER = "soulseek_user";
    public static final String PREF_PASS = "soulseek_pass";
    public static final String PREF_CUSTOM = "soulseek_custom";
    public static final String PREF_REACH_ENABLED = "soulseek_reach_enabled";
    public static final String PREF_SOULSEEK_ENABLED = "soulseek_enabled";
    public static final String PREF_MESSAGING_ENABLED = "soulseek_messaging_enabled";
    /** @deprecated migrated to {@link #PREF_HIDE_HIGH_BITRATE} */
    public static final String PREF_HIDE_FLAC = "soulseek_hide_flac";
    public static final String PREF_HIDE_HIGH_BITRATE = "soulseek_hide_high_bitrate";
    public static final String PREF_SHARING_ENABLED = "soulseek_sharing_enabled";
    public static final String PREF_INCLUDE_IN_GET_MUSIC = "soulseek_include_in_get_music";

    private static final Pattern USERNAME_OK =
            Pattern.compile("^[A-Za-z0-9_-]{1,20}$");
    private static final Pattern FRIEND_CODE_Y1 =
            Pattern.compile("^Y1-[a-z]{3,5}-[a-z]{3,5}-[0-9]{2}$");
    private static final Pattern FRIEND_CODE_Y2 =
            Pattern.compile("^Y2-[a-z]{3,5}-[a-z]{3,5}-[a-z]{3,5}$");
    /** Hash-based letter segments from earlier Reach builds. */
    private static final Pattern FRIEND_CODE_HASH_Y1 =
            Pattern.compile("^Y1-[a-z]{5}-[a-z]{4}-[0-9]{2}$");
    private static final Pattern FRIEND_CODE_HASH_Y2 =
            Pattern.compile("^Y2-[a-z]{4}-[a-z]{4}-[a-z]{4}$");
    /** Legacy uppercase friend codes from earlier Reach builds. */
    private static final Pattern FRIEND_CODE_LEGACY =
            Pattern.compile("^(Y1|Y2)-[A-Z2-9]{5}-[A-Z2-9]{4}$");
    /** Fixed auto password — identical on every device for auto accounts. */
    private static final String AUTO_PASSWORD = "ReachAutoShare2024";

    public final String username;
    public final String password;
    public final boolean custom;

    private SoulseekAccount(String username, String password, boolean custom) {
        this.username = username;
        this.password = password;
        this.custom = custom;
    }

    public static SoulseekAccount load(SharedPreferences prefs) {
        return load(prefs, null);
    }

    public static SoulseekAccount load(SharedPreferences prefs, Context context) {
        boolean custom = prefs.getBoolean(PREF_CUSTOM, false);
        String user = prefs.getString(PREF_USER, "");
        String pass = prefs.getString(PREF_PASS, "");
        if (custom && user != null && !user.trim().isEmpty() && pass != null && !pass.isEmpty()) {
            return new SoulseekAccount(user.trim(), pass, true);
        }
        if (user != null && !user.isEmpty() && pass != null && !pass.isEmpty()) {
            return new SoulseekAccount(user, pass, false);
        }
        user = generateUsername(context, false);
        pass = generateAutoPassword();
        prefs.edit()
                .putString(PREF_USER, user)
                .putString(PREF_PASS, pass)
                .putBoolean(PREF_CUSTOM, false)
                .commit();
        return new SoulseekAccount(user, pass, false);
    }

    public static boolean isValidUsername(String username) {
        if (username == null) return false;
        String t = username.trim();
        return USERNAME_OK.matcher(t).matches();
    }

    public static boolean isFriendCode(String username) {
        if (username == null) return false;
        String t = username.trim();
        return FRIEND_CODE_Y1.matcher(t).matches()
                || FRIEND_CODE_Y2.matcher(t).matches()
                || FRIEND_CODE_HASH_Y1.matcher(t).matches()
                || FRIEND_CODE_HASH_Y2.matcher(t).matches()
                || FRIEND_CODE_LEGACY.matcher(t).matches();
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
    public static SoulseekAccount resetToAuto(SharedPreferences prefs, Context context) {
        clearCustom(prefs);
        String user = generateUsername(context, true);
        String pass = generateAutoPassword();
        prefs.edit()
                .putString(PREF_USER, user)
                .putString(PREF_PASS, pass)
                .putBoolean(PREF_CUSTOM, false)
                .commit();
        return new SoulseekAccount(user, pass, false);
    }

    public static SoulseekAccount resetToAuto(SharedPreferences prefs) {
        return resetToAuto(prefs, null);
    }

    static String generateUsername(Context context) {
        return generateUsername(context, false);
    }

    static String generateUsername(Context context, boolean fresh) {
        String prefix = DeviceFeatures.isY2() ? "Y2" : "Y1";
        String deviceId = deviceId(context);
        String seed = prefix + ":" + deviceId;
        if (fresh) {
            seed += ":" + System.nanoTime();
        }
        byte[] hash = sha256(utf8Bytes(seed));
        if (DeviceFeatures.isY2()) {
            return String.format(Locale.US, "Y2-%s-%s-%s",
                    SoulseekWordDictionary.pickWord(context, hash, 0),
                    SoulseekWordDictionary.pickWord(context, hash, 1),
                    SoulseekWordDictionary.pickWord(context, hash, 2));
        }
        int nn = (hash[10] & 0xff) % 100;
        return String.format(Locale.US, "Y1-%s-%s-%02d",
                SoulseekWordDictionary.pickWord(context, hash, 0),
                SoulseekWordDictionary.pickWord(context, hash, 1),
                nn);
    }

    static String generateAutoPassword() {
        return AUTO_PASSWORD;
    }

    private static byte[] utf8Bytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    private static String deviceId(Context context) {
        if (context != null) {
            try {
                String id = Settings.Secure.getString(context.getContentResolver(),
                        Settings.Secure.ANDROID_ID);
                if (id != null && !id.isEmpty() && !"9774d56d682e549c".equals(id)) {
                    return id;
                }
            } catch (Exception ignored) {}
        }
        return "solar-reach-default";
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            byte[] fallback = new byte[32];
            for (int i = 0; i < fallback.length; i++) {
                fallback[i] = (byte) (input[i % input.length] ^ i);
            }
            return fallback;
        }
    }

    public static String displayLabel(SoulseekAccount account) {
        if (account == null) return "Auto account";
        if (account.custom) return account.username;
        return account.username;
    }

    public static boolean includeInGetMusic(SharedPreferences prefs) {
        if (prefs == null) return true;
        return prefs.getBoolean(PREF_INCLUDE_IN_GET_MUSIC, true);
    }
}
