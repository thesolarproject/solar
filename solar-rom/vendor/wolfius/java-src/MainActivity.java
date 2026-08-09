package io.github.gohoski.wolfius;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

/**
 * Created by Gleb on 22.06.2026.
 */
public class MainActivity extends Activity {
    private ToggleButton toggleButton;
    private ImageButton btnSettings;
    private ImageButton btnAbout;
    private static final int REQUEST_VPN = 1002;
    private static final String PREF_PENDING_DIALOG = "pending_dialog";

    private SharedPreferences getPrefs() {
        return getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SettingsActivity.SDK >= 21)
            Toast.makeText(MainActivity.this, R.string.lollipop, Toast.LENGTH_LONG).show();

        SettingsBackup.restore(this);

        SharedPreferences prefs = getPrefs();
        if (prefs.getBoolean("is_first_run", true)) {
            Intent intent = new Intent(this, WelcomeActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        WolfSSLSocketFactory customFactory = new WolfSSLSocketFactory();
        HttpsURLConnection.setDefaultSSLSocketFactory(customFactory);
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            @Override public boolean verify(String hostname, SSLSession session) { return true; }
        });
        toggleButton = (ToggleButton) findViewById(R.id.toggle);
        btnSettings = (ImageButton) findViewById(R.id.settings);
        btnAbout = (ImageButton) findViewById(R.id.about);
        checkCaStatus();

        toggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                String method = getPrefs().getString(SettingsActivity.KEY_METHOD, SettingsActivity.METHOD_ROOT);
                boolean isVpnMethod = SettingsActivity.METHOD_VPNSERVICE.equals(method);

