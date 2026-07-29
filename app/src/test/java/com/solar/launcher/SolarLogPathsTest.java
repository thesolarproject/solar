package com.solar.launcher;

import org.junit.Test;

/** 2026-07-16 — MicroSD capacity heuristics and path helpers. */
public class SolarLogPathsTest {

    @Test
    public void suspiciousCapacityUnder512Mb() {
        long almostHalfGb = 400L * 1024L * 1024L;
        if (!SolarLogPaths.isSuspiciousMicroSdCapacity(almostHalfGb)) {
            throw new AssertionError("0.4GB total should flag");
        }
        if (!SolarLogPaths.isSuspiciousMicroSdCapacity(
                SolarLogPaths.MICROSD_CAPACITY_WARN_BYTES - 1)) {
            throw new AssertionError("just under 512MB");
        }
    }

    @Test
    public void healthyCapacityAtOrAbove512Mb() {
        if (SolarLogPaths.isSuspiciousMicroSdCapacity(
                SolarLogPaths.MICROSD_CAPACITY_WARN_BYTES)) {
            throw new AssertionError("exactly 512MB is OK");
        }
        if (SolarLogPaths.isSuspiciousMicroSdCapacity(8L * 1024L * 1024L * 1024L)) {
            throw new AssertionError("8GB healthy");
        }
        // Free-space-only case: huge card almost full is NOT a capacity anomaly.
        if (SolarLogPaths.isSuspiciousMicroSdCapacity(0L)) {
            throw new AssertionError("unknown total should not flag");
        }
        if (SolarLogPaths.isSuspiciousMicroSdCapacity(-1L)) {
            throw new AssertionError("negative should not flag");
        }
    }

    @Test
    public void formatBytesReadable() {
        String s = SolarLogPaths.formatBytes(400L * 1024L * 1024L);
        if (s == null || !s.contains("MB")) {
            throw new AssertionError("format=" + s);
        }
    }

    @Test
    public void logDirsIncludeLegacyAndPrivate() {
        java.util.List<java.io.File> dirs = SolarLogPaths.logDirs(null);
        if (dirs == null || dirs.isEmpty()) throw new AssertionError("empty");
        boolean sawLegacy = false;
        boolean sawPrivate = false;
        for (java.io.File d : dirs) {
            String p = d.getAbsolutePath().replace('\\', '/');
            if (p.contains("solar/logs")) sawLegacy = true;
            if (p.contains("com.solar.launcher") || p.contains("files")) sawPrivate = true;
        }
        if (!sawLegacy) throw new AssertionError("expected solar/logs path");
        if (!sawPrivate) throw new AssertionError("expected app-private path");
    }
}
