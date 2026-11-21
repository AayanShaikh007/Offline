package com.ayaanshaikh.offline;

import static org.junit.Assert.*;

import android.app.job.JobScheduler;
import android.content.Context;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class ForcedSyncRecoveryTest {
    private static final String VIDEO = "abcdefghijk";
    private final PlaylistLink link = new PlaylistLink("spotify", "1a2b3c4d5e6f7g8h9i0j12");
    private Context context;
    private OfflineStore store;
    private JobScheduler scheduler;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        var singleton = OfflineStore.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
        context.getSharedPreferences("sync", 0).edit().clear().commit();
        store = OfflineStore.get(context);
        addDeferredTrack();
        scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancelAll();
        assertFalse(SyncJob.wifi(context));
        // A disconnected Spotify source fails metadata lookup locally, before any HTTP request.
        assertFalse(new SpotifySource(context).connected());
    }

    private void addDeferredTrack() throws Exception {
        store.add(link);
        store.snapshot(link, new OfflineStore.Snapshot("Retry playlist", List.of(
                new OfflineStore.RemoteSong("track", "Deferred song", "Artist", 120, null))));
        var song = store.songs(link.key()).get(0);
        assertTrue(store.setMatch(song, VIDEO));
        store.failed(store.songs(link.key()).get(0), VIDEO, "retry queued", false);
        store.ensureAudioDir();
        Files.write(store.file(VIDEO + ".m4a").toPath(),
                new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 1, 2, 3, 4});
        assertTrue(store.queue(false).isEmpty());
        assertEquals(1, store.queue(true).size());
    }

    @After public void cleanup() {
        if (scheduler != null) scheduler.cancelAll();
        if (store != null) store.close();
    }

    private boolean runSync(Runnable check) throws Exception {
        var method = SyncJob.class.getDeclaredMethod("runSync", Context.class, Runnable.class);
        method.setAccessible(true);
        try {
            return (boolean) method.invoke(null, context, check);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error failure) throw failure;
            throw e;
        }
    }

    private void requestAndInterrupt() {
        SyncJob.request(context, true);
        assertThrows(Stopped.class, () -> runSync(() -> { throw new Stopped(); }));
    }

    @Test public void aRescheduledRunKeepsTheManualBackoffOverride() throws Exception {
        requestAndInterrupt();
        assertFalse(store.hasAudio(VIDEO));
        runSync(() -> {});
        assertTrue("The resumed manual sync must recover the deferred track", store.hasAudio(VIDEO));
        assertTrue(store.queue(true).isEmpty());
    }

    @Test public void interruptionAfterPlaylistLookupKeepsTheOverride() throws Exception {
        SyncJob.request(context, true);
        var checks = new AtomicInteger();
        assertThrows(Stopped.class, () -> runSync(() -> {
            if (checks.incrementAndGet() == 2) throw new Stopped();
        }));
        assertEquals(2, checks.get());
        runSync(() -> {});
        assertTrue(store.hasAudio(VIDEO));
    }

    @Test public void openingTheAppResumesAnInterruptedRequestEvenForARecentPlaylist() {
        requestAndInterrupt();
        scheduler.cancelAll();
        SyncJob.onOpen(context);
        assertTrue("An interrupted manual request should enqueue deferred work",
                scheduler.getAllPendingJobs().stream().anyMatch(job -> !job.isPeriodic()));
    }

    @Test public void repeatedInterruptionsDoNotConsumeTheRequest() throws Exception {
        requestAndInterrupt();
        assertThrows(Stopped.class, () -> runSync(() -> { throw new Stopped(); }));
        runSync(() -> {});
        assertTrue(store.hasAudio(VIDEO));
    }

    @Test public void aCompletedBatchDoesNotLeaveAForcedRetryLoop() throws Exception {
        SyncJob.request(context, true);
        runSync(() -> {});
        assertTrue(store.hasAudio(VIDEO));
        scheduler.cancelAll();
        SyncJob.onOpen(context);
        assertTrue(scheduler.getAllPendingJobs().stream().noneMatch(job -> !job.isPeriodic()));
    }

    @Test public void aNewManualRequestDuringTheRunIsNotAcknowledgedByTheOldRun() throws Exception {
        SyncJob.request(context, true);
        var once = new AtomicBoolean();
        runSync(() -> {
            if (once.compareAndSet(false, true)) SyncJob.request(context, true);
        });
        assertTrue(store.hasAudio(VIDEO));
        scheduler.cancelAll();
        SyncJob.onOpen(context);
        assertTrue(scheduler.getAllPendingJobs().stream().anyMatch(job -> !job.isPeriodic()));
    }

    @Test public void removingAllSourcesClearsAnInterruptedRequest() throws Exception {
        requestAndInterrupt();
        store.remove(link);
        SyncJob.cancelIfEmpty(context);
        addDeferredTrack();
        scheduler.cancelAll();
        SyncJob.onOpen(context);
        assertTrue(scheduler.getAllPendingJobs().stream().noneMatch(job -> !job.isPeriodic()));
    }

    @Test public void interruptingAnAutomaticRunDoesNotPromoteItToManual() throws Exception {
        assertThrows(Stopped.class, () -> runSync(() -> { throw new Stopped(); }));
        runSync(() -> {});
        assertFalse(store.hasAudio(VIDEO));
        assertTrue(store.queue(false).isEmpty());
        assertEquals(1, store.queue(true).size());
    }

    private static final class Stopped extends RuntimeException {}
}
