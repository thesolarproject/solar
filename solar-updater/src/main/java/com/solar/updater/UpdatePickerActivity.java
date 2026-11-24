package com.solar.updater;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.solar.ota.OtaCompanionInstaller;
import com.solar.ota.SolarApkInstaller;
import com.solar.ota.SolarLauncherVersion;
import com.solar.ota.SolarUpdateClient;
import com.solar.ota.net.OtaDownload;
import com.solar.ota.net.OtaTlsHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-06 — Standalone Solar version picker (recovery / rollback path).
 * Layman: pick any published Solar build — works when Solar will not start.
 * Technical: updates.xml only; Rockbox/JJ companion install removed 2026-07-19.
 */
public final class UpdatePickerActivity extends Activity {

    private static final int FIXED_ROWS_BEFORE_SELECTABLE = 4;
    /** 2026-07-19 — No companion install rows (Solar-only). Was: COMPANION_ROW_COUNT=1 Rockbox. */
    private static final int COMPANION_ROW_COUNT = 0;
    private static final int ROW_FIRST_RELEASE = FIXED_ROWS_BEFORE_SELECTABLE + COMPANION_ROW_COUNT;

    private final List<SolarUpdateClient.ReleaseInfo> releases = new ArrayList<SolarUpdateClient.ReleaseInfo>();
    private LinearLayout root;
    private ScrollView scroll;
    private int focusIndex;
    private int loadGen;
    private volatile boolean installing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OtaTlsHelper.init(getApplicationContext());
        scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);
        scroll.addView(root);
        setContentView(scroll);
        rebuildHeader(getString(R.string.updater_loading));
        fetchCatalog();
    }

    private void rebuildHeader(String status) {
        root.removeAllViews();
        releases.clear();

        TextView title = row(getString(R.string.updater_title), true);
        root.addView(title);

        TextView current = row(getString(R.string.updater_current,
                SolarLauncherVersion.displayLabel(this)), false);
        root.addView(current);

        TextView hint = row(getString(R.string.updater_hint), false);
        root.addView(hint);

        TextView statusTv = row(status, false);
        statusTv.setTag("status");
        root.addView(statusTv);

        // 2026-07-19 — Companion Rockbox/JJ rows removed from recovery picker.
        focusIndex = 0;
    }

    private TextView row(CharSequence text, boolean titleStyle) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(titleStyle ? 18 : 14);
        tv.setPadding(8, titleStyle ? 12 : 8, 8, titleStyle ? 12 : 8);
        tv.setFocusable(false);
        return tv;
    }

    private void setStatus(String text) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if ("status".equals(v.getTag()) && v instanceof TextView) {
                ((TextView) v).setText(text);
                return;
            }
        }
    }

    private int selectableCount() {
        return COMPANION_ROW_COUNT + releases.size();
    }

    private void fetchCatalog() {
        if (!isOnline()) {
            setStatus(getString(R.string.updater_offline));
            updateFocus();
            return;
        }
        final int gen = ++loadGen;
        final int localCode = SolarLauncherVersion.installedVersionCode(this);
        final String localName = SolarLauncherVersion.installedVersionName(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<SolarUpdateClient.ReleaseInfo> fetched =
                            SolarUpdateClient.fetchUpdates(BuildConfig.OTA_UPDATES_URL);
                    final List<SolarUpdateClient.ReleaseInfo> picker =
                            SolarUpdateClient.releasesForPicker(fetched, localCode, localName,
                                    SolarUpdateClient.MAX_PICKER_RELEASES);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != loadGen) return;
                            bindReleases(picker);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != loadGen) return;
                            setStatus(getString(R.string.updater_failed));
                            updateFocus();
                        }
                    });
                }
            }
        }, "UpdaterCatalog").start();
    }

    private void bindReleases(List<SolarUpdateClient.ReleaseInfo> picker) {
        while (root.getChildCount() > ROW_FIRST_RELEASE) {
            root.removeViewAt(ROW_FIRST_RELEASE);
        }
        releases.clear();
        if (picker == null || picker.isEmpty()) {
            setStatus(getString(R.string.updater_empty));
            focusIndex = 0;
            updateFocus();
            return;
        }
        releases.addAll(picker);
        int localCode = SolarLauncherVersion.installedVersionCode(this);
        String localName = SolarLauncherVersion.installedVersionName(this);
        for (SolarUpdateClient.ReleaseInfo r : releases) {
            String label = r.listLabel();
            if (r.matchesInstalled(localCode, localName)) label = label + " ✓";
            TextView releaseRow = row(label, false);
            releaseRow.setTag(r);
            root.addView(releaseRow);
        }
        setStatus(releases.size() + " versions");
        focusIndex = 0;
        updateFocus();
    }

    private void updateFocus() {
        int sel = 0;
        for (int i = FIXED_ROWS_BEFORE_SELECTABLE; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if ("companion_header".equals(v.getTag())) continue;
            boolean focused = sel == focusIndex;
            v.setBackgroundColor(focused ? 0x44FFFFFF : 0);
            if (focused && scroll != null) {
                final View focusView = v;
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.requestChildFocus(root, focusView);
                    }
                });
            }
            sel++;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (installing) return true;
        int count = selectableCount();
        if (count == 0) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (focusIndex < count - 1) focusIndex++;
            updateFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (focusIndex > 0) focusIndex--;
            updateFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // 2026-07-19 — Releases only (no companion install at index 0).
            int relIdx = focusIndex - COMPANION_ROW_COUNT;
            if (relIdx >= 0 && relIdx < releases.size()) {
                downloadAndInstall(releases.get(relIdx));
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void installJjLatest() {
        if (!isOnline()) {
            setStatus(getString(R.string.updater_offline));
            return;
        }
        installing = true;
        setStatus(getString(R.string.updater_companion_jj));
        final File workDir = getDir("update", Context.MODE_PRIVATE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = OtaCompanionInstaller.installJjLatest(
                        UpdatePickerActivity.this, workDir,
                        new OtaCompanionInstaller.Progress() {
                            @Override
                            public void onPhase(String phase) {
                                final int res = "jj_install".equals(phase)
                                        ? R.string.updater_companion_installing
                                        : R.string.updater_companion_jj;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        setStatus(getString(res));
                                    }
                                });
                            }
                        });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        installing = false;
                        setStatus(getString(ok
                                ? R.string.updater_companion_jj_done
                                : R.string.updater_companion_jj_failed));
                    }
                });
            }
        }, "UpdaterJjLatest").start();
    }

    private void installRockboxLatest() {
        if (!isOnline()) {
            setStatus(getString(R.string.updater_offline));
            return;
        }
        installing = true;
        setStatus(getString(R.string.updater_companion_jj));
        final File workDir = getDir("update", Context.MODE_PRIVATE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final OtaCompanionInstaller.Result result = OtaCompanionInstaller.installRockboxLatest(
                        UpdatePickerActivity.this, workDir,
                        new OtaCompanionInstaller.Progress() {
                            @Override
                            public void onPhase(String phase) {
                                final int res;if ("rockbox_libs".equals(phase)) {
                                    res = R.string.updater_companion_installing;
                                } else {
                                    res = R.string.updater_companion_installing;
                                }
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        setStatus(getString(res));
                                    }
                                });
                            }
                        });
                final boolean ok = result.jjOk;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        installing = false;
                        setStatus(getString(ok
                                ? R.string.updater_companion_jj_done
                                : R.string.updater_companion_jj_failed));
                    }
                });
            }
        }, "UpdaterRockboxLatest").start();
    }

    private void downloadAndInstall(final SolarUpdateClient.ReleaseInfo release) {
        if (release == null || release.apkUrl == null || release.apkUrl.trim().isEmpty()) return;
        if (!isOnline()) {
            setStatus(getString(R.string.updater_offline));
            return;
        }
        installing = true;
        setStatus(getString(R.string.updater_downloading, release.listLabel()));
        new Thread(new Runnable() {
            @Override
            public void run() {
                File out = null;
                try {
                    final File dir = getDir("update", Context.MODE_PRIVATE);
                    Thread companionThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            OtaCompanionInstaller.downloadAll(UpdatePickerActivity.this, dir,
                                    new OtaCompanionInstaller.Progress() {
                                        @Override
                                        public void onPhase(String phase) {
                                            final int res;
                                            if ("jj".equals(phase)) {
                                                res = R.string.updater_companion_jj;
                                            } else{
                                                res = R.string.updater_companion_installing;
                                            }
                                            runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    setStatus(getString(res));
                                                }
                                            });
                                        }
                                    });
                        }
                    }, "UpdaterCompanionDl");
                    companionThread.start();
                    out = new File(dir, "Solar_Target.apk");
                    OtaDownload.downloadToFile(release.apkUrl, out, "SolarUpdater/1.0");
                    try {
                        companionThread.join();
                    } catch (InterruptedException ignored) {}
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus(getString(R.string.updater_companion_installing));
                        }
                    });
                    final OtaCompanionInstaller.Result companions =
                            OtaCompanionInstaller.installDownloaded(
                                    UpdatePickerActivity.this, dir, null);
                    final File apk = out;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!companions.jjOk) {
                                setStatus(getString(R.string.updater_companion_skipped));
                            } else {
                                setStatus(getString(R.string.updater_installing));
                            }
                        }
                    });
                    final boolean ok = SolarApkInstaller.install(
                            UpdatePickerActivity.this, apk, getAssets());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            installing = false;
                            if (ok) {
                                setStatus(getString(R.string.updater_installed, release.listLabel()));
                            } else {
                                setStatus(getString(R.string.updater_install_failed));
                            }
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            installing = false;
                            setStatus(getString(R.string.updater_install_failed));
                        }
                    });
                }
            }
        }, "UpdaterInstall").start();
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }
}
