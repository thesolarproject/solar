package com.solar.launcher.transfer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TransferJobStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void stateAndProgressSurviveProcessRestart() throws Exception {
        FakeClock clock = new FakeClock(1000L);
        File journal = new File(temporary.getRoot(), "jobs.json");
        TransferJobStore first = TransferJobStore.openForTest(journal, clock, false);
        TransferJobStore.Job created = first.create(
                TransferJobStore.Provider.PODCAST,
                "Episode",
                "Show",
                "https://example.test/episode.mp3",
                "/music/Episode.mp3",
                true,
                3);
        first.transition(created.id, TransferJobStore.State.CONNECTING, "Connecting", "");
        clock.advance(1000L);
        first.progress(created.id, 512L, 1024L);

        TransferJobStore reopened = TransferJobStore.openForTest(journal, clock, false);
        TransferJobStore.Job restored = reopened.get(created.id);
        assertNotNull(restored);
        assertEquals(TransferJobStore.State.DOWNLOADING, restored.state);
        assertEquals(512L, restored.doneBytes);
        assertEquals(1024L, restored.totalBytes);
        assertEquals(50, restored.percent());
    }

    @Test
    public void runningJobBecomesResumablePauseAfterCrash() throws Exception {
        FakeClock clock = new FakeClock(1000L);
        File journal = new File(temporary.getRoot(), "jobs.json");
        TransferJobStore first = TransferJobStore.openForTest(journal, clock, false);
        TransferJobStore.Job job = first.create(
                TransferJobStore.Provider.SOULSEEK, "Track", "peer", "folder\\track.mp3",
                "/music/track.mp3", true, 3);
        first.transition(job.id, TransferJobStore.State.CONNECTING, "Connecting", "");
        clock.advance(1000L);
        first.progress(job.id, 100L, 1000L);

        TransferJobStore restarted = TransferJobStore.openForTest(journal, clock, true);
        TransferJobStore.Job recovered = restarted.get(job.id);
        assertEquals(TransferJobStore.State.PAUSED, recovered.state);
        assertEquals(100L, recovered.doneBytes);
        assertTrue(recovered.detail.contains("restart"));
    }

    @Test
    public void invalidStateTransitionIsRejected() throws Exception {
        FakeClock clock = new FakeClock(1000L);
        TransferJobStore store = TransferJobStore.openForTest(
                new File(temporary.getRoot(), "jobs.json"), clock, false);
        TransferJobStore.Job job = store.create(
                TransferJobStore.Provider.DIRECT, "Track", "Creator", "https://x.test/a.mp3",
                "/music/a.mp3", true, 2);
        store.transition(job.id, TransferJobStore.State.CONNECTING, "Connecting", "");
        store.progress(job.id, 10L, 10L);
        store.transition(job.id, TransferJobStore.State.VERIFYING, "Verifying", "");
        store.transition(job.id, TransferJobStore.State.COMPLETED, "Complete", "");

        try {
            store.transition(job.id, TransferJobStore.State.DOWNLOADING, "Again", "");
            fail("terminal transition should fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Invalid"));
        }
    }

    @Test
    public void progressCalculatesSpeedEtaAndAggregate() throws Exception {
        FakeClock clock = new FakeClock(1000L);
        TransferJobStore store = TransferJobStore.openForTest(
                new File(temporary.getRoot(), "jobs.json"), clock, false);
        TransferJobStore.Job job = store.create(
                TransferJobStore.Provider.PODCAST, "Episode", "Show", "url", "target", true, 3);
        store.transition(job.id, TransferJobStore.State.CONNECTING, "Connecting", "");
        clock.advance(2000L);
        TransferJobStore.Job updated = store.progress(job.id, 2000L, 6000L);

        assertEquals(1000L, updated.speedBytesPerSecond);
        assertEquals(4L, updated.etaSeconds);
        TransferJobStore.Aggregate aggregate = store.aggregate();
        assertEquals(1, aggregate.activeCount);
        assertEquals(2000L, aggregate.doneBytes);
        assertEquals(6000L, aggregate.totalBytes);
    }

    @Test
    public void corruptPrimaryFallsBackToPreviousAtomicSnapshot() throws Exception {
        FakeClock clock = new FakeClock(1000L);
        File journal = new File(temporary.getRoot(), "jobs.json");
        TransferJobStore first = TransferJobStore.openForTest(journal, clock, false);
        TransferJobStore.Job job = first.create(
                TransferJobStore.Provider.IMPORT, "Owned file", "Local", "/usb/a.flac",
                "/music/a.flac", false, 1);
        first.transition(job.id, TransferJobStore.State.CONNECTING, "Importing", "");
        assertTrue(new File(journal.getParentFile(), journal.getName() + ".bak").isFile());

        FileOutputStream corrupt = new FileOutputStream(journal);
        corrupt.write("{broken".getBytes("UTF-8"));
        corrupt.close();

        TransferJobStore recovered = TransferJobStore.openForTest(journal, clock, false);
        TransferJobStore.Job fromBackup = recovered.get(job.id);
        assertNotNull(fromBackup);
        // Backup is the prior valid snapshot, before the CONNECTING transition.
        assertEquals(TransferJobStore.State.QUEUED, fromBackup.state);
    }

    @Test
    public void pausedAndFinishedJobsCanBeRemovedButRunningCannot() throws Exception {
        FakeClock clock = new FakeClock(1000L);
        TransferJobStore store = TransferJobStore.openForTest(
                new File(temporary.getRoot(), "jobs.json"), clock, false);
        TransferJobStore.Job job = store.create(
                TransferJobStore.Provider.CONVERSION, "Track", "Local", "input", "output",
                false, 1);
        store.transition(job.id, TransferJobStore.State.CONNECTING, "Starting", "");
        try {
            store.remove(job.id);
            fail("running job removed");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
        store.transition(job.id, TransferJobStore.State.PAUSED, "Paused", "");
        store.remove(job.id);
        assertTrue(store.list().isEmpty());
    }

    @Test
    public void percentDoesNotOverflowForLargeFiles() throws Exception {
        FakeClock clock = new FakeClock(1000L);
        TransferJobStore store = TransferJobStore.openForTest(
                new File(temporary.getRoot(), "jobs.json"), clock, false);
        TransferJobStore.Job job = store.create(
                TransferJobStore.Provider.DIRECT, "Large", "Creator", "remote", "target",
                true, 1);
        store.transition(job.id, TransferJobStore.State.CONNECTING, "Connecting", "");
        TransferJobStore.Job updated = store.progress(
                job.id, Long.MAX_VALUE - 10L, Long.MAX_VALUE);
        assertEquals(99, updated.percent());
    }

    private static final class FakeClock implements TransferJobStore.Clock {
        long now;

        FakeClock(long now) {
            this.now = now;
        }

        @Override public long nowMs() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }
}
