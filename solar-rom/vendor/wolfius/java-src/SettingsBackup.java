package io.github.gohoski.wolfius;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;

class SettingsBackup {
    private static final String TAG = "SettingsBackup";
    private static final String BACKUP_FILE = "WolfiusSettings.xml";
    private static final String MARKER_FILE = ".settings_restored";

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    static void backup(Context context) {
        File extDir = Environment.getExternalStorageDirectory();
        if (extDir == null) return;
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return;

        SharedPreferences prefs = getPrefs(context);
        Map<String, ?> all = prefs.getAll();
        if (all.isEmpty()) return;

        Properties props = new Properties();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            props.setProperty(entry.getKey(), String.valueOf(entry.getValue()));
        }

        File backupFile = new File(extDir, BACKUP_FILE);
        try {
            FileOutputStream fos = new FileOutputStream(backupFile);
            props.storeToXML(fos, null);
            fos.close();
            Log.i(TAG, "Settings backed up to " + backupFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to back up settings", e);
        }
    }

    static void restore(Context context) {
        File marker = new File(context.getFilesDir(), MARKER_FILE);
        if (marker.exists()) return;

        File extDir = Environment.getExternalStorageDirectory();
        if (extDir == null) return;
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return;

        File backupFile = new File(extDir, BACKUP_FILE);
        if (!backupFile.exists()) return;

        Properties props = new Properties();
        try {
            FileInputStream fis = new FileInputStream(backupFile);
            props.loadFromXML(fis);
            fis.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read backup", e);
            return;
        }

        SharedPreferences prefs = getPrefs(context);
        SharedPreferences.Editor editor = prefs.edit();

        Enumeration<?> keys = props.propertyNames();
        while (keys.hasMoreElements()) {
            String key = (String) keys.nextElement();
            String value = props.getProperty(key);
            if ("true".equals(value) || "false".equals(value)) {
                editor.putBoolean(key, Boolean.parseBoolean(value));
            } else {
                editor.putString(key, value);
            }
        }
        editor.commit();

        try {
            marker.getParentFile().mkdirs();
            marker.createNewFile();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create restore marker", e);
        }

        Log.i(TAG, "Settings restored from " + backupFile.getAbsolutePath());
    }
}