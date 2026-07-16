package com.solar.launcher.stage;

/** 2026-07-16 — one named staging step for first-run UI. */
public final class StageStep {
    public final String id;
    public final int titleResId;
    public final StageResult result;
    public final String detail;

    public StageStep(String id, int titleResId, StageResult result, String detail) {
        this.id = id != null ? id : "";
        this.titleResId = titleResId;
        this.result = result != null ? result : StageResult.SKIPPED;
        this.detail = detail != null ? detail : "";
    }
}
