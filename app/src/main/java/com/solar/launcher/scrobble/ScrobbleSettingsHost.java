package com.solar.launcher.scrobble;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.solar.launcher.R;
import com.solar.launcher.feature.settings.RowKeys;
import com.solar.launcher.feature.settings.SettingsScreens;

/**
 * Settings → Scrobbling (Last.fm & ListenBrainz).
 * Credentials can also be set via the Wi‑Fi transfer web UI (/scrobbling).
 */
public final class ScrobbleSettingsHost {

    public interface Actions {
        android.app.Activity activity();
        SharedPreferences prefs();
        void clickFeedback();
        LinearLayout createSettingsRow(String rowKey, int labelRes, boolean submenu);
        LinearLayout createSettingsRow(String rowKey, CharSequence title, boolean submenu);
        Button createListButton(String label);
        void styleSecondaryLabel(Button btn);
        void addSettingsRow(View row);
        void clearSettingsRows();
        void setSettingsSubScreen(String key);
        void updateStatusBarTitle();
        void applyReachBrowseLayoutMode();
        void refreshSettingsPreview(String rowKey);
        void drillSettingsBack(Runnable back);
        void openKeyboard(int purpose, String prefill);
        String previewForRow(String rowKey);
    }

    public static final int KEYBOARD_LASTFM_USER = 40;
    public static final int KEYBOARD_LASTFM_PASS = 41;
    public static final int KEYBOARD_LISTENBRAINZ_TOKEN = 42;

    private final Actions actions;

    public ScrobbleSettingsHost(Actions actions) {
        this.actions = actions;
    }

