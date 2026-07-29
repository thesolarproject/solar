package com.solar.launcher;

import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * 2026-07-05 — Tier-1 IME: wheel keyboard overlay + InputConnection text commits only.
 * Mutex: pauses when global overlay active; arbiter publishes route=ime while tray is visible.
 * Fail-open: if Solar IME unavailable, stock LatinIME and normal key delivery continue.
 * 2026-07-14 — A5: real InputMethod input view (no Xposed); Vol space/del; Back Enter / hold charset.
 * When changing: SolarImeRouteArbiter arm/disarm; never commit keys while overlay owns wheel.
 * Reversal: unregister IME in manifest; stock LatinIME becomes default again.
 */
public class SolarInputMethodService extends InputMethodService implements SolarImeKeyGate.Handler,
        SolarWheelKeyboardController.Listener {

    private final SolarWheelKeyboardController wheelKeyboard = new SolarWheelKeyboardController();
    private SolarImeFullscreenOverlay overlay;
    private String sessionTitle = "";
    private long ppDownAt;
    private boolean ppLongHandled;
    /** 2026-07-14 — A5 side-Back hold while IME tray open. */
    private long a5BackDownAt;
    private boolean a5BackLongHandled;
    /** 2026-07-14 — Last KeyEvent scan for face-mid vs side-Back (int onKey* path). */
    private int lastScanCode;
    /** 2026-07-14 — A5 framework soft-input shell (keeps InputConnection alive). */
    private View a5InputRoot;
    private SolarKeyboardShellHost a5ShellHost;
    private A5EdgeGestures a5EdgeGestures;

    @Override
    public void onCreate() {
        super.onCreate();
        wheelKeyboard.setListener(this);
        setExtractViewShown(false);
    }

    /** A5 uses a real soft-input view; Y1/Y2 keep WM overlay + Xposed key gate. */
    private boolean useA5InputView() {
        return DeviceFeatures.isA5();
    }

    @Override
    public View onCreateInputView() {
        // 2026-07-14 — A5: inflate themed shell so touch + keys work without Xposed IPC.
        // Was: GONE placeholder + TYPE_SYSTEM_ERROR overlay (Y1/Y2 Xposed path).
        // Reversal: always return GONE view; A5 also uses WM overlay.
        if (!useA5InputView()) {
            View v = new View(this);
            v.setVisibility(View.GONE);
            return v;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        a5InputRoot = inflater.inflate(R.layout.layout_solar_keyboard_shell, null);
        String enterLabel = getString(R.string.keyboard_enter);
        a5ShellHost = new SolarKeyboardShellHost(this, a5InputRoot, enterLabel);
        a5ShellHost.getKeyboardUi().setHintText(getString(R.string.keyboard_hint_a5));
        // Touch: focus-then-confirm + drag; commit via applyCenterSelection so IC gets text.
        a5ShellHost.getKeyboardUi().attachTouchSlotsForIme(wheelKeyboard, new Runnable() {
            @Override
            public void run() {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("idx", wheelKeyboard.getIndex());
                    d.put("ch", wheelKeyboard.isPasswordMode()
                            ? "<redacted>" : wheelKeyboard.charAt(wheelKeyboard.getIndex()));
                    Debug670453Log.log(SolarInputMethodService.this,
                            "SolarInputMethodService.touchConfirm", "confirm runnable", "H5", d);
                } catch (Exception ignored) {}
                // #endregion
                applyCenterSelection();
            }
        });
        wireA5EdgeDismiss(a5InputRoot);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("isA5", DeviceFeatures.isA5());
            d.put("family", DeviceFeatures.deviceModelLabel());
            d.put("rootNull", a5InputRoot == null);
            Debug670453Log.log(this, "SolarInputMethodService.onCreateInputView",
                    "a5 shell inflated", "H6", d);
        } catch (Exception ignored) {}
        // #endregion
        return a5InputRoot;
    }

    /**
     * 2026-07-14 — Left/right edge swipe closes IME (hardware Back is Enter on A5).
     * Layman: flick from the screen edge to cancel typing without submitting.
     */
    private void wireA5EdgeDismiss(View root) {
        if (root == null || !DeviceFeatures.isA5()) return;
        a5EdgeGestures = new A5EdgeGestures(new A5EdgeGestures.Host() {
            @Override
            public void onA5EdgeBack() {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("via", "edgeBack");
                    d.put("vw", viewportWidth());
                    d.put("vh", viewportHeight());
                    d.put("rootW", a5InputRoot != null ? a5InputRoot.getWidth() : -1);
                    d.put("rootH", a5InputRoot != null ? a5InputRoot.getHeight() : -1);
                    Debug670453Log.log(SolarInputMethodService.this,
                            "SolarInputMethodService.wireA5EdgeDismiss", "edge dismiss", "H1", d);
                } catch (Exception ignored) {}
                // #endregion
                dismissImeSession();
            }

            @Override
            public void onA5EdgeHome() {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("via", "edgeHome");
                    Debug670453Log.log(SolarInputMethodService.this,
                            "SolarInputMethodService.wireA5EdgeDismiss", "edge dismiss", "H1", d);
                } catch (Exception ignored) {}
                // #endregion
                dismissImeSession();
            }

            @Override
            public void onA5EdgeOpenContext() {
                // Ignore hold-context on IME tray — typing owns the session.
            }

            @Override
            public void onA5EdgeContextHoldArm() {
                // IME: no Options spinner.
            }

            @Override
            public void onA5EdgeContextHoldCancel() {
                // IME: no Options spinner.
            }

            @Override
            public int viewportWidth() {
                return getResources().getDisplayMetrics().widthPixels;
            }

            @Override
            public int viewportHeight() {
                return getResources().getDisplayMetrics().heightPixels;
            }

            @Override
            public View a5GestureRoot() {
                return a5InputRoot;
            }

            @Override
            public boolean a5ContextMenuBlockingHold() {
                return true;
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                boolean consumed = a5EdgeGestures != null && a5EdgeGestures.process(event);
                // #region agent log
                if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    try {
                        org.json.JSONObject d = new org.json.JSONObject();
                        d.put("x", event.getX());
                        d.put("y", event.getY());
                        d.put("vw", getResources().getDisplayMetrics().widthPixels);
                        d.put("vh", getResources().getDisplayMetrics().heightPixels);
                        d.put("viewW", v != null ? v.getWidth() : -1);
                        d.put("viewH", v != null ? v.getHeight() : -1);
                        d.put("consumed", consumed);
                        d.put("edge", A5EdgeGestures.edgeAt(
                                event.getX(), event.getY(),
                                getResources().getDisplayMetrics().widthPixels,
                                getResources().getDisplayMetrics().heightPixels).name());
                        d.put("edgeViewLocal", A5EdgeGestures.edgeAt(
                                event.getX(), event.getY(),
                                v != null ? v.getWidth() : 0,
                                v != null ? v.getHeight() : 0).name());
                        Debug670453Log.log(SolarInputMethodService.this,
                                "SolarInputMethodService.a5RootTouch", "DOWN", "H1", d);
                    } catch (Exception ignored) {}
                }
                // #endregion
                return consumed;
            }
        });
    }

    @Override
    public View onCreateCandidatesView() {
        return null;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    /**
     * 2026-07-14 — A5 shows framework input view; Y1/Y2 hide it (WM shell only).
     * Was: always false. Reversal: return false always.
     */
    @Override
    public boolean onEvaluateInputViewShown() {
        return useA5InputView();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("restarting", restarting);
            d.put("canArm", SolarImeRouteArbiter.canArm());
            d.put("pkg", attribute != null ? attribute.packageName : "");
            d.put("a5View", useA5InputView());
            d.put("isA5", DeviceFeatures.isA5());
            d.put("trayGate", SolarImeDismiss.shouldShowSystemImeTray(attribute));
            if (attribute != null) {
                d.put("fieldId", attribute.fieldId);
                d.put("label", attribute.label != null ? attribute.label.toString() : "");
                d.put("hint", attribute.hintText != null ? attribute.hintText.toString() : "");
                d.put("inputType", DebugImeLog.inputTypeFields(attribute.inputType));
            }
            DebugImeLog.log(this, "SolarInputMethodService.onStartInput", "enter", "H2", d);
            Debug670453Log.log(this, "SolarInputMethodService.onStartInput", "enter", "H6", d);
        } catch (Exception ignored) {}
        // #endregion
        if (!SolarImeRouteArbiter.canArm()) {
            // #region agent log
            try {
                Debug670453Log.log(this, "SolarInputMethodService.onStartInput",
                        "early dismiss !canArm", "H6", null);
            } catch (Exception ignored) {}
            // #endregion
            dismissImeSession();
            return;
        }
        if (!SolarImeDismiss.shouldShowSystemImeTray(attribute)) {
            // #region agent log
            try {
                Debug670453Log.log(this, "SolarInputMethodService.onStartInput",
                        "early dismiss !trayGate", "H6", null);
            } catch (Exception ignored) {}
            // #endregion
            dismissImeSession();
            return;
        }
        super.onStartInput(attribute, restarting);
        sessionTitle = attribute != null && attribute.label != null
                ? attribute.label.toString() : getString(R.string.solar_ime_label);
        wheelKeyboard.reset();
        wheelKeyboard.setGroupedMode(WheelKeyboardLayout.isGrouped(this));
        wheelKeyboard.setDigitOnlyMode(attribute != null
                && isNumericPasswordInputType(attribute.inputType));
        wheelKeyboard.setPasswordMode(attribute != null
                && isPasswordInputType(attribute.inputType));
        seedBufferFromInputConnection();
        if (!SolarImeKeyGate.arm(this)) {
            // #region agent log
            try {
                Debug670453Log.log(this, "SolarInputMethodService.onStartInput",
                        "early dismiss !keyGate", "H6", null);
            } catch (Exception ignored) {}
            // #endregion
            dismissImeSession();
            return;
        }
        if (useA5InputView()) {
            // Framework paints onCreateInputView; refresh shell + mark tray visible.
            refreshA5InputUi();
            SolarImeRouteArbiter.setTrayUiVisible(true);
            try {
                showWindow(true);
            } catch (Exception ignored) {}
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("path", "a5InputView");
                d.put("icNull", getCurrentInputConnection() == null);
                Debug670453Log.log(this, "SolarInputMethodService.onStartInput",
                        "armed a5 path", "H6", d);
            } catch (Exception ignored) {}
            // #endregion
        } else {
            if (overlay == null) {
                overlay = new SolarImeFullscreenOverlay(this, wheelKeyboard);
            }
            overlay.show(sessionTitle);
            suppressFrameworkImeWindow();
            GlobalOverlayTrigger.ensureStarted(getApplicationContext());
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("path", "wmOverlay");
                Debug670453Log.log(this, "SolarInputMethodService.onStartInput",
                        "armed y1y2 path on non-a5", "H6", d);
            } catch (Exception ignored) {}
            // #endregion
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (useA5InputView()) {
            refreshA5InputUi();
        }
    }

    /** Paint A5 soft-input shell from controller + session title. */
    private void refreshA5InputUi() {
        if (a5ShellHost == null) return;
        // 2026-07-20 — Status clock; no session subtitle; type hint only in the input row.
        a5ShellHost.applyShellTheme(sessionTitle);
        String buffer = wheelKeyboard.getBuffer();
        String placeholder = getString(R.string.solar_ime_type_hint);
        String display = buffer.length() == 0
                ? placeholder : wheelKeyboard.renderBuffer(true);
        a5ShellHost.getKeyboardUi().setHintText(getString(R.string.keyboard_hint_a5));
        a5ShellHost.getKeyboardUi().refresh(wheelKeyboard, null, display, buffer.length() == 0);
    }

    @Override
    public void onFinishInput() {
        tearDown();
        super.onFinishInput();
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        // Y1/Y2 shrink framework window; A5 needs the real soft-input window visible.
        if (!useA5InputView()) {
            suppressFrameworkImeWindow();
        }
    }

    /**
     * 2026-07-14 — Framework key path for A5 soft-input window (no Xposed forwarder).
     * Layman: buttons on the player reach the keyboard even in other apps.
     * Tech: remap A5 mid/power then delegate to int onKeyDown.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (useA5InputView() && event != null) {
            lastScanCode = event.getScanCode();
            int mapped = A5KeyboardKeys.remapForIme(keyCode, lastScanCode);
            boolean handled = onKeyDown(mapped);
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("raw", keyCode);
                d.put("scan", lastScanCode);
                d.put("mapped", mapped);
                d.put("enterBack", A5KeyboardKeys.isEnterBackKey(mapped, lastScanCode));
                d.put("faceMid", A5InputKeys.isFaceMiddle(mapped, lastScanCode));
                d.put("space", A5KeyboardKeys.isSpaceKey(mapped));
                d.put("del", A5KeyboardKeys.isDeleteKey(mapped));
                d.put("handled", handled);
                Debug670453Log.log(this, "SolarInputMethodService.onKeyDown(KeyEvent)",
                        "key", "H2", d);
            } catch (Exception ignored) {}
            // #endregion
            return handled;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (useA5InputView() && event != null) {
            lastScanCode = event.getScanCode();
            int mapped = A5KeyboardKeys.remapForIme(keyCode, lastScanCode);
            boolean handled = onKeyUp(mapped);
            // #region agent log
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("raw", keyCode);
                d.put("scan", lastScanCode);
                d.put("mapped", mapped);
                d.put("handled", handled);
                Debug670453Log.log(this, "SolarInputMethodService.onKeyUp(KeyEvent)",
                        "key", "H3", d);
            } catch (Exception ignored) {}
            // #endregion
            return handled;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && OverlayTriggers.ACTION_IME_KEY.equals(intent.getAction())) {
            int keyCode = intent.getIntExtra(OverlayTriggers.EXTRA_KEY_CODE, 0);
            int action = intent.getIntExtra(OverlayTriggers.EXTRA_KEY_ACTION, KeyEvent.ACTION_DOWN);
            int scan = intent.getIntExtra(OverlayTriggers.EXTRA_SCAN_CODE, 0);
            if (keyCode != 0) {
                SolarImeKeyGate.rebindHandler(this);
                lastScanCode = scan;
                int mapped = A5KeyboardKeys.active()
                        ? A5KeyboardKeys.remapForIme(keyCode, scan) : keyCode;
                boolean handled;
                if (action == KeyEvent.ACTION_DOWN) {
                    handled = onKeyDown(mapped);
                } else if (action == KeyEvent.ACTION_UP) {
                    handled = onKeyUp(mapped);
                } else {
                    handled = false;
                }
                if (!handled && SolarImeRouteArbiter.isActive()) {
                    SolarImeRouteArbiter.signalXposedMiss();
                }
            }
            return START_NOT_STICKY;
        }
        if (intent != null && OverlayTriggers.ACTION_IME_COMMIT.equals(intent.getAction())) {
            String text = intent.getStringExtra(OverlayTriggers.EXTRA_IME_TEXT);
            boolean delete = intent.getBooleanExtra(OverlayTriggers.EXTRA_IME_DELETE, false);
            if (delete) {
                commitDelete(1);
            } else if (text != null) {
                commitTextTiered(text);
            }
            return START_NOT_STICKY;
        }
        if (intent != null && OverlayTriggers.ACTION_IME_DISMISS.equals(intent.getAction())) {
            dismissImeSession();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    /** BACK / dismiss — tear down tray first, then tell framework IME to hide. */
    private void dismissImeSession() {
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("a5", useA5InputView());
            Debug670453Log.log(this, "SolarInputMethodService.dismissImeSession",
                    "dismiss", "H1", d);
        } catch (Exception ignored) {}
        // #endregion
        tearDown();
        try {
            requestHideSelf(0);
        } catch (Exception ignored) {}
    }

    @Override
    public void onStateChanged() {
        if (useA5InputView()) {
            refreshA5InputUi();
        } else if (overlay != null) {
            overlay.refresh(sessionTitle);
        }
    }

    @Override
    public void onEnterRequested() {
        sendDefaultEditorAction(false);
        dismissImeSession();
    }

    @Override
    public boolean onKeyDown(int keyCode) {
        // 2026-07-14 — A5: Vol = space/delete; Back arms Enter/charset (not dismiss).
        if (A5KeyboardKeys.isSpaceKey(keyCode)) {
            insertText(" ");
            return true;
        }
        if (A5KeyboardKeys.isDeleteKey(keyCode)) {
            deleteOneChar();
            return true;
        }
        if (A5KeyboardKeys.active() && A5KeyboardKeys.isEnterBackKey(keyCode, lastScanCode)) {
            if (a5BackDownAt == 0) {
                a5BackDownAt = SystemClock.uptimeMillis();
                a5BackLongHandled = false;
            }
            return true;
        }
        if (Y1InputKeys.isBackKey(keyCode)) {
            dismissImeSession();
            return true;
        }
        if (Y1InputKeys.isWheelUp(keyCode)) {
            wheelKeyboard.wheelUp();
            return true;
        }
        if (Y1InputKeys.isWheelDown(keyCode)) {
            wheelKeyboard.wheelDown();
            return true;
        }
        if (Y1InputKeys.isCenterKey(keyCode) || Y1InputKeys.isPlayPauseKey(keyCode)) {
            if (ppDownAt == 0) {
                ppDownAt = SystemClock.uptimeMillis();
                ppLongHandled = false;
            }
            return true;
        }
        if (Y1InputKeys.isTrackPreviousKey(keyCode)) {
            deleteOneChar();
            return true;
        }
        if (Y1InputKeys.isTrackNextKey(keyCode)) {
            insertText(" ");
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode) {
        if (A5KeyboardKeys.isSpaceKey(keyCode) || A5KeyboardKeys.isDeleteKey(keyCode)) {
            return true;
        }
        if (A5KeyboardKeys.active() && A5KeyboardKeys.isEnterBackKey(keyCode, lastScanCode)) {
            long held = a5BackDownAt > 0 ? SystemClock.uptimeMillis() - a5BackDownAt : 0;
            a5BackDownAt = 0;
            if (!a5BackLongHandled && held >= A5KeyboardKeys.CHARSET_HOLD_MS) {
                wheelKeyboard.playPauseLongPress();
                a5BackLongHandled = true;
                return true;
            }
            if (!a5BackLongHandled) {
                onEnterRequested();
            }
            a5BackLongHandled = false;
            return true;
        }
        if (Y1InputKeys.isBackKey(keyCode)) {
            dismissImeSession();
            return true;
        }
        if (Y1InputKeys.isPlayPauseKey(keyCode) && !Y1InputKeys.isCenterKey(keyCode)) {
            long held = ppDownAt > 0 ? SystemClock.uptimeMillis() - ppDownAt : 0;
            ppDownAt = 0;
            if (!ppLongHandled && held >= 400L) {
                wheelKeyboard.playPauseLongPress();
                ppLongHandled = true;
                return true;
            }
            if (!ppLongHandled) {
                onEnterRequested();
            }
            ppLongHandled = false;
            return true;
        }
        if (Y1InputKeys.isCenterKey(keyCode)) {
            long held = ppDownAt > 0 ? SystemClock.uptimeMillis() - ppDownAt : 0;
            ppDownAt = 0;
            if (!ppLongHandled && held >= 400L) {
                wheelKeyboard.playPauseLongPress();
                ppLongHandled = true;
                return true;
            }
            if (!ppLongHandled) {
                applyCenterSelection();
            }
            ppLongHandled = false;
            return true;
        }
        return false;
    }

    /** Center short — mirror in-app keyboard; inject matching text into the target field only. */
    private void applyCenterSelection() {
        String selected = wheelKeyboard.charAt(wheelKeyboard.getIndex());
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("selected", wheelKeyboard.isPasswordMode()
                    ? "<redacted>" : selected);
            d.put("icNull", getCurrentInputConnection() == null);
            Debug670453Log.log(this, "SolarInputMethodService.applyCenterSelection",
                    "enter", "H4", d);
        } catch (Exception ignored) {}
        // #endregion
        if (SolarWheelKeyboardController.TOKEN_DEL.equals(selected)) {
            deleteOneChar();
        } else if (SolarWheelKeyboardController.TOKEN_WORD.equals(selected)) {
            deleteOneWord();
        } else if (SolarWheelKeyboardController.TOKEN_LEFT.equals(selected)) {
            moveEditorCursor(-1);
        } else if (SolarWheelKeyboardController.TOKEN_RIGHT.equals(selected)) {
            moveEditorCursor(1);
        } else if (SolarWheelKeyboardController.TOKEN_VISIBILITY.equals(selected)) {
            wheelKeyboard.togglePasswordVisibility();
        } else if (SolarWheelKeyboardController.TOKEN_CONN.equals(selected)) {
            onEnterRequested();
        } else if (SolarWheelKeyboardController.TOKEN_SPC.equals(selected)) {
            insertText(" ");
        } else {
            wheelKeyboard.centerPress();
            commitTextTiered(selected);
        }
    }

    private void insertText(String text) {
        wheelKeyboard.insertText(text);
        commitTextTiered(text);
    }

    private void deleteOneChar() {
        WheelTextEditor.State before = new WheelTextEditor.State(
                wheelKeyboard.getBuffer(), wheelKeyboard.getCursor());
        wheelKeyboard.mediaDelete();
        WheelTextEditor.State after = new WheelTextEditor.State(
                wheelKeyboard.getBuffer(), wheelKeyboard.getCursor());
        int count = WheelTextEditor.deletedBeforeCursorCount(before, after);
        if (count > 0) commitDelete(count);
    }

    private void deleteOneWord() {
        WheelTextEditor.State before = new WheelTextEditor.State(
                wheelKeyboard.getBuffer(), wheelKeyboard.getCursor());
        wheelKeyboard.deleteWord();
        WheelTextEditor.State after = new WheelTextEditor.State(
                wheelKeyboard.getBuffer(), wheelKeyboard.getCursor());
        int count = WheelTextEditor.deletedBeforeCursorCount(before, after);
        if (count > 0) commitDelete(count);
    }

    private void moveEditorCursor(int direction) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            int keyCode = direction < 0 ? KeyEvent.KEYCODE_DPAD_LEFT
                    : KeyEvent.KEYCODE_DPAD_RIGHT;
            try {
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            } catch (Exception ignored) {}
        }
        wheelKeyboard.moveCursor(direction);
    }

    private void suppressFrameworkImeWindow() {
        try {
            hideWindow();
        } catch (Exception ignored) {}
        try {
            Window w = getWindow() != null ? getWindow().getWindow() : null;
            if (w != null) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams lp = w.getAttributes();
                if (lp != null) {
                    lp.dimAmount = 0f;
                    lp.width = 0;
                    lp.height = 0;
                    w.setAttributes(lp);
                }
            }
        } catch (Exception ignored) {}
    }

    private void tearDown() {
        SolarImeKeyGate.disarm();
        SolarImeRouteArbiter.setTrayUiVisible(false);
        if (overlay != null) {
            overlay.dismiss();
        }
        a5BackDownAt = 0;
        a5BackLongHandled = false;
        try {
            hideWindow();
        } catch (Exception ignored) {}
    }

    /** Seed preview line from focused editor text when available. */
    private void seedBufferFromInputConnection() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            CharSequence before = ic.getTextBeforeCursor(512, 0);
            CharSequence after = ic.getTextAfterCursor(512, 0);
            String beforeText = before != null ? before.toString() : "";
            String afterText = after != null ? after.toString() : "";
            wheelKeyboard.setBuffer(beforeText + afterText);
            wheelKeyboard.setCursor(beforeText.length());
        } catch (Exception ignored) {}
    }

    static boolean isPasswordInputType(int inputType) {
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        }
        return inputClass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    static boolean isNumericPasswordInputType(int inputType) {
        return (inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
                && (inputType & InputType.TYPE_MASK_VARIATION)
                == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    static boolean commitTextTiered(Context ctx, InputConnection ic, CharSequence text) {
        if (text == null) return true;
        if (ic != null) {
            try {
                if (ic.commitText(text, 1)) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        SolarImeRouteArbiter.escalateToXposedSession();
        if (broadcastXposedCommit(ctx, text.toString(), false)) {
            return true;
        }
        SolarImeRouteArbiter.escalateToAccessibility();
        return SolarImeAccessibilityService.commitViaAccessibility(ctx, text.toString());
    }

    private boolean commitTextTiered(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        boolean ok = commitTextTiered(this, ic, text);
        // #region agent log
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            d.put("text", wheelKeyboard.isPasswordMode()
                    ? "<redacted>" : (text != null ? text.toString() : ""));
            d.put("icNull", ic == null);
            d.put("ok", ok);
            Debug670453Log.log(this, "SolarInputMethodService.commitTextTiered",
                    "commit", "H4", d);
        } catch (Exception ignored) {}
        // #endregion
        return ok;
    }

    private void commitDelete(int count) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            try {
                if (ic.deleteSurroundingText(count, 0)) return;
            } catch (Exception ignored) {}
        }
        SolarImeRouteArbiter.escalateToXposedSession();
        if (broadcastXposedCommit(this, "", true)) return;
        SolarImeRouteArbiter.escalateToAccessibility();
        SolarImeAccessibilityService.commitViaAccessibility(this, "");
    }

    private static boolean broadcastXposedCommit(Context ctx, String text, boolean delete) {
        try {
            Intent i = new Intent(OverlayTriggers.ACTION_IME_COMMIT);
            i.putExtra(OverlayTriggers.EXTRA_IME_TEXT, text);
            i.putExtra(OverlayTriggers.EXTRA_IME_DELETE, delete);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.sendBroadcast(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
