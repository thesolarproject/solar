package com.solar.launcher;

import com.solar.launcher.contracts.SolarFeature;
import com.solar.launcher.feature.apps.AppsFeature;
import com.solar.launcher.feature.home.HomeMenuConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/** Central registry of in-process feature modules. */
public final class FeatureRegistry {
    private final Map<String, SolarFeature> byId = new LinkedHashMap<String, SolarFeature>();
    private final AppsFeature appsFeature = new AppsFeature();

    public FeatureRegistry() {
        register(appsFeature);
    }

    public void register(SolarFeature feature) {
        if (feature == null || feature.id() == null) return;
        byId.put(feature.id(), feature);
    }

    public SolarFeature get(String id) {
        return byId.get(id);
    }

    public AppsFeature appsFeature() {
        return appsFeature;
    }

    /** Map home menu catalog id to feature module id when they differ. */
    public static String featureIdForHomeEntry(String homeId) {
        if (HomeMenuConfig.ID_PC_UPLOAD.equals(homeId)) {
            return com.solar.launcher.contracts.FeatureIds.WEBSERVER;
        }
        if (HomeMenuConfig.ID_SOULSEEK.equals(homeId)) {
            return com.solar.launcher.contracts.FeatureIds.REACH;
        }
        return homeId;
    }
}
