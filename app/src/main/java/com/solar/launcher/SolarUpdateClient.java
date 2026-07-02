package com.solar.launcher;

import com.solar.launcher.net.SolarHttp;
import com.solar.launcher.net.TlsHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** OTA catalog from thesolarproject/solar-update GitHub Pages (updates.xml + APKs). */
public final class SolarUpdateClient {
    public static final String DEFAULT_UPDATES_URL =
            "https://thesolarproject.github.io/solar-update/updates.xml";
    /** Max rows per page on Change App Version screen (Y1 scroll). */
    public static final int PICKER_PAGE_SIZE = 12;
    /** Hard cap — entire published catalog should fit (see trimCatalog). */
    public static final int MAX_PICKER_RELEASES = 40;
    /** Max nightly APKs kept in published OTA catalog. */
    public static final int MAX_CATALOG_NIGHTLIES = 24;
    /** UTC build stamp in versionName — {@code YYYYMMDD-HHMM} (stable or nightly tag body). */
    private static final Pattern TIMESTAMP_VERSION =
            Pattern.compile("^\\d{8}-\\d{4}$");
    private static final Pattern TIMESTAMP_NIGHTLY_TAG =
            Pattern.compile("^nightly-\\d{8}-\\d{4}$");
    private static final Pattern TIMESTAMP_NIGHTLY_PARTS =
            Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})$");

    public static final class ReleaseInfo {
        public final String tag;
        public final String versionName;
        public final int versionCode;
        public final String apkUrl;
        public final boolean nightly;

        ReleaseInfo(String tag, String versionName, int versionCode, String apkUrl, boolean nightly) {
            this.tag = tag == null ? "" : tag;
            this.versionName = versionName == null ? "" : versionName;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
            this.nightly = nightly;
        }

        public String listLabel() {
            if (nightly) {
                return isTimestampNightly(tag) ? formatNightlyDisplayLabel(tag) : tag;
            }
            if (isTimestampVersionName(versionName)) {
                return formatTimestampDisplayLabel(versionName);
            }
            return "v" + versionName;
        }

        public boolean matchesInstalled(int localCode, String localName) {
            String local = localName == null ? "" : localName.trim();
            if (nightly) {
                if (!local.startsWith("nightly-")) return false;
                if (tag.equals(local)) return true;
                // ponytail: same catalog row if versionName matches even when tag string differs.
                return versionName != null && versionName.equals(local);
            }
            if (local.startsWith("nightly-")) return false;
            return local.equals(versionName);
        }

        public boolean isNewerThan(int localCode, String localName) {
            return compareToInstalled(localCode, localName) == InstallRelation.UPGRADE;
        }

        /** Same channel ordering — used to pick pm vs system install. */
        public InstallRelation compareToInstalled(int localCode, String localName) {
            if (matchesInstalled(localCode, localName)) return InstallRelation.SAME;
            String local = localName == null ? "" : localName.trim();
            if (nightly) {
                if (!local.startsWith("nightly-")) return InstallRelation.SIDEGRADE;
                if (versionCode > 0 && localCode > 0) {
                    if (versionCode > localCode) return InstallRelation.UPGRADE;
                    if (versionCode < localCode) return InstallRelation.DOWNGRADE;
                }
                return InstallRelation.SIDEGRADE;
            }
            if (local.startsWith("nightly-")) return InstallRelation.SIDEGRADE;
            int cmp = compareSemver(local, versionName);
            if (cmp < 0) return InstallRelation.UPGRADE;
            if (cmp > 0) return InstallRelation.DOWNGRADE;
            return InstallRelation.SIDEGRADE;
        }
    }

    public enum InstallRelation {
        SAME, UPGRADE, DOWNGRADE, SIDEGRADE
    }

    private SolarUpdateClient() {}

    public static List<ReleaseInfo> fetchUpdates(String updatesUrl) throws IOException {
        return filterForDevice(fetchUpdatesRaw(updatesUrl));
    }

    static List<ReleaseInfo> fetchUpdatesRaw(String updatesUrl) throws IOException {
        if (updatesUrl == null || updatesUrl.trim().isEmpty()) updatesUrl = DEFAULT_UPDATES_URL;
        TlsHelper.ensureSecurityProvider();
        Request req = new Request.Builder()
                .url(updatesUrl.trim())
                .header("User-Agent", "SolarLauncher/1.0")
                .header("Accept", "application/xml,text/xml,*/*")
                .build();
        OkHttpClient client = SolarHttp.longReadClient();
        Response resp = client.newCall(req).execute();
        try {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("updates.xml HTTP " + resp.code());
            }
            String xml = new String(resp.body().bytes(), "UTF-8");
            return parseUpdatesXml(xml);
        } finally {
            if (resp.body() != null) resp.body().close();
        }
    }

    static List<ReleaseInfo> parseUpdatesXml(String xml) {
        if (xml == null || xml.trim().isEmpty()) return new ArrayList<ReleaseInfo>();
        String base = DEFAULT_UPDATES_URL.replace("updates.xml", "");
        Matcher baseM = Pattern.compile("<solar-updates\\s+[^>]*\\bbase=\"([^\"]+)\"").matcher(xml);
        if (baseM.find()) base = baseM.group(1).trim();
        if (!base.endsWith("/")) base += "/";

        List<ReleaseInfo> out = new ArrayList<ReleaseInfo>();
        Matcher relM = Pattern.compile("<release\\s+([^>/]+)/?>").matcher(xml);
        while (relM.find()) {
            Map<String, String> attrs = parseAttrs(relM.group(1));
            String tag = attrs.get("tag");
            String apk = attrs.get("apk");
            if (tag == null || tag.isEmpty() || apk == null || apk.isEmpty()) continue;
            String versionName = attrs.containsKey("versionName") ? attrs.get("versionName") : tag;
            int versionCode = parseIntPart(attrs.get("versionCode"));
            boolean nightly = "true".equalsIgnoreCase(attrs.get("nightly"))
                    || tag.startsWith("nightly-");
            String apkUrl = apk.startsWith("http://") || apk.startsWith("https://")
                    ? apk : base + apk;
            out.add(new ReleaseInfo(tag, versionName, versionCode, apkUrl, nightly));
        }
        return out;
    }

    private static Map<String, String> parseAttrs(String raw) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Matcher m = Pattern.compile("(\\w+)=\"([^\"]*)\"").matcher(raw);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }


    public static boolean isTimestampNightly(String tag) {
        return tag != null && TIMESTAMP_NIGHTLY_TAG.matcher(tag).matches();
    }

    /** Stable channel build stamps — {@code 20260629-1615} without nightly prefix. */
    public static boolean isTimestampVersionName(String versionName) {
        return versionName != null && TIMESTAMP_VERSION.matcher(versionName.trim()).matches();
    }

    /** Human-readable UTC label for timestamp stable builds. */
    public static String formatTimestampDisplayLabel(String versionName) {
        if (!isTimestampVersionName(versionName)) {
            return versionName == null ? "" : versionName;
        }
        return formatNightlyDisplayLabel(versionName);
    }

    public static String formatNightlyDisplayLabel(String tag) {
        if (tag == null) return "";
        String rest = tag;
        if (rest.startsWith("nightly-")) {
            if (!isTimestampNightly(tag)) return tag;
            rest = tag.substring("nightly-".length());
        } else if (!isTimestampVersionName(rest)) {
            return tag;
        }
        Matcher m = TIMESTAMP_NIGHTLY_PARTS.matcher(rest);
        if (!m.matches()) return tag;
        try {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            int hh = Integer.parseInt(m.group(4));
            int mm = Integer.parseInt(m.group(5));
            java.util.Calendar cal = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"));
            cal.set(y, mo - 1, d, hh, mm, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                    "MMM d, yyyy · HH:mm", Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return fmt.format(cal.getTime()) + " UTC";
        } catch (Exception e) {
            return tag;
        }
    }

    static boolean includeInNightlyPicker(ReleaseInfo r, int localCode, String localName) {
        if (r == null || !r.nightly) return false;
        if (isTimestampNightly(r.tag)) return true;
        return r.matchesInstalled(localCode, localName);
    }

    public static ReleaseInfo latestStable(List<ReleaseInfo> releases) {
        ReleaseInfo best = null;
        for (ReleaseInfo r : releases) {
            if (r.nightly) continue;
            if (best == null || compareStableRelease(r, best) > 0) best = r;
        }
        return best;
    }

    public static ReleaseInfo latestNightly(List<ReleaseInfo> releases) {
        ReleaseInfo best = null;
        for (ReleaseInfo r : releases) {
            if (!r.nightly || !isTimestampNightly(r.tag)) continue;
            if (best == null || r.versionCode > best.versionCode) best = r;
        }
        return best;
    }

    /** Human-readable version for About / update rows (no double v-prefix). */
    public static String formatVersionLabel(String versionName) {
        if (versionName == null) return "";
        String v = versionName.trim();
        if (v.isEmpty()) return "";
        if (isTimestampNightly(v)) return formatNightlyDisplayLabel(v);
        if (isTimestampVersionName(v)) return formatTimestampDisplayLabel(v);
        if (v.startsWith("nightly-")) return v;
        if (v.startsWith("v")) return v;
        return "v" + v;
    }

    /** Y1/Y2 OTA APK names: {@code solar-y1-nightly-…} / {@code solar-y2-v0.4.apk}. */
    public static List<ReleaseInfo> filterForDevice(List<ReleaseInfo> all) {
        if (all == null || all.isEmpty()) return new ArrayList<ReleaseInfo>();
        String variant = deviceVariant();
        List<ReleaseInfo> out = new ArrayList<ReleaseInfo>();
        for (ReleaseInfo r : all) {
            if (matchesDeviceVariant(r, variant)) out.add(r);
        }
        return out;
    }

    static String deviceVariant() {
        return DeviceFeatures.deviceFamily();
    }

    static boolean matchesDeviceVariant(ReleaseInfo r, String variant) {
        if (r == null || variant == null) return false;
        String apk = r.apkUrl == null ? "" : r.apkUrl.toLowerCase(Locale.US);
        String tag = r.tag == null ? "" : r.tag.toLowerCase(Locale.US);
        if (apk.contains("-y1-") || apk.contains("-y2-")
                || tag.contains("-y1-") || tag.contains("-y2-")) {
            return apk.contains("-" + variant + "-") || tag.contains("-" + variant + "-");
        }
        return true;
    }

    /**
     * Newest-first list for the version picker — same channel as installed build
     * (stable timestamps / semver vs nightly), capped at maxItems.
     */
    public static List<ReleaseInfo> releasesForPicker(List<ReleaseInfo> all,
            int localCode, String localName, int maxItems) {
        all = filterForDevice(all);
        if (all == null || all.isEmpty()) return new ArrayList<ReleaseInfo>();
        if (maxItems <= 0) maxItems = MAX_PICKER_RELEASES;

        String local = localName == null ? "" : localName.trim();
        boolean onNightly = local.startsWith("nightly-");

        List<ReleaseInfo> channel = new ArrayList<ReleaseInfo>();
        for (ReleaseInfo r : all) {
            if (onNightly) {
                if (!r.nightly) continue;
                if (!includeInNightlyPicker(r, localCode, local)) continue;
            } else if (r.nightly) {
                continue;
            }
            channel.add(r);
        }
        sortNewestFirst(channel);

        ReleaseInfo installed = null;
        for (ReleaseInfo r : channel) {
            if (r.matchesInstalled(localCode, local)) {
                installed = r;
                break;
            }
        }

        List<ReleaseInfo> out = new ArrayList<ReleaseInfo>();
        for (ReleaseInfo r : channel) {
            if (out.size() >= maxItems) break;
            out.add(r);
        }

        if (installed != null && !containsRelease(out, installed)) {
            if (out.size() >= maxItems && !out.isEmpty()) {
                out.remove(out.size() - 1);
            }
            out.add(installed);
            sortNewestFirst(out);
        }
        return out;
    }

    /** All releases in the installed channel — for paginated picker UI. */
    public static List<ReleaseInfo> releasesForPickerAll(List<ReleaseInfo> all,
            int localCode, String localName) {
        return releasesForPicker(all, localCode, localName, MAX_PICKER_RELEASES);
    }

    /** Compare stable releases — timestamp builds use versionCode; semver uses dotted parts. */
    static int compareStableRelease(ReleaseInfo a, ReleaseInfo b) {
        if (a.versionCode > 0 || b.versionCode > 0) {
            if (a.versionCode != b.versionCode) {
                return a.versionCode < b.versionCode ? -1 : 1;
            }
        }
        return compareSemver(a.versionName, b.versionName);
    }

    /** Trim catalog entries before publishing — keeps all stables + newest nightlies. */
    public static List<ReleaseInfo> trimCatalog(List<ReleaseInfo> all, int maxNightlies) {
        if (all == null || all.isEmpty()) return new ArrayList<ReleaseInfo>();
        if (maxNightlies <= 0) maxNightlies = MAX_CATALOG_NIGHTLIES;

        List<ReleaseInfo> stables = new ArrayList<ReleaseInfo>();
        List<ReleaseInfo> nightlies = new ArrayList<ReleaseInfo>();
        for (ReleaseInfo r : all) {
            if (r.nightly && isTimestampNightly(r.tag)) nightlies.add(r);
            else stables.add(r);
        }
        sortNewestFirst(stables);
        sortNewestFirst(nightlies);
        if (nightlies.size() > maxNightlies) {
            nightlies = new ArrayList<ReleaseInfo>(nightlies.subList(0, maxNightlies));
        }

        List<ReleaseInfo> out = new ArrayList<ReleaseInfo>();
        out.addAll(nightlies);
        out.addAll(stables);
        sortNewestFirst(out);
        return out;
    }

    static void sortNewestFirst(List<ReleaseInfo> releases) {
        if (releases == null || releases.size() < 2) return;
        java.util.Collections.sort(releases, new java.util.Comparator<ReleaseInfo>() {
            @Override
            public int compare(ReleaseInfo a, ReleaseInfo b) {
                if (a.nightly != b.nightly) return a.nightly ? -1 : 1;
                if (a.nightly) {
                    if (a.versionCode != b.versionCode) {
                        return a.versionCode > b.versionCode ? -1 : 1;
                    }
                    return b.tag.compareTo(a.tag);
                }
                return -compareStableRelease(a, b);
            }
        });
    }

    private static boolean containsRelease(List<ReleaseInfo> list, ReleaseInfo target) {
        for (ReleaseInfo r : list) {
            if (r.tag.equals(target.tag)) return true;
        }
        return false;
    }

    static int parseNightlyCode(String tag) {
        if (tag == null || !tag.startsWith("nightly-")) return 0;
        String rest = tag.substring("nightly-".length());
        Matcher ts = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})$").matcher(rest);
        if (ts.matches()) {
            try {
                int y = Integer.parseInt(ts.group(1));
                int mo = Integer.parseInt(ts.group(2));
                int d = Integer.parseInt(ts.group(3));
                int hh = Integer.parseInt(ts.group(4));
                int mm = Integer.parseInt(ts.group(5));
                java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                cal.set(y, mo - 1, d, hh, mm, 0);
                cal.set(java.util.Calendar.MILLISECOND, 0);
                java.util.Calendar epoch = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                epoch.set(2020, 0, 1, 0, 0, 0);
                epoch.set(java.util.Calendar.MILLISECOND, 0);
                long minutes = (cal.getTimeInMillis() - epoch.getTimeInMillis()) / 60_000L;
                if (minutes < 0 || minutes > Integer.MAX_VALUE) return 0;
                return (int) minutes;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        try {
            return Integer.parseInt(rest);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** ponytail: naive semver — enough for v0.2 style tags */
    static int compareSemver(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseIntPart(pa[i]) : 0;
            int vb = i < pb.length ? parseIntPart(pb[i]) : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        return 0;
    }

    static int parseIntPart(String s) {
        if (s == null || s.isEmpty()) return 0;
        StringBuilder n = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') n.append(c);
            else break;
        }
        if (n.length() == 0) return 0;
        try {
            return Integer.parseInt(n.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void selfCheck() {
        if (compareSemver("0.1", "0.2") >= 0) throw new AssertionError("0.1 < 0.2");
        if (compareSemver("0.2", "0.2") != 0) throw new AssertionError("0.2 eq");
        if (parseNightlyCode("nightly-42") != 42) throw new AssertionError("nightly code");
        if (parseNightlyCode("nightly-20240622-1530") <= 0) {
            throw new AssertionError("nightly timestamp code");
        }
        if (parseNightlyCode("nightly-20240622-1545") <= parseNightlyCode("nightly-20240622-1530")) {
            throw new AssertionError("nightly timestamp ordering");
        }
        ReleaseInfo y1Only = new ReleaseInfo("nightly-1", "nightly-1", 1,
                "https://x/solar-y1-nightly-1.apk", true);
        ReleaseInfo y2Only = new ReleaseInfo("nightly-1", "nightly-1", 1,
                "https://x/solar-y2-nightly-1.apk", true);
        if (!matchesDeviceVariant(y1Only, "y1")) throw new AssertionError("y1 match");
        if (matchesDeviceVariant(y2Only, "y1")) throw new AssertionError("y1 rejects y2");
        ReleaseInfo universal = new ReleaseInfo("v0.3", "0.3", 0,
                "https://x/solar-v0.3.apk", false);
        if (!matchesDeviceVariant(universal, "y1")) throw new AssertionError("universal y1");
        if (!matchesDeviceVariant(universal, "y2")) throw new AssertionError("universal y2");
        List<ReleaseInfo> mixed = new ArrayList<ReleaseInfo>();
        mixed.add(y1Only);
        mixed.add(y2Only);
        if (filterForDevice(mixed).size() != 1) throw new AssertionError("device filter");
        ReleaseInfo r = new ReleaseInfo("v0.2", "0.2", 0, "https://x/a.apk", false);
        if (!r.isNewerThan(1, "0.1")) throw new AssertionError("stable newer");
        ReleaseInfo n = new ReleaseInfo("nightly-10", "nightly-10", 10, "https://x/a.apk", true);
        if (!n.isNewerThan(5, "nightly-5")) throw new AssertionError("nightly newer");
        ReleaseInfo ts = new ReleaseInfo("nightly-20240622-1530", "nightly-20240622-1530", 100,
                "https://x/a.apk", true);
        if (!ts.listLabel().contains("2024")) throw new AssertionError("nightly display label");
        if (isTimestampNightly("nightly-52")) throw new AssertionError("legacy not timestamp");
        if (!isTimestampNightly("nightly-20240622-1530")) throw new AssertionError("timestamp tag");
        if (!n.matchesInstalled(10, "nightly-10")) throw new AssertionError("nightly installed");
        if (!r.matchesInstalled(0, "0.2")) throw new AssertionError("stable installed");
        if (n.compareToInstalled(5, "nightly-5") != InstallRelation.UPGRADE) {
            throw new AssertionError("nightly upgrade");
        }
        if (n.compareToInstalled(15, "nightly-15") != InstallRelation.DOWNGRADE) {
            throw new AssertionError("nightly downgrade");
        }
        if (r.compareToInstalled(0, "0.3") != InstallRelation.DOWNGRADE) {
            throw new AssertionError("stable downgrade");
        }
        List<ReleaseInfo> many = new ArrayList<ReleaseInfo>();
        String variant = deviceVariant();
        for (int i = 1; i <= 20; i++) {
            many.add(new ReleaseInfo("nightly-" + i, "nightly-" + i, i,
                    "https://x/solar-" + variant + "-nightly-" + i + ".apk", true));
        }
        many.add(new ReleaseInfo("v0.2", "0.2", 0, "https://x/s.apk", false));
        List<ReleaseInfo> legacyMany = new ArrayList<ReleaseInfo>();
        for (int i = 1; i <= 20; i++) {
            legacyMany.add(new ReleaseInfo("nightly-" + i, "nightly-" + i, i,
                    "https://x/solar-nightly-" + i + ".apk", true));
        }
        List<ReleaseInfo> legacyPicked = releasesForPicker(legacyMany, 3, "nightly-5", MAX_PICKER_RELEASES);
        if (!legacyPicked.isEmpty() && legacyPicked.size() > 1) {
            throw new AssertionError("legacy nightlies should not fill picker");
        }
        List<ReleaseInfo> tsMany = new ArrayList<ReleaseInfo>();
        tsMany.add(new ReleaseInfo("nightly-20240622-1530", "nightly-20240622-1530", 10,
                "https://x/a.apk", true));
        tsMany.add(new ReleaseInfo("nightly-20240622-1545", "nightly-20240622-1545", 11,
                "https://x/b.apk", true));
        List<ReleaseInfo> picked = releasesForPicker(tsMany, 3, "0.2.1", MAX_PICKER_RELEASES);
        if (picked.size() != 2) throw new AssertionError("timestamp picker count");
        if (!picked.get(0).tag.endsWith("1545")) throw new AssertionError("newest timestamp first");
        List<ReleaseInfo> trimmed = trimCatalog(many, 5);
        int nightlyCount = 0;
        for (ReleaseInfo r2 : trimmed) if (r2.nightly) nightlyCount++;
        if (nightlyCount > 5) throw new AssertionError("catalog trim nightlies");
        if (!"nightly-20".equals(formatVersionLabel("nightly-20"))) {
            throw new AssertionError("nightly label format");
        }
        if (!"v0.2".equals(formatVersionLabel("0.2"))) throw new AssertionError("stable label format");
        String sample = "<?xml version=\"1.0\"?><solar-updates base=\"https://example.com/ota/\">"
                + "<release tag=\"v0.2\" versionName=\"0.2\" versionCode=\"0\" nightly=\"false\" apk=\"solar-v0.2.apk\"/>"
                + "<release tag=\"nightly-9\" versionName=\"nightly-9\" versionCode=\"9\" nightly=\"true\" apk=\"solar-nightly-9.apk\"/>"
                + "</solar-updates>";
        List<ReleaseInfo> parsed = parseUpdatesXml(sample);
        if (parsed.size() != 2) throw new AssertionError("xml count");
        if (!parsed.get(0).apkUrl.equals("https://example.com/ota/solar-v0.2.apk")) {
            throw new AssertionError("xml base url");
        }
    }
}
