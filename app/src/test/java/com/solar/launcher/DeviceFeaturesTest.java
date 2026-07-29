package com.solar.launcher;

import org.junit.Test;

import java.io.File;
import java.util.List;

public class DeviceFeaturesTest {

    @Test
    public void mt6582MapsToY2() {
        String family = DeviceFeatures.detectFamilyForTest("MT6582", "mt6582", 17, "Y1");
        if (!"y2".equals(family)) throw new AssertionError("expected y2 got " + family);
    }

    @Test
    public void mt6582BeatsY1DisplaySize() {
        // 2026-07-16 — Y2 shares ~360×480 with Y1; SoC must win (solar-diag family=y1 bug).
        String family = DeviceFeatures.detectFamilyForTest(
                "MT6582", "mt6582", 19, "Y2", "Y2", 360, 480);
        if (!"y2".equals(family)) throw new AssertionError("expected y2 got " + family);
    }

    @Test
    public void shared360pSdkDisambiguatesY1VsY2() {
        // 360p + 4.2.x (SDK 17) → Y1; 360p + 4.4.x (SDK 19) → Y2 when SoC unknown.
        String y1 = DeviceFeatures.detectFamilyForTest("", "", 17, "", "", 360, 480);
        if (!"y1".equals(y1)) throw new AssertionError("360p sdk17 → y1 got " + y1);
        String y2 = DeviceFeatures.detectFamilyForTest("", "", 19, "", "", 360, 480);
        if (!"y2".equals(y2)) throw new AssertionError("360p sdk19 → y2 got " + y2);
    }

    @Test
    public void a5QvgaBeatsModelY1() {
        String family = DeviceFeatures.detectFamilyForTest(
                "", "", 17, "Y1", "Timmkoo", 240, 320);
        if (!"a5".equals(family)) throw new AssertionError("expected a5 got " + family);
    }

    @Test
    public void socBeatsStaleFamilyPin() {
        // MT6582 must win even if pin or model says y1.
        String family = DeviceFeatures.detectFamilyForTest(
                "MT6582", "mt6582", 19, "Y1", "", 360, 480, "y1");
        if (!"y2".equals(family)) throw new AssertionError("soc beats pin got " + family);
    }

    @Test
    public void pinUsedWhenSocAndPanelAmbiguous() {
        String family = DeviceFeatures.detectFamilyForTest(
                "", "", 18, "", "", 0, 0, "y2");
        if (!"y2".equals(family)) throw new AssertionError("pin y2 got " + family);
    }

    @Test
    public void mt6572MapsToY1() {
        String family = DeviceFeatures.detectFamilyForTest("MT6572", "mt6572", 19, "Y2");
        if (!"y1".equals(family)) throw new AssertionError("expected y1 got " + family);
    }

    @Test
    public void sdkFallbackY2() {
        String family = DeviceFeatures.detectFamilyForTest("", "", 19, "");
        if (!"y2".equals(family)) throw new AssertionError("expected y2 got " + family);
    }

    @Test
    public void modelFallbackY1() {
        String family = DeviceFeatures.detectFamilyForTest("", "", 17, "Innioasis Y1");
        if (!"y1".equals(family)) throw new AssertionError("expected y1 got " + family);
    }

