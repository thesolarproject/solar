package com.solar.launcher.homehelper;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.solar.home.policy.HomeTargetPolicy;
import com.solar.home.policy.LauncherCompetitionPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 2026-07-07 — Wheel-friendly HOME picker fallback (Solar overlay is primary).
 * Layman: hold Back/Power, pick any Android HOME app — choice sticks across reboots.
 * Technical: PM CATEGORY_HOME scan + Solar/Rockbox/JJ/Stock rows; custom uses persist component.
 * Reversal: finish-only stub; Solar OverlayModalHost.showLauncherPickerMode owns UI again.
 */
public final class LauncherPickerActivity extends Activity {

    private static final String SOLAR_SET_HOME_ACTION = "com.solar.launcher.action.SET_PREFERRED_HOME";
    private static final String SOLAR_SET_HOME_EXTRA = "target";
    private static final String SOLAR_HOME_RECEIVER = "com.solar.launcher.LauncherHomeReceiver";

    private final ArrayList<String> targets = new ArrayList<String>();
    /** pkg/activity flat for {@link HomeTargetPolicy#TARGET_CUSTOM} rows — empty for named targets. */
    private final ArrayList<String> customComponents = new ArrayList<String>();
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView title = new TextView(this);
        title.setText(R.string.picker_title);
        title.setPadding(24, 24, 24, 12);
        listView = new ListView(this);
        listView.setDividerHeight(1);
        buildRows();
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(title);
        root.addView(listView, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                applySelection(position);
            }
        });
        if (listView.getCount() > 0) {
            listView.setSelection(findCurrentIndex());
            listView.requestFocus();
        }
    }

    /**
     * Build Solar / JJ / Stock / PM-discovered HOME rows — skip helper middle-man.
     * 2026-07-08 — Stock Innioasis row was missing; custom scan alone skipped dedicated stock pkgs.
     * Reversal: drop stock block (only solar/jj + custom).
     */
    private void buildRows() {
        ArrayList<String> labels = new ArrayList<String>();
        targets.clear();
        customComponents.clear();
        labels.add(getString(R.string.picker_solar));
        targets.add(HomeTargetPolicy.TARGET_SOLAR);
        customComponents.add("");
        if (isInstalled(HomeTargetPolicy.JJ_PKG)) {
            labels.add(getString(R.string.picker_jj));
            targets.add(HomeTargetPolicy.TARGET_JJ);
            customComponents.add("");
        }
        // 2026-07-08 — Factory HOME when either family package is present on the device.
        if (isInstalled(HomeTargetPolicy.INNIOASIS_Y1_PKG)
                || isInstalled(HomeTargetPolicy.INNIOASIS_Y2_PKG)) {
            labels.add(getString(R.string.picker_stock));
            targets.add(HomeTargetPolicy.TARGET_STOCK);
            customComponents.add("");
        }
        appendDiscoveredHomeLaunchers(labels);
        listView.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_activated_1,
                android.R.id.text1, labels));
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
    }

    /** PM scan — any MAIN+HOME APK except Solar/Rockbox/JJ/helper/companion. */
    private void appendDiscoveredHomeLaunchers(ArrayList<String> labels) {
        PackageManager pm = getPackageManager();
        if (pm == null) return;
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> matches = pm.queryIntentActivities(probe, 0);
        if (matches == null || matches.isEmpty()) return;
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        ArrayList<ResolveInfo> custom = new ArrayList<ResolveInfo>();
        for (ResolveInfo info : matches) {
            if (info == null || info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (LauncherCompetitionPolicy.isPlatformHomePackage(pkg)) continue;
            if (LauncherCompetitionPolicy.targetForPackage(pkg) != null) continue;
            String flat = HomeTargetPolicy.flattenComponent(pkg, info.activityInfo.name);
            if (!seen.add(flat)) continue;
            custom.add(info);
        }
        Collections.sort(custom, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                CharSequence la = a.loadLabel(pm);
                CharSequence lb = b.loadLabel(pm);
                return String.valueOf(la).compareToIgnoreCase(String.valueOf(lb));
            }
        });
        for (ResolveInfo info : custom) {
            CharSequence label = info.loadLabel(pm);
            labels.add(label != null ? label.toString() : info.activityInfo.packageName);
            targets.add(HomeTargetPolicy.TARGET_CUSTOM);
            customComponents.add(HomeTargetPolicy.flattenComponent(
                    info.activityInfo.packageName, info.activityInfo.name));
        }
    }

    private int findCurrentIndex() {
        String current = LauncherHomeActivity.readSystemProperty(
                HomeTargetPolicy.PROP_HOME_TARGET, HomeTargetPolicy.TARGET_SOLAR);
        current = HomeTargetPolicy.normalizeTarget(current);
        if (HomeTargetPolicy.TARGET_CUSTOM.equals(current)) {
            String flat = LauncherHomeActivity.readSystemProperty(
                    HomeTargetPolicy.PROP_HOME_COMPONENT, "");
            for (int i = 0; i < targets.size(); i++) {
                if (HomeTargetPolicy.TARGET_CUSTOM.equals(targets.get(i))
                        && flat.equals(customComponents.get(i))) {
                    return i;
                }
            }
        }
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).equals(current)) return i;
        }
        return 0;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85) {
            if (event.getRepeatCount() == 0) {
                applySelection(listView.getSelectedItemPosition());
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Persist HOME via Solar receiver, then launch the chosen app. */
    private void applySelection(int index) {
        if (index < 0 || index >= targets.size()) return;
        String target = targets.get(index);
        String component = customComponents.get(index);
        SetHomeTargetReceiver.setSystemProperty(HomeTargetPolicy.PROP_HOME_TARGET, target);
        if (HomeTargetPolicy.TARGET_CUSTOM.equals(target) && component != null && component.length() > 0) {
            SetHomeTargetReceiver.setSystemProperty(HomeTargetPolicy.PROP_HOME_COMPONENT, component);
        }
        Intent apply = new Intent(SOLAR_SET_HOME_ACTION);
        apply.setComponent(new ComponentName(HomeTargetPolicy.SOLAR_PKG, SOLAR_HOME_RECEIVER));
        apply.putExtra(SOLAR_SET_HOME_EXTRA, target);
        if (HomeTargetPolicy.TARGET_CUSTOM.equals(target)) {
            apply.putExtra(HomeTargetPolicy.EXTRA_HOME_COMPONENT, component);
        }
        apply.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try {
            sendBroadcast(apply);
        } catch (Exception ignored) {}
        LauncherHomeActivity.launchSavedTarget(getApplicationContext());
        finish();
    }

    private boolean isInstalled(String pkg) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            return info != null;
        } catch (Exception e) {
            return false;
        }
    }
}
