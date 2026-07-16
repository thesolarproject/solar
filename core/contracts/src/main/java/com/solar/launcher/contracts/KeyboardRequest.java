package com.solar.launcher.contracts;

/** In-process full-screen keyboard session request. */
public final class KeyboardRequest {
    public final String purposeId;
    public final String initialText;
    public final int maxLength;
    public final boolean password;
    public final Listener listener;

    public interface Listener {
        void onCommit(String text);
        void onCancel();
    }

    public KeyboardRequest(String purposeId, String initialText, int maxLength,
                           boolean password, Listener listener) {
        this.purposeId = purposeId;
        this.initialText = initialText != null ? initialText : "";
        this.maxLength = maxLength;
        this.password = password;
        this.listener = listener;
    }
}
