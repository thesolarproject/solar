package com.solar.launcher;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 2026-07-05 — Device family hub: Y1 (MT6572), Y2 (MT6582), A5 (Timmkoo touch 240×320).
 * 2026-07-11 — A5 is exclusive third family (not Y1-by-default); touch + portrait themes.
 * 2026-07-15 — Dual-storage resilience: all families scan every healthy volume;
 *   Primary storage pref only picks where new downloads/recordings go;
 *   Y1 may discover Internal via A5-style probes when a second mount exists.
 * 2026-07-15 — A5 stock props often claim model=Y1; use display 240×320 + input scheme,
 *   not ro.product.model alone (staging/adb must use the same checks).
 * Intentional divergences: Y1 no power button (BACK-long overlay); Y2 power/BACK-long;
 *   Y1 AVRCP native BT patches; Y2 dual storage; Y2 hides volume/lock chips;
 *   A5 touchscreen, dual face/side nav modes, portrait default, overlay volume/lock.
 * Reversal: drop isA5 branches; restore isY1 = !isY2; revert Y1 secondary to null.
 */
public final class DeviceFeatures {
    private static final String TAG = "DeviceFeatures";
    /** Legacy bool — migrated into {@link #PREF_Y2_PRIMARY_MEDIA}; kept for old installs. */
    public static final String PREF_Y2_USE_INTERNAL_MEDIA = "y2_use_internal_media";
    /** Y2 only — when true, long OK sleeps the screen (legacy); default false opens quick menu instead. */
    public static final String PREF_Y2_HOLD_OK_TO_SLEEP = "y2_hold_ok_to_sleep";
    /** Primary save target on any family: {@link #PRIMARY_MEDIA_MICROSD} or {@link #PRIMARY_MEDIA_INTERNAL}. */
    public static final String PREF_Y2_PRIMARY_MEDIA = "y2_primary_media";
    public static final String PRIMARY_MEDIA_MICROSD = "microsd";
    public static final String PRIMARY_MEDIA_INTERNAL = "internal";
    /** Emulator / lab override — persist.solar.device_family = y1|y2|a5. */
    public static final String PROP_DEVICE_FAMILY = "persist.solar.device_family";
    private static final String PREFS = "SOLAR_SETTINGS";
    private static volatile String cachedFamily;
    private static Boolean testMicroSdPresentOverride;

    private DeviceFeatures() {}

    public static String deviceFamily() {
        return detectFamily();
    }

    public static String deviceModel() {
        if (isA5()) return "A5";
        return isY2() ? "Y2" : "Y1";
    }

    public static String deviceModelLabel() {
        if (isA5()) return "A5";
        return isY2() ? "Y2" : "Y1";
    }

    /**
     * Short product name for USB eject copy ("Please eject your Y1…").
     * Alias of {@link #deviceModelLabel()} — kept for call sites / companion parity.
     */
    public static String productModelLabel() {
        return deviceModelLabel();
    }

    public static int soulseekClientMinor() {
        // Reach-specific minor under experimental major 177 (not Nicotine+ 160.x).
        if (isA5()) return 103;
        return isY2() ? 102 : 101;
    }

    public static String reachClientName() {
        // 2026-07-11 — A5 is Timmkoo, not Innioasis; keep Reach brand clear.
        if (isA5()) return "Reach for Timmkoo A5";
        return "Reach for Innioasis " + deviceModelLabel();
    }

    /** Soulseek profile description peers see — reflects Sharing / Messaging toggles. */
    public static String reachUserBio(android.content.Context ctx, boolean sharingEnabled,
            boolean messagingEnabled) {
        if (ctx == null) return reachClientName();
        String intro = ctx.getString(com.solar.launcher.R.string.reach_user_bio_intro, deviceModelLabel());
        String sharingState = ctx.getString(sharingEnabled
                ? com.solar.launcher.R.string.common_on : com.solar.launcher.R.string.common_off);
        String messagingState = ctx.getString(messagingEnabled
                ? com.solar.launcher.R.string.common_on : com.solar.launcher.R.string.common_off);
        String status = ctx.getString(com.solar.launcher.R.string.reach_user_bio_status,
                sharingState, messagingState);
        String shareNote = ctx.getString(sharingEnabled
                ? com.solar.launcher.R.string.reach_user_bio_note_share_on
                : com.solar.launcher.R.string.reach_user_bio_note_share_off);
        String msgNote = ctx.getString(messagingEnabled
                ? com.solar.launcher.R.string.reach_user_bio_note_msg_on
                : com.solar.launcher.R.string.reach_user_bio_note_msg_off);
        return intro + "\n\n" + status + "\n\n" + shareNote + " " + msgNote;
    }

