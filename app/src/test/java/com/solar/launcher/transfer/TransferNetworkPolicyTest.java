package com.solar.launcher.transfer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class TransferNetworkPolicyTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void offlineAlwaysPauses() {
        assertTrue(TransferNetworkPolicy.shouldPause(false, false, false));
        assertTrue(TransferNetworkPolicy.shouldPause(true, false, true));
    }

    @Test
    public void wifiOnlyDoesNotUseAnotherConnectedRoute() {
        assertTrue(TransferNetworkPolicy.shouldPause(true, true, false));
        assertFalse(TransferNetworkPolicy.shouldPause(true, true, true));
    }

    @Test
    public void nonWifiJobCanUseAnotherInternetRoute() {
        assertFalse(TransferNetworkPolicy.shouldPause(false, true, false));
    }

    @Test
    public void autoResumeRequiresPreferenceAndEligibleNetwork() {
        assertFalse(TransferNetworkPolicy.shouldAutoResume(false, true, true, true));
        assertFalse(TransferNetworkPolicy.shouldAutoResume(true, true, true, false));
        assertFalse(TransferNetworkPolicy.shouldAutoResume(true, true, false, true));
        assertTrue(TransferNetworkPolicy.shouldAutoResume(true, true, true, true));
    }

    @Test
    public void onlyRecoveredDirectHttpJobIsArmedAfterRestart() throws Exception {
        MutableClock clock = new MutableClock();
        File journal = new File(temporary.getRoot(), "jobs.json");
        TransferJobStore before = TransferJobStore.openForTest(journal, clock, false);
        TransferJobStore.Job direct = before.create(
                TransferJobStore.Provider.DIRECT,
                "Track",
                "Creator",
                "https://creator.example/track.mp3",
                "/music/track.mp3",
                true,
                3);
        before.transition(direct.id, TransferJobStore.State.CONNECTING, "Connecting", "");

        TransferJobStore after = TransferJobStore.openForTest(journal, clock, true);
        assertTrue(TransferNetworkPolicy.isSafeRestartAutoResumeCandidate(
                after.get(direct.id)));

        TransferJobStore.Job podcast = after.create(
                TransferJobStore.Provider.PODCAST,
                "Episode",
                "Show",
                "https://podcast.example/episode.mp3",
                "/podcasts/episode.mp3",
                true,
                3);
        after.transition(podcast.id, TransferJobStore.State.PAUSED,
                TransferJobStore.DETAIL_PAUSED_AFTER_RESTART, "");
        assertFalse(TransferNetworkPolicy.isSafeRestartAutoResumeCandidate(
                after.get(podcast.id)));
    }

    @Test
    public void manualPauseIsNeverArmedAsCrashRecovery() throws Exception {
        MutableClock clock = new MutableClock();
        TransferJobStore store = TransferJobStore.openForTest(
                new File(temporary.getRoot(), "manual.json"), clock, false);
        TransferJobStore.Job direct = store.create(
                TransferJobStore.Provider.DIRECT,
                "Track",
                "Creator",
                "https://creator.example/track.mp3",
                "/music/track.mp3",
                true,
                3);
        store.transition(direct.id, TransferJobStore.State.PAUSED, "Paused", "");
        assertFalse(TransferNetworkPolicy.isSafeRestartAutoResumeCandidate(
                store.get(direct.id)));
    }

    private static final class MutableClock implements TransferJobStore.Clock {
        private long now = 1L;

        @Override
        public long nowMs() {
            return now++;
        }
    }
}
