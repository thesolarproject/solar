package com.solar.launcher.overlay;

import org.junit.After;
import org.junit.Test;
import android.os.SystemProperties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-07-14 — Solar ThemedContextMenu is sole shell by default (matches Xposed forwarder).
 * Host unit tests: SystemProperties missing → readProp returns defaults in OverlayShellRouter.
 */
public class OverlayShellRouterTest {

    @After
    public void tearDown() {
        SystemProperties.clear();
    }

    @Test
    public void defaultUsesSolarThemedShell() {
        // android.os.SystemProperties stub is present, but empty → companion_shell default 0 → Solar.
        assertFalse(OverlayShellRouter.useCompanionShell());
        assertEquals(OverlayShellRouter.SOLAR_PKG, OverlayShellRouter.overlayPackage());
        assertEquals(OverlayShellRouter.SOLAR_OVERLAY_SERVICE,
                OverlayShellRouter.overlayServiceClass());
        assertTrue(OverlayShellRouter.overlayComponent() != null);
    }

    @Test
    public void companionPackageConstantsStable() {
        assertFalse(OverlayShellRouter.SOLAR_PKG.equals(OverlayShellRouter.COMPANION_PKG));
        assertTrue(OverlayShellRouter.COMPANION_OVERLAY_SERVICE
                .startsWith(OverlayShellRouter.COMPANION_PKG));
        assertTrue(OverlayShellRouter.SOLAR_OVERLAY_SERVICE
                .startsWith(OverlayShellRouter.SOLAR_PKG));
    }

    @Test
    public void peerIsOtherShellWhenSolarPrimary() {
        // Default Solar → peer is companion Chip (so dismissPeer cannot hit Solar).
        assertEquals(OverlayShellRouter.COMPANION_PKG, OverlayShellRouter.peerOverlayPackage());
        assertEquals(OverlayShellRouter.COMPANION_OVERLAY_SERVICE,
                OverlayShellRouter.peerOverlayServiceClass());
        assertFalse(OverlayShellRouter.peerOverlayPackage()
                .equals(OverlayShellRouter.overlayPackage()));
    }

    @Test
    public void useCompanionShell_enabled_returnsTrue() {
        SystemProperties.set(OverlayShellRouter.COMPANION_SHELL_PROP, "1");
        assertTrue(OverlayShellRouter.useCompanionShell());
    }

    @Test
    public void useCompanionShell_enabledButLegacyForced_returnsFalse() {
        SystemProperties.set(OverlayShellRouter.COMPANION_SHELL_PROP, "1");
        SystemProperties.set(OverlayShellRouter.LEGACY_SHELL_PROP, "1");
        assertFalse(OverlayShellRouter.useCompanionShell());
    }

    @Test
    public void useCompanionShell_disabledButLegacyForced_returnsFalse() {
        SystemProperties.set(OverlayShellRouter.COMPANION_SHELL_PROP, "0");
        SystemProperties.set(OverlayShellRouter.LEGACY_SHELL_PROP, "1");
        assertFalse(OverlayShellRouter.useCompanionShell());
    }
}
