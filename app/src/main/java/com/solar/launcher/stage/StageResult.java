package com.solar.launcher.stage;

/** 2026-07-16 — outcome of one Solar device-staging step (graceful, never hangs forever). */
public enum StageResult {
    /** Step applied successfully. */
    OK,
    /** Step not needed or cannot run (no root, asset missing, already present). */
    SKIPPED,
    /** Root available but command failed — continue other steps. */
    FAILED
}
