package io.github.gohoski.wolfius;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Created by Gleb on 19.07.2026.
 */
public class WelcomeActivity extends Activity {
    private TextView tvTitle;
    private TextView tvBody;
    private Button btnNext;
    private Button btnSkip;
    private ProgressBar progressBar;

    private int currentStep = 1;
    private boolean hasRootDetected = false;
    private String detectedMethod = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        tvTitle = (TextView) findViewById(R.id.tv_title);
        tvBody = (TextView) findViewById(R.id.tv_body);
        btnNext = (Button) findViewById(R.id.btn_next);
        btnSkip = (Button) findViewById(R.id.btn_skip);
        progressBar = (ProgressBar) findViewById(R.id.progress_bar);

        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentStep == 1) {
                    btnNext.setEnabled(false);
                    progressBar.setVisibility(View.VISIBLE);
                    new SetupCheckTask().execute();
                } else if (currentStep == 2) {
                    if (hasRootDetected) {
                        installRootCa();
                    } else {
                        finishOnboarding();
                    }
                }
            }
        });

        btnSkip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentStep == 2) {
                    finishOnboarding();
                }
            }
        });
    }

    private static class SetupResult {
        String method;
        boolean hasRoot;

        SetupResult(String method, boolean hasRoot) {
            this.method = method;
            this.hasRoot = hasRoot;
        }
    }

    private class SetupCheckTask extends AsyncTask<Void, Void, SetupResult> {
        @Override
        protected SetupResult doInBackground(Void... params) {
            int sdk = SettingsActivity.SDK;
            String selectedMethod;
            boolean hasRoot = false;
            if (sdk == 3) {
                selectedMethod = SettingsActivity.METHOD_HTTP;
            } else {
                hasRoot = ShellUtils.checkRoot();
                if (sdk >= 14) {
                    if (hasRoot) {
                        if (hasIptables()) {
                            selectedMethod = SettingsActivity.METHOD_ROOT;
                        } else if (isTunKoPresent()) {
                            selectedMethod = SettingsActivity.METHOD_VPNSERVICE;
                        } else {
                            selectedMethod = SettingsActivity.METHOD_PPTP;
                        }
                    } else {
                        selectedMethod = SettingsActivity.METHOD_VPNSERVICE;
                    }
                } else {
                    if (hasRoot) {
                        if (hasIptables()) {
                            selectedMethod = SettingsActivity.METHOD_ROOT;
                        } else {
                            selectedMethod = SettingsActivity.METHOD_PPTP;
                        }
                    } else {
                        selectedMethod = SettingsActivity.METHOD_HTTP;
                    }
                }
            }
            return new SetupResult(selectedMethod, hasRoot);
        }

        @Override
        protected void onPostExecute(SetupResult result) {
            progressBar.setVisibility(View.GONE);
            btnNext.setEnabled(true);

            detectedMethod = result.method;
            hasRootDetected = result.hasRoot;

            String displayMethod = getFriendlyMethodName(detectedMethod);
            Toast.makeText(WelcomeActivity.this, getString(R.string.selected, displayMethod), Toast.LENGTH_LONG).show();

            currentStep = 2;
            showCaStep();
        }
    }

    private void showCaStep() {
        tvTitle.setText(R.string.install_root_ca);
        if (hasRootDetected) {
            tvBody.setText(R.string.install_root_ca_);
            btnNext.setText(R.string.install);
            btnSkip.setVisibility(View.VISIBLE);
        } else {
            tvBody.setText(R.string.no_root_ca_note);
            btnNext.setText(R.string.next);
            btnSkip.setVisibility(View.GONE);
        }
    }

    private void installRootCa() {
        final android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(this);
        progressDialog.setMessage(getString(R.string.installing_ca));
        progressDialog.setCancelable(false);
        progressDialog.show();
        btnNext.setEnabled(false);
        btnSkip.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean success = MitmKeyStoreManager.installRootCa(WelcomeActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        if (success) {
                            Toast.makeText(WelcomeActivity.this, R.string.reboot, Toast.LENGTH_LONG).show();
                            saveOnboardingState();
                            ShellUtils.rebootDevice();
                        } else {
                            Toast.makeText(WelcomeActivity.this, R.string.install_root_ca_fail, Toast.LENGTH_LONG).show();
                            finishOnboarding();
                        }
                    }
                });
            }
        }).start();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void saveOnboardingState() {
        SharedPreferences prefs = getPrefs();
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(SettingsActivity.KEY_METHOD, detectedMethod);
        if (!prefs.contains(SettingsActivity.KEY_CERT_SIG)) {
            String defaultSig = (SettingsActivity.SDK >= 16) ? SettingsActivity.SIG_SHA256 : SettingsActivity.SIG_SHA1;
            editor.putString(SettingsActivity.KEY_CERT_SIG, defaultSig);
        }
        editor.putBoolean("is_first_run", false);
        editor.commit();
        SettingsBackup.backup(WelcomeActivity.this);
    }

    private void finishOnboarding() {
        saveOnboardingState();
        Intent intent = new Intent(WelcomeActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    private boolean hasIptables() {
        File binIptables = new File("/system/bin/iptables");
        File xbinIptables = new File("/system/xbin/iptables");
        return binIptables.exists() || xbinIptables.exists();
    }

    private boolean isTunKoPresent() {
        File tunFile = new File("/system/lib/modules/tun.ko");
        return tunFile.exists();
    }

    private String getFriendlyMethodName(String method) {
        if (SettingsActivity.METHOD_ROOT.equals(method)) {
            return getString(R.string.iptables);
        } else if (SettingsActivity.METHOD_HTTP.equals(method)) {
            return getString(R.string.http);
        } else if (SettingsActivity.METHOD_PPTP.equals(method)) {
            return getString(R.string.pptp_vpn);
        } else if (SettingsActivity.METHOD_VPNSERVICE.equals(method)) {
            return "VpnService";
        }
        return method;
    }
}