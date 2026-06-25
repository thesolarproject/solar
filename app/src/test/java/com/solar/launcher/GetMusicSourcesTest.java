package com.solar.launcher;

import com.solar.launcher.soulseek.SoulseekAccount;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** ponytail: static gating rules — no Robolectric needed. */
public class GetMusicSourcesTest {

    @After
    public void tearDown() {
        ConnectivityHelper.setReachPeerOk(true);
        ConnectivityHelper.setDeezerLoginOk(false);
    }

    @Test
    public void deezerSearchFollowsServiceFlagOnly() {
        assertTrue(GetMusicSources.deezerSearchInGetMusic(null, true));
        assertFalse(GetMusicSources.deezerSearchInGetMusic(null, false));
    }

    @Test
    public void reachSearchNeedsPeerOk() {
        ConnectivityHelper.setReachPeerOk(true);
        ConnectivityHelper.setDeezerLoginOk(false);
        int sub = GetMusicSources.activeSourceSubtitle(
                null, true, false);
        assertEquals(GetMusicSources.SUBTITLE_SOULSEEK, sub);
    }

    @Test
    public void activeSourceSubtitleDeezerOnlyWhenReachOff() {
        ConnectivityHelper.setReachPeerOk(false);
        int sub = GetMusicSources.activeSourceSubtitle(null, true, true);
        assertEquals(GetMusicSources.SUBTITLE_DEEZER, sub);
    }

    @Test
    public void soulseekEnabledPrefKey() {
        assertEquals("soulseek_enabled", SoulseekAccount.PREF_SOULSEEK_ENABLED);
    }
}
