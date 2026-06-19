package com.solar.launcher;

import com.solar.launcher.net.SolarHttp;
import com.solar.launcher.net.TlsHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            return nightly ? tag : ("v" + versionName);
        }

        public boolean matchesInstalled(int localCode, String localName) {
            String local = localName == null ? "" : localName.trim();
            if (nightly) return local.startsWith("nightly-") && tag.equals(local);
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

    public static ReleaseInfo latestStable(List<ReleaseInfo> releases) {
        ReleaseInfo best = null;
        for (ReleaseInfo r : releases) {
            if (r.nightly) continue;
            if (best == null || compareSemver(best.versionName, r.versionName) < 0) best = r;
        }
        return best;
    }

    public static ReleaseInfo latestNightly(List<ReleaseInfo> releases) {
        ReleaseInfo best = null;
        for (ReleaseInfo r : releases) {
            if (!r.nightly) continue;
            if (best == null || r.versionCode > best.versionCode) best = r;
        }
        return best;
    }

    static int parseNightlyCode(String tag) {
        if (tag == null || !tag.startsWith("nightly-")) return 0;
        try {
            return Integer.parseInt(tag.substring("nightly-".length()));
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
        ReleaseInfo r = new ReleaseInfo("v0.2", "0.2", 0, "https://x/a.apk", false);
        if (!r.isNewerThan(1, "0.1")) throw new AssertionError("stable newer");
        ReleaseInfo n = new ReleaseInfo("nightly-10", "nightly-10", 10, "https://x/a.apk", true);
        if (!n.isNewerThan(5, "nightly-5")) throw new AssertionError("nightly newer");
        if (!"nightly-10".equals(n.listLabel())) throw new AssertionError("nightly label");
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
        if (n.compareToInstalled(0, "0.5") != InstallRelation.SIDEGRADE) {
            throw new AssertionError("stable to nightly sidgrade");
        }
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
