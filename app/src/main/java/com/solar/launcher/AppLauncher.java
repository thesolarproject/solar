package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ponytail: launchable apps from PackageManager — sorted labels, excludes self. */
public final class AppLauncher {

    public static final class Entry implements Comparable<Entry> {
        public final String label;
        public final String packageName;

        public Entry(String label, String packageName) {
            this.label = label != null ? label : "";
            this.packageName = packageName;
        }

        @Override
        public int compareTo(Entry other) {
            return label.compareToIgnoreCase(other.label);
        }
    }

    private AppLauncher() {}

    public static List<Entry> load(PackageManager pm, String excludePackage) {
        List<Entry> out = new ArrayList<Entry>();
        if (pm == null) return out;
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        if (installed == null) return out;
        for (ApplicationInfo info : installed) {
            if (info == null || info.packageName == null) continue;
            if (excludePackage != null && excludePackage.equals(info.packageName)) continue;
            Intent launch = pm.getLaunchIntentForPackage(info.packageName);
            if (launch == null) continue;
            CharSequence name = pm.getApplicationLabel(info);
            String label = name != null ? name.toString() : info.packageName;
            out.add(new Entry(label, info.packageName));
        }
        Collections.sort(out);
        return out;
    }

    public static boolean launch(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        PackageManager pm = context.getPackageManager();
        Intent launch = pm != null ? pm.getLaunchIntentForPackage(packageName) : null;
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        return true;
    }
}
