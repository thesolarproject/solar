package com.solar.launcher.stem;

/** Pure routing policy for the Get Stems preparation-only picker flow. */
public final class StemPreparationPolicy {
    private StemPreparationPolicy() {}

    /** Get Stems must never enter the performance screen after preparation. */
    public static boolean shouldStartPerformance(boolean preparationOnly) {
        return !preparationOnly;
    }

}