                // We separate ProxyService into VpnService for VpnService users, because android:process=":vpn" makes memory overhead
                if (isChecked) {
                    if (isVpnMethod) {
                        if (!isVpnRunning(MainActivity.this)) {
                            Intent prepareIntent = VpnCompatHelper.prepareVpn(MainActivity.this);
                            if (prepareIntent != null) {
                                startActivityForResult(prepareIntent, REQUEST_VPN);
                                return;
                            }
                            VpnCompatHelper.startVpnService(MainActivity.this);
                        }
                    } else {
                        if (!isProxyRunning(MainActivity.this)) {
                            if (SettingsActivity.METHOD_PPTP.equals(method)) showPptpDialog();
                            else if (SettingsActivity.METHOD_HTTP.equals(method)) showHttpDialog();
                            startService(new Intent(MainActivity.this, ProxyService.class));
                        }
                    }
                } else {
                    if (isVpnMethod) {
                        if (isVpnRunning(MainActivity.this)) {
                            VpnCompatHelper.stopVpnService(MainActivity.this);
                        }
                    } else {
                        if (isProxyRunning(MainActivity.this)) {
                            stopService(new Intent(MainActivity.this, ProxyService.class));
                        }
                    }
                }
            }
        });

        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isProxyRunning(MainActivity.this) || isVpnRunning(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, R.string.disable_proxy, Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
            }
        });

        btnAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                about();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        toggleButton.setChecked(isProxyRunning(this) || isVpnRunning(this));
        String pending = getPrefs().getString(PREF_PENDING_DIALOG, null);
        if (pending != null) {
            if ("pptp".equals(pending)) showPptpDialog();
            else if ("http".equals(pending)) showHttpDialog();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                toggleButton.setChecked(true);
                VpnCompatHelper.startVpnService(MainActivity.this);
            } else {
                Toast.makeText(this, "VPN permission denied.", Toast.LENGTH_SHORT).show();
                toggleButton.setChecked(false);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private boolean isProxyRunning(Context context) {
        android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            java.util.List<android.app.ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
            if (services != null) {
                for (android.app.ActivityManager.RunningServiceInfo service : services) {
                    if ("io.github.gohoski.wolfius.ProxyService".equals(service.service.getClassName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isVpnRunning(Context context) {
        android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            java.util.List<android.app.ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
            if (services != null) {
                for (android.app.ActivityManager.RunningServiceInfo service : services) {
                    if ("io.github.gohoski.wolfius.WolfiusVpnService".equals(service.service.getClassName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    private void showPptpDialog() {
        new AlertDialog.Builder(this).setTitle(R.string.setup_inst)
                .setMessage(R.string.pptp_inst)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        getPrefs().edit().remove(PREF_PENDING_DIALOG).commit();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(R.string.vpn_settings, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        getPrefs().edit().putString(PREF_PENDING_DIALOG, "pptp").commit();
                        Intent intent = new Intent();
                        if (SettingsActivity.SDK >= 14) {
                            intent.setAction("android.settings.VPN_SETTINGS");
                        } else {
                            intent.setAction("android.net.vpn.SETTINGS");
                        }
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivity(intent);
                        } catch (Exception e) {
                            try {
                                intent.setAction("android.settings.WIRELESS_SETTINGS");
                                startActivity(intent);
                            } catch (Exception ex) {
                                Toast.makeText(MainActivity.this, "Could not open VPN settings.",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }).create().show();
    }

    private void showHttpDialog() {
        new AlertDialog.Builder(this).setTitle(R.string.setup_inst)
                .setMessage(R.string.http_inst)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        getPrefs().edit().remove(PREF_PENDING_DIALOG).commit();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(R.string.wireless_settings, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        getPrefs().edit().putString(PREF_PENDING_DIALOG, "http").commit();
                        Intent intent = new Intent();
                        intent.setAction("android.settings.WIRELESS_SETTINGS");
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivity(intent);
                        } catch (Exception e) {
                            try {
                                intent.setAction("android.settings.WIFI_SETTINGS");
                                startActivity(intent);
                            } catch (Exception ex) {
                                Toast.makeText(MainActivity.this, "Could not open wireless settings.", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }).create().show();
    }

    private void about() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(15, 15, 15, 15);

        final TextView app = new TextView(this);
        app.setText(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME);
        app.setTypeface(null, Typeface.BOLD);
        app.setTextSize(20);
        app.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(app);

        final ImageView imageView = new ImageView(this);
        imageView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        imageView.setImageResource(R.drawable.main_icon);
        layout.addView(imageView);

        final TextView text = new TextView(this);
        text.setText(getString(R.string.about) + "\n\nCopyright (c) 2001, 2002 Swedish Institute of Computer Science.\n" +
                "All rights reserved.\n" +
                "\n" +
                "Redistribution and use in source and binary forms, with or without modification,\n" +
                "are permitted provided that the following conditions are met:\n" +
                "\n" +
                "1. Redistributions of source code must retain the above copyright notice,\n" +
                "   this list of conditions and the following disclaimer.\n" +
                "2. Redistributions in binary form must reproduce the above copyright notice,\n" +
                "   this list of conditions and the following disclaimer in the documentation\n" +
                "   and/or other materials provided with the distribution.\n" +
                "3. The name of the author may not be used to endorse or promote products\n" +
                "   derived from this software without specific prior written permission.\n" +
                "\n" +
                "THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED\n" +
                "WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF\n" +
                "MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT\n" +
                "SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,\n" +
                "EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT\n" +
                "OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS\n" +
                "INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN\n" +
                "CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING\n" +
                "IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY\n" +
                "OF SUCH DAMAGE.");
        layout.addView(text);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);

        new AlertDialog.Builder(this).setTitle(R.string.app_name)
                .setView(scrollView)
                .setNeutralButton(android.R.string.ok, null)
                .setPositiveButton("GNU GPLv3", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        showFullLicenseDialog();
                    }
                }).show();
    }

    private void showFullLicenseDialog() {
        String licenseText = "";
        java.io.InputStream is = null;
        try {
            is = getAssets().open("gpl-3.0.txt");
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            licenseText = sb.toString();
        } catch (Exception e) {
            licenseText = "assets/gpl-3.0.txt missing";
            e.printStackTrace();
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }

        TextView licenseTv = new TextView(this);
        licenseTv.setText(licenseText);
        licenseTv.setTextSize(13);
        licenseTv.setPadding(10, 10, 10, 10);

        ScrollView sv = new ScrollView(this);
        sv.addView(licenseTv);

        new AlertDialog.Builder(this)
                .setTitle("GNU GPLv3 License")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void checkCaStatus() {
        SharedPreferences prefs = getPrefs();
        boolean pendingReboot = prefs.getBoolean("installation_pending_reboot", false);
        if (pendingReboot) {
            boolean verificationSuccess = false;
            try {
                MitmKeyStoreManager.init(getApplicationContext());
                MitmKeyStoreManager manager = MitmKeyStoreManager.getInstance();
                if (SettingsActivity.SDK >= 14) {
                    X509Certificate caCert = manager.getCaCertificate();
                    String hash = MitmKeyStoreManager.getSubjectHashOld(caCert);
                    java.io.File targetFile = new java.io.File("/system/etc/security/cacerts/" + hash + ".0");
                    if (targetFile.exists()) {
                        String systemFileHash = MitmKeyStoreManager.getFileSha256(targetFile);
                        java.io.InputStream is = getAssets().open("ca_cert.pem");
                        byte[] pemBuffer = new byte[is.available()];
                        is.read(pemBuffer);
                        is.close();
                        String expectedHash = MitmKeyStoreManager.getBytesSha256(pemBuffer);
                        verificationSuccess = systemFileHash.equals(expectedHash);
                    }
                } else {
                    java.io.File targetFile = new java.io.File("/system/etc/security/cacerts.bks");
                    if (targetFile.exists()) {
                        String systemFileHash = MitmKeyStoreManager.getFileSha256(targetFile);
                        String savedOriginalHash = prefs.getString("original_hash", "");
                        verificationSuccess = !systemFileHash.equals(savedOriginalHash);
                    }
                }
            } catch (Exception e) {
                Log.e("MainActivity", "Error verifying root CA installation", e);
                verificationSuccess = false;
            }
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("installation_pending_reboot", false);
            if (verificationSuccess) {
                editor.putBoolean("root_ca_installed", true);
                editor.commit();
                Toast.makeText(this, R.string.install_root_ca_success, Toast.LENGTH_LONG).show();
            } else {
                editor.putBoolean("root_ca_installed", false);
                editor.commit();
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.write_protection)
                        .setMessage(R.string.write_protection_)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                            }
                        })
                        .show();
            }
            SettingsBackup.backup(this);
        }
    }
}