    public void build() {
        actions.setSettingsSubScreen(SettingsScreens.SCROBBLING);
        actions.updateStatusBarTitle();
        actions.applyReachBrowseLayoutMode();
        actions.clearSettingsRows();

        Button back = actions.createListButton(actions.activity().getString(R.string.common_cancel_back));
        actions.styleSecondaryLabel(back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                actions.drillSettingsBack(null);
            }
        });
        actions.addSettingsRow(back);

        final SharedPreferences p = actions.prefs();
        final LinearLayout btnLastfmEnable = actions.createSettingsRow(
                RowKeys.LASTFM_ENABLE, R.string.scrobble_lastfm_enable, false);
        btnLastfmEnable.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                boolean enabled = !p.getBoolean(ScrobbleManager.PREF_LASTFM_ENABLED, false);
                p.edit().putBoolean(ScrobbleManager.PREF_LASTFM_ENABLED, enabled).apply();
                actions.refreshSettingsPreview(RowKeys.LASTFM_ENABLE);
            }
        });
        actions.addSettingsRow(btnLastfmEnable);

        addEditableRow(RowKeys.LASTFM_USER, R.string.scrobble_lastfm_user, KEYBOARD_LASTFM_USER);
        addEditableRow(RowKeys.LASTFM_PASS, R.string.scrobble_lastfm_pass, KEYBOARD_LASTFM_PASS);

        LinearLayout btnLastfmAuth = actions.createSettingsRow(
                RowKeys.LASTFM_AUTH, R.string.scrobble_lastfm_auth, false);
        btnLastfmAuth.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                authenticateLastFm();
            }
        });
        actions.addSettingsRow(btnLastfmAuth);

        final LinearLayout btnListenbrainzEnable = actions.createSettingsRow(
                RowKeys.LISTENBRAINZ_ENABLE, R.string.scrobble_listenbrainz_enable, false);
        btnListenbrainzEnable.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                boolean enabled = !p.getBoolean(ScrobbleManager.PREF_LISTENBRAINZ_ENABLED, false);
                p.edit().putBoolean(ScrobbleManager.PREF_LISTENBRAINZ_ENABLED, enabled).apply();
                actions.refreshSettingsPreview(RowKeys.LISTENBRAINZ_ENABLE);
            }
        });
        actions.addSettingsRow(btnListenbrainzEnable);

        addEditableRow(RowKeys.LISTENBRAINZ_TOKEN, R.string.scrobble_listenbrainz_token,
                KEYBOARD_LISTENBRAINZ_TOKEN);
    }

    private void addEditableRow(final String rowKey, int labelRes, final int keyboardPurpose) {
        LinearLayout row = actions.createSettingsRow(rowKey, labelRes, false);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                actions.openKeyboard(keyboardPurpose, prefillFor(rowKey));
            }
        });
        actions.addSettingsRow(row);
    }

    private String prefillFor(String rowKey) {
        SharedPreferences p = actions.prefs();
        if (RowKeys.LASTFM_USER.equals(rowKey)) {
            return p.getString(ScrobbleManager.PREF_LASTFM_USERNAME, "");
        }
        if (RowKeys.LASTFM_PASS.equals(rowKey)) {
            return p.getString(ScrobbleManager.PREF_LASTFM_PASSWORD, "");
        }
        if (RowKeys.LISTENBRAINZ_TOKEN.equals(rowKey)) {
            return p.getString(ScrobbleManager.PREF_LISTENBRAINZ_TOKEN, "");
        }
        return "";
    }

    public void finishKeyboard(int purpose, String text) {
        SharedPreferences p = actions.prefs();
        if (purpose == KEYBOARD_LASTFM_USER) {
            p.edit().putString(ScrobbleManager.PREF_LASTFM_USERNAME, text.trim()).apply();
            actions.refreshSettingsPreview(RowKeys.LASTFM_USER);
        } else if (purpose == KEYBOARD_LASTFM_PASS) {
            p.edit().putString(ScrobbleManager.PREF_LASTFM_PASSWORD, text.trim()).apply();
            actions.refreshSettingsPreview(RowKeys.LASTFM_PASS);
        } else if (purpose == KEYBOARD_LISTENBRAINZ_TOKEN) {
            boolean hasToken = !text.trim().isEmpty();
            p.edit()
                    .putString(ScrobbleManager.PREF_LISTENBRAINZ_TOKEN, text.trim())
                    .putBoolean(ScrobbleManager.PREF_LISTENBRAINZ_ENABLED, hasToken)
                    .apply();
            actions.refreshSettingsPreview(RowKeys.LISTENBRAINZ_TOKEN);
            actions.refreshSettingsPreview(RowKeys.LISTENBRAINZ_ENABLE);
            Toast.makeText(actions.activity(), "ListenBrainz token saved", Toast.LENGTH_SHORT).show();
        }
    }

    private void authenticateLastFm() {
        SharedPreferences p = actions.prefs();
        String user = p.getString(ScrobbleManager.PREF_LASTFM_USERNAME, "");
        String pass = p.getString(ScrobbleManager.PREF_LASTFM_PASSWORD, "");
        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(actions.activity(),
                    "Please enter Last.fm Username and Password first", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(actions.activity(), "Connecting to Last.fm…", Toast.LENGTH_SHORT).show();
        ScrobbleManager.authenticateLastFm(actions.activity(), user, pass,
                new ScrobbleManager.AuthCallback() {
                    @Override
                    public void onResult(boolean success, String message) {
                        Toast.makeText(actions.activity(), message, Toast.LENGTH_LONG).show();
                        if (success) {
                            actions.refreshSettingsPreview(RowKeys.LASTFM_ENABLE);
                            actions.refreshSettingsPreview(RowKeys.LASTFM_USER);
                            actions.refreshSettingsPreview(RowKeys.LASTFM_AUTH);
                        }
                    }
                });
    }

    public static String previewValue(SharedPreferences prefs, String rowKey) {
        if (prefs == null) return "";
        if (RowKeys.LASTFM_ENABLE.equals(rowKey)) {
            return prefs.getBoolean(ScrobbleManager.PREF_LASTFM_ENABLED, false) ? "On" : "Off";
        }
        if (RowKeys.LASTFM_USER.equals(rowKey)) {
            String u = prefs.getString(ScrobbleManager.PREF_LASTFM_USERNAME, "");
            return u != null && !u.isEmpty() ? u : "—";
        }
        if (RowKeys.LASTFM_PASS.equals(rowKey)) {
            String p = prefs.getString(ScrobbleManager.PREF_LASTFM_PASSWORD, "");
            return p != null && !p.isEmpty() ? "••••" : "—";
        }
        if (RowKeys.LASTFM_AUTH.equals(rowKey)) {
            String sk = prefs.getString(ScrobbleManager.PREF_LASTFM_SK, "");
            return sk != null && !sk.isEmpty() ? "Signed In" : "Not Signed In";
        }
        if (RowKeys.LISTENBRAINZ_ENABLE.equals(rowKey)) {
            return prefs.getBoolean(ScrobbleManager.PREF_LISTENBRAINZ_ENABLED, false) ? "On" : "Off";
        }
        if (RowKeys.LISTENBRAINZ_TOKEN.equals(rowKey)) {
            String t = prefs.getString(ScrobbleManager.PREF_LISTENBRAINZ_TOKEN, "");
            return t != null && !t.isEmpty()
                    ? "••••" + (t.length() > 4 ? t.substring(t.length() - 4) : "")
                    : "—";
        }
        return "";
    }
}