    public static boolean isY2() {
        return "y2".equals(detectFamily());
    }

    /** Exclusive Y1 — not “everything that is not Y2” (A5 is its own family). */
    public static boolean isY1() {
        return "y1".equals(detectFamily());
    }

    /** 2026-07-11 — Timmkoo A5 touch MP3 (240×320 portrait). */
    public static boolean isA5() {
        return "a5".equals(detectFamily());
    }

    /**
     * Touch UI path — A5, or phone chrome (click wheel needs touch).
     * 2026-07-20 — Was: A5 only. Now: also when PhoneChromePolicy would activate.
     * Reversal: return isA5() only.
     */
    public static boolean hasTouchscreen() {
        if (isA5()) return true;
        try {
            int[] px = readDisplayPixels();
            return com.solar.launcher.phone.PhoneChromePolicy.active(
                    px[0], px[1], readDeviceFamilyPinForPhoneChrome());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 2026-07-15 — Overlay Volume + Sleep/Zzz chips — Y1/A5 (or emulator pin).
     * Was: named “lock”; same gate, Sleep chip is now rightmost. Y2 stays false (HW buttons).
     */
    public static boolean showsOverlayVolumeLockChips() {
        if (isY1() || isA5()) return true;
        // 2026-07-11 — Emulator y1/y2/a5 pins still show Solar volume/sleep chips.
        return EmulatorInputMap.isEmulator();
    }

    /** AVD / sdk build — for volume HUD and input map. */
    public static boolean isEmulator() {
        return EmulatorInputMap.isEmulator();
    }

    public static boolean hasRootAccess() {
        // 2026-07-15 — A5 Solar ROM bakes the same setuid su as Y1 (verify-a5-rom-contents).
        // Was: Y1/Y2 only — UMS + prep helpers treated A5 as unrooted even on Solar ROM.
        // Reversal: return isY1() || isY2() if stock A5 without su returns as a product SKU.
        return isY1() || isY2() || isA5();
    }

    /** True when su actually works from this app — ROM-only APK installs must fail-open without root. */
    public static boolean canRunRootShell() {
        return RootShell.canRun();
    }

    /**
     * Expected MicroSD / primary volume path string (may not be mounted).
     * 2026-07-11 — A5: TF when present else emulated sdcard0.
     * 2026-07-15 — Same idea on all families: removable/user card path when known.
     */
    public static String primaryStoragePath() {
        if (isY2()) return "/storage/sdcard1";
        if (isA5()) {
            // A5 often uses sdcard0 as TF; prefer sdcard1 when both exist (Y2-like).
            File sd1 = new File("/storage/sdcard1");
            if (sd1.isDirectory()) return "/storage/sdcard1";
            return "/storage/sdcard0";
        }
        // Y1: user MicroSD (soldered or slot) appears as sdcard0.
        return "/storage/sdcard0";
    }

    /**
     * Internal volume path when a second public mount exists.
     * 2026-07-15 — Y1 uses A5-style probes so eMMC can host media when the card dies.
     * Reversal: return null on Y1 only (pre-dual-storage policy).
     */
    public static String secondaryStoragePath() {
        if (isY2()) return "/storage/sdcard0";
        // Y1 + A5: discover a distinct Internal mount when hardware exposes one.
        return discoverSecondaryPath(primaryStoragePath());
    }

    /**
     * Probe candidates for Internal Storage when primary is the MicroSD path.
     * Layman: find the other drive if the player has one.
     * Tech: /data/media/0, peer /storage/sdcard*, /mnt/sdcard — skip aliases of primary.
     */
    private static String discoverSecondaryPath(String primary) {
        if (primary == null) primary = "/storage/sdcard0";
        // When primary is sdcard1, peer internal is usually sdcard0.
        if ("/storage/sdcard1".equals(primary)) {
            File sd0 = new File("/storage/sdcard0");
            if (sd0.isDirectory()) return "/storage/sdcard0";
        }
        File dataMedia = new File("/data/media/0");
        if (dataMedia.isDirectory() && !samePath(primary, dataMedia.getAbsolutePath())) {
            return "/data/media/0";
        }
        // Y1/A5: some images expose a second /storage/sdcard1 as “internal-ish” peer.
        if ("/storage/sdcard0".equals(primary)) {
            File sd1 = new File("/storage/sdcard1");
            if (sd1.isDirectory()) return "/storage/sdcard1";
        }
        File mnt = new File("/mnt/sdcard");
        if (mnt.isDirectory() && !samePath(primary, mnt.getAbsolutePath())
                && !"/storage/sdcard0".equals(primary)) {
            return "/mnt/sdcard";
        }
        return null;
    }

    /** Path equality without requiring both files to exist. */
    private static boolean samePath(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /**
     * Volume paths exported to the PC in USB mass-storage mode (2026-07-05).
     * Layman: share Internal first when dual, then MicroSD.
     * Tech: vold LUN order — internal then primary; single-volume devices export one root.
     */
    public static List<String> getUmsExportVolumePaths() {
        List<String> paths = new ArrayList<String>();
        String secondary = secondaryStoragePath();
        if (secondary != null) paths.add(secondary);
        String primary = primaryStoragePath();
        if (!samePath(primary, secondary)) paths.add(primary);
        if (paths.isEmpty()) paths.add("/storage/sdcard0");
        return paths;
    }

    /**
     * True when a volume is mounted and usable for media browse/write.
     * 2026-07-15 — Skips dead/0B cards that still appear as directories.
     * Layman: the card must open and have somewhere to put a file.
     */
    public static boolean isStorageVolumeHealthy(File root) {
        if (root == null || !root.isDirectory()) return false;
        try {
            if (!root.canRead()) return false;
            long free = root.getUsableSpace();
            if (free > 0L) return true;
            // Some mounts report 0 free while still writable — probe canWrite.
            return root.canWrite();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * MicroSD root when healthy; else null (do not fall back to Internal here).
     * 2026-07-15 — Callers that need “any volume” use {@link #getStorageRoots()}.
     * 2026-07-20 — Phone chrome may override with MicroSD/ under the user folder.
     * Reversal: drop phone override block.
     */
    public static File getMicroSdRoot() {
        if (testMicroSdPresentOverride != null && !testMicroSdPresentOverride.booleanValue()) {
            return null;
        }
        try {
            android.content.Context app = com.solar.launcher.SolarApplication.getAppContext();
            if (app != null) {
                File phone = com.solar.launcher.phone.PhoneStorageRoots.microSdRootOverride(app);
                if (phone != null) return phone;
            }
        } catch (Throwable ignored) {}
        File primary = new File(primaryStoragePath());
        if (isStorageVolumeHealthy(primary)) return primary;
        return null;
    }

    /**
     * Public Internal Storage root when healthy; else null.
     * 2026-07-15 — Available even when MicroSD is absent (Y2 sold without a card).
     * 2026-07-20 — Phone chrome may override with Internal/ under the user folder.
     * Reversal: drop phone override block.
     */
    public static File getInternalStorageRoot() {
        try {
            android.content.Context app = com.solar.launcher.SolarApplication.getAppContext();
            if (app != null) {
                File phone = com.solar.launcher.phone.PhoneStorageRoots.internalRootOverride(app);
                if (phone != null) return phone;
            }
        } catch (Throwable ignored) {}
        String secondary = secondaryStoragePath();
        if (secondary == null) return null;
        File internal = new File(secondary);
        if (isStorageVolumeHealthy(internal)) return internal;
        // Directory present but “unhealthy” — still return if readable so library can list.
        if (internal.isDirectory() && internal.canRead()) return internal;
        return null;
    }

    /**
     * Preferred user volume for legacy wipe/compat — MicroSD if healthy, else Internal, else path stub.
     * 2026-07-15 — Never leave callers hanging when only Internal is mounted.
     */
    public static File getPrimaryStorageRoot() {
        File micro = getMicroSdRoot();
        if (micro != null) return micro;
        File internal = getInternalStorageRoot();
        if (internal != null) return internal;
        // Fail-open stub so mkdir callers do not NPE — may be unusable until a card mounts.
        return new File(primaryStoragePath());
    }

    /**
     * Internal volume when mounted — no longer requires MicroSD to also be present.
     * 2026-07-15 — Was: both dirs required (broke no-card Y2). Now: Internal alone is fine.
     * Reversal: require primary.isDirectory() again.
     */
    public static File getSecondaryStorageRoot() {
        return getInternalStorageRoot();
    }

    /**
     * All healthy mounted user volumes (MicroSD + Internal when both exist).
     * 2026-07-15 — Library scans union these; never gate on the Primary storage pref.
     */
    public static java.util.List<File> getStorageRoots() {
        java.util.List<File> roots = new java.util.ArrayList<File>();
        addUniqueRoot(roots, getMicroSdRoot());
        addUniqueRoot(roots, getInternalStorageRoot());
        // Emulator / A5: also expose /mnt/sdcard when distinct and healthy.
        if (isEmulator() || isA5()) {
            File mnt = new File("/mnt/sdcard");
            if (isStorageVolumeHealthy(mnt)) addUniqueRoot(roots, mnt);
        }
        // Fail-open: at least one path entry so media helpers never see an empty list.
        if (roots.isEmpty()) {
            File stub = getPrimaryStorageRoot();
            if (stub != null) roots.add(stub);
        }
        return roots;
    }

    /** Append root when non-null and path not already listed. */
    private static void addUniqueRoot(java.util.List<File> roots, File root) {
        if (root == null) return;
        String path = root.getAbsolutePath();
        for (int i = 0; i < roots.size(); i++) {
            if (path.equals(roots.get(i).getAbsolutePath())) return;
        }
        roots.add(root);
    }

    /** Roots the file browser may open — same as {@link #getStorageRoots()}. */
    public static java.util.List<File> getBrowsableStorageRoots() {
        return getStorageRoots();
    }

    /**
     * Public Internal (eMMC) Themes/ folder when Internal volume exists; else null.
     * 2026-07-17 — Canonical theme <b>store</b> for load (with filesDir fallback in ThemeManager).
     * MicroSD Themes/ is the 1:1 manage mirror only.
     */
    public static File getInternalPublicThemesDir() {
        File internal = getInternalStorageRoot();
        if (internal == null) return null;
        return new File(internal, "Themes");
    }

    /**
     * MicroSD Themes/ folder when the card is healthy; else null.
     * 2026-07-17 — 1:1 peer mirror of the MMC theme store (users may manage either folder).
     */
    public static File getMicroSdThemesDir() {
        File micro = getMicroSdRoot();
        if (micro == null) return null;
        return new File(micro, "Themes");
    }

    /** True when {@code dir} is a top-level browsable volume root on this device. */
    public static boolean isStorageVolumeRoot(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        String path = dir.getAbsolutePath();
        for (File root : getStorageRoots()) {
            if (path.equals(root.getAbsolutePath())) return true;
        }
        return false;
    }

    /** True when this family can show MicroSD ↔ Internal labels / Primary storage UI. */
    public static boolean supportsDualStorageUi() {
        return getInternalStorageRoot() != null || secondaryStoragePath() != null
                || isY2() || isA5();
    }

    /** User-facing label for a storage volume (Settings, folder browser cross-links). */
    public static String storageRootLabel(Context ctx, File root) {
        if (root == null) return "";
        String path = root.getAbsolutePath();
        String primary = primaryStoragePath();
        String secondary = secondaryStoragePath();
        if (path.equals(primary) || (getMicroSdRoot() != null
                && path.equals(getMicroSdRoot().getAbsolutePath()))) {
            return ctx != null ? ctx.getString(R.string.storage_volume_microsd) : "MicroSD";
        }
        if (secondary != null && (path.equals(secondary)
                || (getInternalStorageRoot() != null
                && path.equals(getInternalStorageRoot().getAbsolutePath())))) {
            return ctx != null ? ctx.getString(R.string.storage_volume_internal)
                    : "Internal Storage";
        }
        // Single-volume leftovers still read as MicroSD for breadcrumbs.
        return ctx != null ? ctx.getString(R.string.storage_volume_microsd) : "MicroSD";
    }

    /**
     * True when the user-facing MicroSD volume is healthy.
     * 2026-07-15 — Y2 sold without a card → false so pref defaults to Internal.
     */
    public static boolean isMicroSdPresent() {
        if (testMicroSdPresentOverride != null) {
            return testMicroSdPresentOverride.booleanValue();
        }
        return getMicroSdRoot() != null;
    }

    /** Pref: new downloads go to Internal (library scans still union every volume). */
    public static boolean useInternalForNewMedia(Context ctx) {
        return PRIMARY_MEDIA_INTERNAL.equals(resolvePrimaryMediaPref(ctx));
    }

    /**
     * Resolved primary medium for new saves — all families.
     * 2026-07-15 — Was Y2/A5-only (Y1 forced microsd). Now smart-default on every family.
     * Reversal: early-return PRIMARY_MEDIA_MICROSD when !isY2 && !isA5.
     */
    public static String resolvePrimaryMediaPref(Context ctx) {
        if (ctx == null) {
            return isMicroSdPresent() ? PRIMARY_MEDIA_MICROSD : PRIMARY_MEDIA_INTERNAL;
        }
        android.content.SharedPreferences prefs =
                ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String explicit = prefs.getString(PREF_Y2_PRIMARY_MEDIA, null);
        if (PRIMARY_MEDIA_MICROSD.equals(explicit) || PRIMARY_MEDIA_INTERNAL.equals(explicit)) {
            // Card pulled after choosing MicroSD — fall open to Internal without rewriting pref.
            if (PRIMARY_MEDIA_MICROSD.equals(explicit) && !isMicroSdPresent()
                    && getInternalStorageRoot() != null) {
                return PRIMARY_MEDIA_INTERNAL;
            }
            return explicit;
        }
        if (prefs.contains(PREF_Y2_USE_INTERNAL_MEDIA)) {
            return prefs.getBoolean(PREF_Y2_USE_INTERNAL_MEDIA, false)
                    ? PRIMARY_MEDIA_INTERNAL : PRIMARY_MEDIA_MICROSD;
        }
        return isMicroSdPresent() ? PRIMARY_MEDIA_MICROSD : PRIMARY_MEDIA_INTERNAL;
    }

    /** Persist Primary storage choice from Settings (any family). */
    public static void setPrimaryMediaPref(Context ctx, String medium) {
        if (ctx == null) return;
        if (!PRIMARY_MEDIA_MICROSD.equals(medium) && !PRIMARY_MEDIA_INTERNAL.equals(medium)) return;
        android.content.SharedPreferences prefs =
                ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_Y2_PRIMARY_MEDIA, medium)
                .putBoolean(PREF_Y2_USE_INTERNAL_MEDIA, PRIMARY_MEDIA_INTERNAL.equals(medium))
                .commit();
    }

    /**
     * Preferred root for new user media (Reach, podcasts, covers, recordings).
     * 2026-07-15 — Honors pref; falls open to any healthy volume when the pick is missing.
     * 2026-07-20 — Phone chrome without a chosen Storage folder must not write to /sdcard0.
     * Y1/Y2/A5 unchanged (chrome inactive). Reversal: drop blocksStockStorageFallOpen check.
     */
    public static File getNewMediaRoot(Context ctx) {
        if (useInternalForNewMedia(ctx)) {
            File internal = getInternalStorageRoot();
            if (internal != null) return internal;
        } else {
            File micro = getMicroSdRoot();
            if (micro != null) return micro;
            File internal = getInternalStorageRoot();
            if (internal != null) return internal;
        }
        // Phone Solar: wait for onboarding instead of silently using stock sdcard0.
        try {
            if (com.solar.launcher.phone.PhoneStorageRoots.blocksStockStorageFallOpen(ctx)) {
                return null;
            }
        } catch (Throwable ignored) {}
        return getPrimaryStorageRoot();
    }

    /** Rockbox config lives on Internal on Y2 when present; else the only user volume. */
    public static File getRockboxRoot() {
        if (isY2()) {
            File internal = getInternalStorageRoot();
            if (internal != null) return internal;
        }
        return getPrimaryStorageRoot();
    }

    /** Reset → MicroSD wipe target — MicroSD when present, else primary fall-open. */
    public static File getMicroSdWipeRoot() {
        File micro = getMicroSdRoot();
        if (micro != null) return micro;
        return getPrimaryStorageRoot();
    }

    public static java.util.List<File> getMusicRoots() {
        return subdirRoots("Music");
    }

    /** 2026-07-15 — Audiobooks/ on each browsable volume. */
    public static java.util.List<File> getAudiobookRoots() {
        return subdirRoots("Audiobooks");
    }

    public static java.util.List<File> getPodcastRoots() {
        return subdirRoots("Podcasts");
    }

    public static java.util.List<File> getVideoRoots() {
        return subdirRoots("Videos");
    }

    public static java.util.List<File> getThemeRoots() {
        return subdirRoots("Themes");
    }

    public static java.util.List<File> getPhotoRoots() {
        java.util.List<File> list = new java.util.ArrayList<File>();
        for (File root : getStorageRoots()) {
            list.add(new File(root, "Pictures"));
            list.add(new File(root, "DCIM"));
        }
        return list;
    }

    public static java.util.List<File> getFmRecordingRoots() {
        return subdirRoots("FM Recordings");
    }

    private static java.util.List<File> subdirRoots(String subdir) {
        java.util.List<File> list = new java.util.ArrayList<File>();
        for (File root : getStorageRoots()) {
            list.add(new File(root, subdir));
        }
        return list;
    }

    public static void setCachedFamilyForTest(String family) {
        cachedFamily = family;
    }

    static void resetCacheForTest() {
        cachedFamily = null;
        testMicroSdPresentOverride = null;
    }

    /** Unit tests — simulate SD mount without /storage paths. */
    static void setMicroSdPresentForTest(boolean present) {
        testMicroSdPresentOverride = present;
    }

    static String resolvePrimaryMediaPrefForTest(String explicitPref, Boolean legacyInternal) {
        if (PRIMARY_MEDIA_MICROSD.equals(explicitPref) || PRIMARY_MEDIA_INTERNAL.equals(explicitPref)) {
            return explicitPref;
        }
        if (legacyInternal != null) {
            return legacyInternal ? PRIMARY_MEDIA_INTERNAL : PRIMARY_MEDIA_MICROSD;
        }
        return isMicroSdPresent() ? PRIMARY_MEDIA_MICROSD : PRIMARY_MEDIA_INTERNAL;
    }

    static String detectFamilyForTest(String cpuHardware, String boardHardware, int sdkInt, String model) {
        cachedFamily = null;
        return probeFamily(cpuHardware, boardHardware, sdkInt, model, "", 0, 0, "");
    }

    /** Unit / emulator — include manufacturer tokens (timmkoo, etc.). */
    static String detectFamilyForTest(String cpuHardware, String boardHardware, int sdkInt,
            String model, String manufacturer) {
        cachedFamily = null;
        return probeFamily(cpuHardware, boardHardware, sdkInt, model, manufacturer, 0, 0, "");
    }

    /**
     * 2026-07-15 — Include display pixels when model props lie (A5 reports as Y1).
     * @param displayW physical/logical width px (0 = unknown)
     * @param displayH physical/logical height px (0 = unknown)
     */
    static String detectFamilyForTest(String cpuHardware, String boardHardware, int sdkInt,
            String model, String manufacturer, int displayW, int displayH) {
        cachedFamily = null;
        return probeFamily(cpuHardware, boardHardware, sdkInt, model, manufacturer,
                displayW, displayH, "");
    }

    /** Unit — optional persist.solar.device_family pin after SoC / A5 gates. */
    static String detectFamilyForTest(String cpuHardware, String boardHardware, int sdkInt,
            String model, String manufacturer, int displayW, int displayH, String familyPin) {
        cachedFamily = null;
        return probeFamily(cpuHardware, boardHardware, sdkInt, model, manufacturer,
                displayW, displayH, familyPin);
    }

    private static String detectFamily() {
        if (cachedFamily != null) return cachedFamily;
        synchronized (DeviceFeatures.class) {
            if (cachedFamily != null) return cachedFamily;
            int[] px = readDisplayPixels();
            // 2026-07-16 — A5 QVGA is unique: force a5 even when stock props / pin say y1.
            // Do NOT short-circuit ~360×480 to y1: Y2 shares that panel; SoC + SDK disambiguate.
            if (looksLikeA5Display(px[0], px[1])) {
                cachedFamily = "a5";
                logDetected(cachedFamily, px);
                return cachedFamily;
            }
            String board = (Build.HARDWARE != null ? Build.HARDWARE : "")
                    + " " + (Build.BOARD != null ? Build.BOARD : "");
            String manu = (Build.MANUFACTURER != null ? Build.MANUFACTURER : "")
                    + " " + (Build.BRAND != null ? Build.BRAND : "")
                    + " " + (Build.PRODUCT != null ? Build.PRODUCT : "");
            String prop = readSystemProperty(PROP_DEVICE_FAMILY);
            cachedFamily = probeFamily(readProcCpuHardware(), board, Build.VERSION.SDK_INT,
                    Build.MODEL != null ? Build.MODEL : "", manu, px[0], px[1], prop);
            logDetected(cachedFamily, px);
            return cachedFamily;
        }
    }

    private static void logDetected(String family, int[] px) {
        try {
            Log.i(TAG, "detected device family: " + family
                    + " hw=" + Build.HARDWARE + " board=" + Build.BOARD
                    + " sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL
                    + " manu=" + Build.MANUFACTURER
                    + " display=" + (px != null ? px[0] + "x" + px[1] : "?"));
        } catch (Throwable ignored) {}
    }

    /**
     * 2026-07-16 — Family priority for Reach usernames / UI:
     * A5 panel → A5 tokens → SoC (MT6582/MT6572) → ROM pin → shared 360p via SDK
     * (4.4.x/SDK19 = Y2, 4.2.x/SDK17 = Y1) → model tokens → y1.
     * Was: early 360p short-circuit forced y1 and mislabeled Y2; A5 model=Y1 was OK via QVGA.
     */
    private static String probeFamily(String cpuHardware, String boardHardware, int sdkInt,
            String model, String manufacturer, int displayW, int displayH) {
        return probeFamily(cpuHardware, boardHardware, sdkInt, model, manufacturer,
                displayW, displayH, "");
    }

    private static String probeFamily(String cpuHardware, String boardHardware, int sdkInt,
            String model, String manufacturer, int displayW, int displayH, String familyPin) {
        String cpu = cpuHardware != null ? cpuHardware.toLowerCase(Locale.US) : "";
        String board = boardHardware != null ? boardHardware.toLowerCase(Locale.US) : "";
        String m = model != null ? model.toLowerCase(Locale.US) : "";
        String manu = manufacturer != null ? manufacturer.toLowerCase(Locale.US) : "";
        String pin = familyPin != null ? familyPin.toLowerCase(Locale.US).trim() : "";
        // Strongest: physical A5 panel (stock ROM still ships Y1 product props).
        if (looksLikeA5Display(displayW, displayH)) return "a5";
        // Explicit A5 model string (lab / future ROMs).
        if (looksLikeA5ModelOrManu(m, manu)) return "a5";
        // SoC before shared landscape panel — Y2 is MT6582, Y1 is MT6572.
        if (cpu.contains("mt6582") || board.contains("mt6582")) return "y2";
        if (cpu.contains("mt6572") || board.contains("mt6572")) return "y1";
        // ROM / emulator pin when SoC unknown (after A5 panel so stale y1 pin cannot own A5).
        if ("a5".equals(pin) || "y1".equals(pin) || "y2".equals(pin)) return pin;
        // Shared ~360×480: disambiguate by Android release when SoC/pin absent.
        // 360p + 4.4.x (SDK 19+) → Y2; 360p + 4.2.x (SDK ≤17) → Y1.
        if (looksLikeY1Display(displayW, displayH)) {
            if (sdkInt >= 19) return "y2";
            if (sdkInt <= 17) return "y1";
            // SDK 18 middle: model tokens, else Y1.
            if (m.contains("y2")) return "y2";
            if (m.contains("y1")) return "y1";
            return "y1";
        }
        // No reliable panel: SDK / model fallbacks (kitkat-class → y2).
        if (sdkInt >= 19) return "y2";
        if (sdkInt <= 16) return "y1";
        if (m.contains("y2")) return "y2";
        if (m.contains("y1")) return "y1";
        return "y1";
    }

    /**
     * 2026-07-15 — A5 QVGA portrait 240×320 (order-independent; ±20px for overscan).
     * Pure logic for unit tests and adb staging scripts.
     * 2026-07-20 — public for PhoneChromePolicy (other package).
     */
    public static boolean looksLikeA5Display(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return false;
        int a = Math.min(widthPx, heightPx);
        int b = Math.max(widthPx, heightPx);
        return a >= 220 && a <= 260 && b >= 300 && b <= 340;
    }

    /**
     * 2026-07-15 — Y1 360×480 (landscape 480×360 or portrait). ±20px.
     * 2026-07-20 — public for PhoneChromePolicy (other package).
     */
    public static boolean looksLikeY1Display(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return false;
        int a = Math.min(widthPx, heightPx);
        int b = Math.max(widthPx, heightPx);
        return a >= 340 && a <= 380 && b >= 460 && b <= 500;
    }

    /**
     * 2026-07-20 — Family pin for phone-chrome gate (empty when unset).
     * PhoneChromePolicy treats y1|y2|a5 as “no chrome” even on tall skins.
     * Reversal: delete; policy can only use display size.
     */
    public static String readDeviceFamilyPinForPhoneChrome() {
        return readSystemProperty(PROP_DEVICE_FAMILY);
    }

    /**
     * Model/brand tokens for A5 when display is unknown.
     * 2026-07-15 — Do not treat bare "Y1" or bare "Timmkoo" as A5 (Y1 is also Timmkoo).
     * Was: any timmkoo → a5; model y1 blocked a5 tokens forever.
     */
    private static boolean looksLikeA5ModelOrManu(String m, String manu) {
        if (m == null) m = "";
        if (manu == null) manu = "";
        // Explicit A5 in model/product string.
        if (m.contains("a5") || manu.contains("a5")) return true;
        // "Timmkoo A5" style — a5 already matched; avoid classifying plain "Y1" + Timmkoo.
        if ((manu.contains("timmkoo") || m.contains("timmkoo"))
                && !m.contains("y1") && !m.contains("y2")
                && m.length() > 0 && !m.equals("y1")) {
            // Brand alone is ambiguous — only when model is empty/generic lab.
            if (m.contains("generic") || m.length() == 0) return false;
        }
        return false;
    }

    /** @deprecated use {@link #looksLikeA5ModelOrManu} — kept for older tests if any. */
    private static boolean looksLikeA5(String m, String manu) {
        return looksLikeA5ModelOrManu(m, manu);
    }

    /** Read built-in display size; [0]=w [1]=h or 0,0 when unavailable (JVM tests). */
    private static int[] readDisplayPixels() {
        int[] out = new int[] { 0, 0 };
        try {
            android.content.res.Resources res = android.content.res.Resources.getSystem();
            if (res == null) return out;
            android.util.DisplayMetrics dm = res.getDisplayMetrics();
            if (dm == null) return out;
            out[0] = dm.widthPixels;
            out[1] = dm.heightPixels;
        } catch (Throwable ignored) {}
        return out;
    }

    private static String readSystemProperty(String key) {
        if (key == null || key.length() == 0) return "";
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v != null ? String.valueOf(v).trim().toLowerCase(Locale.US) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readProcCpuHardware() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase(Locale.US).startsWith("hardware")) {
                    int colon = trimmed.indexOf(':');
                    if (colon >= 0 && colon + 1 < trimmed.length()) {
                        return trimmed.substring(colon + 1).trim();
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {}
            }
        }
        return "";
    }
}
