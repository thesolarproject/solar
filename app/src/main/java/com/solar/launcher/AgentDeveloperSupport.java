package com.solar.launcher;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 2026-11-25 — Helpers for Debug → Agent Developer Menu.
 * Layman: finds test APKs, reports connection info, and builds safe install commands for agents.
 * Technical: pure/static helpers where possible; command execution stays in UI callers.
 */
public final class AgentDeveloperSupport {

    public static final String TEST_APK_PRIMARY = "/sdcard/Solar/test.apk";
    public static final String TEST_APK_FALLBACK = "/sdcard/Download/app-release.apk";

    private AgentDeveloperSupport() {}

    public static String findTestApkPath() {
        File primary = new File(TEST_APK_PRIMARY);
        if (primary.isFile() && primary.canRead()) return primary.getAbsolutePath();
        File fallback = new File(TEST_APK_FALLBACK);
        if (fallback.isFile() && fallback.canRead()) return fallback.getAbsolutePath();
        return null;
    }

    public static String describeTestApk(String path) {
        if (path == null) return null;
        File f = new File(path);
        if (!f.isFile()) return path;
        return path + " (" + f.length() + " bytes)";
    }

    public static String resolveAdbTcpPort(String rawPort) {
        if (rawPort == null) return null;
        String p = rawPort.trim();
        if (p.isEmpty() || "-1".equals(p) || "0".equals(p)) return null;
        return p;
    }

    public static String firstIpv4Address() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            List<NetworkInterface> list = Collections.list(ifaces);
            for (NetworkInterface ni : list) {
                if (ni == null || ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String buildInstallCommand(String apkPath) {
        if (apkPath == null) return null;
        String trimmed = apkPath.trim();
        if (trimmed.isEmpty()) return null;
        // ponytail: fixed known paths only; no shell quoting gymnastics for arbitrary user input.
        if (!TEST_APK_PRIMARY.equals(trimmed) && !TEST_APK_FALLBACK.equals(trimmed)) return null;
        return "pm install -r " + trimmed;
    }
}