    @Test
    public void y1HasRootAccess() {
        DeviceFeatures.setCachedFamilyForTest("y1");
        if (!DeviceFeatures.hasRootAccess()) throw new AssertionError("expected Y1 to have root access");
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void y2HasRootAccess() {
        DeviceFeatures.setCachedFamilyForTest("y2");
        if (!DeviceFeatures.hasRootAccess()) throw new AssertionError("expected Y2 to have root access");
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void y2PrimaryAndSecondaryPaths() {
        DeviceFeatures.setCachedFamilyForTest("y2");
        if (!"/storage/sdcard1".equals(DeviceFeatures.primaryStoragePath())) {
            throw new AssertionError("y2 primary path");
        }
        if (!"/storage/sdcard0".equals(DeviceFeatures.secondaryStoragePath())) {
            throw new AssertionError("y2 secondary path");
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void y1SingleStoragePath() {
        DeviceFeatures.setCachedFamilyForTest("y1");
        if (!"/storage/sdcard0".equals(DeviceFeatures.primaryStoragePath())) {
            throw new AssertionError("y1 primary path");
        }
        if (DeviceFeatures.secondaryStoragePath() != null) {
            throw new AssertionError("y1 should have no secondary path");
        }
        java.util.List<String> ums = DeviceFeatures.getUmsExportVolumePaths();
        if (ums.size() != 1 || !"/storage/sdcard0".equals(ums.get(0))) {
            throw new AssertionError("y1 ums export path");
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void productModelLabelMatchesDeviceModelLabel() {
        DeviceFeatures.setCachedFamilyForTest("y1");
        if (!"Y1".equals(DeviceFeatures.productModelLabel())) {
            throw new AssertionError("expected Y1 product label");
        }
        if (!DeviceFeatures.deviceModelLabel().equals(DeviceFeatures.productModelLabel())) {
            throw new AssertionError("productModelLabel must match deviceModelLabel");
        }
        DeviceFeatures.setCachedFamilyForTest("y2");
        if (!"Y2".equals(DeviceFeatures.productModelLabel())) {
            throw new AssertionError("expected Y2 product label");
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void y2UmsExportVolumesInternalThenMicroSd() {
        DeviceFeatures.setCachedFamilyForTest("y2");
        java.util.List<String> ums = DeviceFeatures.getUmsExportVolumePaths();
        if (ums.size() != 2) {
            throw new AssertionError("y2 ums export should be internal + microSD");
        }
        if (!"/storage/sdcard0".equals(ums.get(0)) || !"/storage/sdcard1".equals(ums.get(1))) {
            throw new AssertionError("y2 ums export order");
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void musicRootsSingleVolumeOnY1() {
        DeviceFeatures.setCachedFamilyForTest("y1");
        List<File> roots = DeviceFeatures.getMusicRoots();
        if (roots.size() != 1) throw new AssertionError("expected 1 music root on Y1 got " + roots.size());
        if (!portablePath(roots.get(0)).endsWith("/Music")) {
            throw new AssertionError("music subdir");
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void scanRootListsIncludeExpectedSubdirs() {
        DeviceFeatures.setCachedFamilyForTest("y2");
        List<File> video = DeviceFeatures.getVideoRoots();
        List<File> fm = DeviceFeatures.getFmRecordingRoots();
        if (video.isEmpty() || fm.isEmpty()) throw new AssertionError("empty root lists");
        if (!video.get(0).getName().equals("Videos")) throw new AssertionError("Videos");
        if (!fm.get(0).getName().equals("FM Recordings")) throw new AssertionError("FM");
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void rockboxRootFollowsDevicePolicy() {
        DeviceFeatures.setCachedFamilyForTest("y1");
        if (!portablePath(DeviceFeatures.getRockboxRoot()).endsWith("/storage/sdcard0")) {
            throw new AssertionError("y1 rockbox on user sd");
        }
        DeviceFeatures.setCachedFamilyForTest("y2");
        if (!portablePath(DeviceFeatures.getRockboxRoot()).contains("/storage/sdcard")) {
            throw new AssertionError("y2 rockbox path");
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void y2PrimaryMediaDefaultMicrosdWhenCardPresent() {
        DeviceFeatures.setMicroSdPresentForTest(true);
        String medium = DeviceFeatures.resolvePrimaryMediaPrefForTest(null, null);
        if (!DeviceFeatures.PRIMARY_MEDIA_MICROSD.equals(medium)) {
            throw new AssertionError("expected microsd default got " + medium);
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void y2PrimaryMediaDefaultInternalWhenNoCard() {
        DeviceFeatures.setMicroSdPresentForTest(false);
        String medium = DeviceFeatures.resolvePrimaryMediaPrefForTest(null, null);
        if (!DeviceFeatures.PRIMARY_MEDIA_INTERNAL.equals(medium)) {
            throw new AssertionError("expected internal default got " + medium);
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void y2PrimaryMediaExplicitOverridesDefault() {
        DeviceFeatures.setMicroSdPresentForTest(true);
        String medium = DeviceFeatures.resolvePrimaryMediaPrefForTest(
                DeviceFeatures.PRIMARY_MEDIA_INTERNAL, null);
        if (!DeviceFeatures.PRIMARY_MEDIA_INTERNAL.equals(medium)) {
            throw new AssertionError("explicit pref");
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void y2PrimaryMediaLegacyBooleanMigration() {
        DeviceFeatures.setMicroSdPresentForTest(true);
        String medium = DeviceFeatures.resolvePrimaryMediaPrefForTest(null, Boolean.TRUE);
        if (!DeviceFeatures.PRIMARY_MEDIA_INTERNAL.equals(medium)) {
            throw new AssertionError("legacy true -> internal");
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void y2PrimaryMediaConstants() {
        if (!"microsd".equals(DeviceFeatures.PRIMARY_MEDIA_MICROSD)) {
            throw new AssertionError("microsd constant");
        }
        if (!"internal".equals(DeviceFeatures.PRIMARY_MEDIA_INTERNAL)) {
            throw new AssertionError("internal constant");
        }
    }

    @Test
    public void a5FamilyExclusive() {
        DeviceFeatures.setCachedFamilyForTest("a5");
        if (!DeviceFeatures.isA5() || DeviceFeatures.isY1() || DeviceFeatures.isY2()) {
            throw new AssertionError("a5 exclusive");
        }
        DeviceFeatures.resetCacheForTest();
    }

    @Test
    public void a5DetectionFromModel() {
        String family = DeviceFeatures.detectFamilyForTest("unknown", "unknown", 17, "Timmkoo A5", "Timmkoo");
        if (!"a5".equals(family)) throw new AssertionError("expected a5 got " + family);
    }

    @Test
    public void a5DisplayBeatsLyingY1Model() {
        // 2026-07-15 — Stock A5 ROM reports model=Y1 + mt6572; panel is 240×320.
        String family = DeviceFeatures.detectFamilyForTest(
                "MT6572", "mt6572", 17, "Y1", "Timmkoo", 240, 320);
        if (!"a5".equals(family)) throw new AssertionError("expected a5 got " + family);
    }

    @Test
    public void y1DisplayBeatsTimmkooBrand() {
        // Same OEM brand as A5, but landscape 480×360 is real Y1.
        String family = DeviceFeatures.detectFamilyForTest(
                "MT6572", "mt6572", 17, "Y1", "Timmkoo", 480, 360);
        if (!"y1".equals(family)) throw new AssertionError("expected y1 got " + family);
    }

    @Test
    public void a5DisplayHelpers() {
        if (!DeviceFeatures.looksLikeA5Display(240, 320)) {
            throw new AssertionError("240x320");
        }
        if (!DeviceFeatures.looksLikeA5Display(320, 240)) {
            throw new AssertionError("320x240 rotated");
        }
        if (DeviceFeatures.looksLikeA5Display(480, 360)) {
            throw new AssertionError("Y1 must not look like A5");
        }
        if (!DeviceFeatures.looksLikeY1Display(480, 360)) {
            throw new AssertionError("480x360 Y1");
        }
    }

    @Test
    public void a5DisplayBeatsY1FamilyPropInProbe() {
        // probeFamily does not read sysprops — display path must still win for model=Y1.
        String family = DeviceFeatures.detectFamilyForTest(
                "MT6572", "mt6572", 17, "Y1", "Timmkoo", 240, 320);
        if (!"a5".equals(family)) throw new AssertionError("display must beat lying model got " + family);
    }

    private static String portablePath(File file) {
        return file.getAbsolutePath().replace('\\', '/');
    }
}
