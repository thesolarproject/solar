package io.github.gohoski.wolfius;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

/**
 * Created by Gleb on 28.06.2026.
 */

public class SettingsActivity extends Activity {
    public static final int SDK = Integer.parseInt(android.os.Build.VERSION.SDK);
    public static final String PREFS_NAME = "Wolfius";
    public static final String KEY_METHOD = "connection_method";
    public static final String METHOD_ROOT = "root";
    public static final String METHOD_HTTP = "http";
    public static final String METHOD_VPNSERVICE = "vpnservice";
    public static final String METHOD_PPTP = "pptp";

    public static final String KEY_CERT_SIG = "cert_sig_algo";
    public static final String SIG_SHA1 = "sha1";
    public static final String SIG_SHA256 = "sha256";

    private RadioGroup rgMethods;
    private RadioButton radioRoot;
    private RadioButton radioHttp;
    private RadioButton radioVpnservice;
    private RadioButton radioPptp;

    private RadioGroup rgCertSig;
    private RadioButton radioSigSha1;
    private RadioButton radioSigSha256;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        rgMethods = (RadioGroup) findViewById(R.id.rg_methods);
        radioRoot = (RadioButton) findViewById(R.id.radio_method_root);
        radioHttp = (RadioButton) findViewById(R.id.radio_method_http);
        radioVpnservice = (RadioButton) findViewById(R.id.radio_method_vpnservice);
        radioPptp = (RadioButton) findViewById(R.id.radio_method_pptp);

        rgCertSig = (RadioGroup) findViewById(R.id.rg_cert_sig);
        radioSigSha1 = (RadioButton) findViewById(R.id.radio_sig_sha1);
        radioSigSha256 = (RadioButton) findViewById(R.id.radio_sig_sha256);

        Button btnSave = (Button) findViewById(R.id.btn_save);
        Button btnCancel = (Button) findViewById(R.id.btn_cancel);
        if (SDK < 14) radioVpnservice.setEnabled(false);

        final Button btnInstallCa = (Button) findViewById(R.id.btn_install_ca);
        SharedPreferences prefs = getPrefs();
        if (prefs.getBoolean("root_ca_installed", false)) {
            btnInstallCa.setText(R.string.root_ca_installed);
            btnInstallCa.setEnabled(false);
        } else {
            btnInstallCa.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!ShellUtils.checkRoot()) {
                        Toast.makeText(SettingsActivity.this, R.string.no_root_toast, Toast.LENGTH_LONG).show();
                        return;
                    }
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle(R.string.install_root_ca)
                            .setMessage(R.string.install_root_ca_)
                            .setPositiveButton(R.string.install, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    installRootCa();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            });
        }

        loadSettings();
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
                finish();
            }
        });
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = getPrefs();
        String method = prefs.getString(KEY_METHOD, METHOD_ROOT);

        if (METHOD_ROOT.equals(method)) {
            radioRoot.setChecked(true);
        } else if (METHOD_HTTP.equals(method)) {
            radioHttp.setChecked(true);
        } else if (METHOD_VPNSERVICE.equals(method) && SDK >= 14) {
            radioVpnservice.setChecked(true);
        } else if (METHOD_PPTP.equals(method)) {
            radioPptp.setChecked(true);
        } else {
            radioRoot.setChecked(true);
        }

        String certSig = prefs.getString(KEY_CERT_SIG, (SDK >= 16) ? SIG_SHA256 : SIG_SHA1);
        if (radioSigSha1 != null && radioSigSha256 != null) {
            if (SIG_SHA256.equals(certSig)) {
                radioSigSha256.setChecked(true);
            } else {
                radioSigSha1.setChecked(true);
            }
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void saveSettings() {
        SharedPreferences prefs = getPrefs();
        SharedPreferences.Editor editor = prefs.edit();

        int checkedId = rgMethods.getCheckedRadioButtonId();
        if (checkedId == R.id.radio_method_root) {
            editor.putString(KEY_METHOD, METHOD_ROOT);
        } else if (checkedId == R.id.radio_method_http) {
            editor.putString(KEY_METHOD, METHOD_HTTP);
        } else if (checkedId == R.id.radio_method_vpnservice) {
            editor.putString(KEY_METHOD, METHOD_VPNSERVICE);
        } else if (checkedId == R.id.radio_method_pptp) {
            editor.putString(KEY_METHOD, METHOD_PPTP);
        }

        if (rgCertSig != null) {
            int checkedSigId = rgCertSig.getCheckedRadioButtonId();
            if (checkedSigId == R.id.radio_sig_sha256) {
                editor.putString(KEY_CERT_SIG, SIG_SHA256);
            } else if (checkedSigId == R.id.radio_sig_sha1) {
                editor.putString(KEY_CERT_SIG, SIG_SHA1);
            }
        } else {
            if (!prefs.contains(KEY_CERT_SIG)) {
                editor.putString(KEY_CERT_SIG, (SDK >= 16) ? SIG_SHA256 : SIG_SHA1);
            }
        }

        editor.commit();

        if (MitmKeyStoreManager.isInitialized()) {
            MitmKeyStoreManager.getInstance().updateSigType(this);
        }

        SettingsBackup.backup(this);
    }

    private void installRootCa() {
        final android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(this);
        progressDialog.setMessage(getString(R.string.installing_ca));
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean success = MitmKeyStoreManager.installRootCa(SettingsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        if (success) {
                            Toast.makeText(SettingsActivity.this, R.string.reboot, Toast.LENGTH_LONG).show();
                            ShellUtils.rebootDevice();
                        } else {
                            Toast.makeText(SettingsActivity.this, R.string.install_root_ca_fail, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }
}