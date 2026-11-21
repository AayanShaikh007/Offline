package com.ayaanshaikh.offline;

import static org.junit.Assert.*;

import android.app.job.JobScheduler;
import android.content.Context;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class ForcedSyncBatchTest {
    private final PlaylistLink link = new PlaylistLink("spotify", "1a2b3c4d5e6f7g8h9i0j12");
    private Context context;
    private OfflineStore store;
    private JobScheduler scheduler;
    private List<OfflineStore.RemoteSong> tracks;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        resetSingleton();
        context.getSharedPreferences("sync", 0).edit().clear().commit();
        store = OfflineStore.get(context);
        scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancelAll();
        assertFalse(SyncJob.wifi(context));
        assertFalse(new SpotifySource(context).connected());
    }

    @After public void cleanup() {
        if (scheduler != null) scheduler.cancelAll();
        if (store != null) store.close();
    }

    private void resetSingleton() throws Exception {
        var field = OfflineStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    private void addTracks(int count) throws Exception {
        store.add(link);
        tracks = new ArrayList<>();
        for (int i = 0; i < count; i++) tracks.add(new OfflineStore.RemoteSong(
                "track-" + i, "Song " + i, "Artist", 120,
                String.format(java.util.Locale.ROOT, "v%010d", i)));
        store.snapshot(link, new OfflineStore.Snapshot("Batch playlist", tracks));
        store.ensureAudioDir();
        for (var song : store.songs(link.key())) {
            store.failed(song, song.video(), "retry queued", false);
            Files.write(store.file(song.video() + ".m4a").toPath(),
                    new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 1, 2, 3, 4});
        }
        assertTrue(store.queue(false).isEmpty());
        assertEquals(count, store.queue(true).size());
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

    @Test public void manualRetriesContinueBeyondThirtyTracks() throws Exception {
        addTracks(31);
        SyncJob.request(context, true);
        assertTrue("The remaining deferred track must schedule continuation", runSync(() -> {}));
        assertEquals(30, store.library().size());
        assertEquals(1, store.queue(false).size());
        assertFalse(runSync(() -> {}));
        assertEquals(31, store.library().size());
        assertTrue(store.queue(true).isEmpty());
    }

    @Test public void aTimeBudgetCutoffLeavesRemainingRetriesEligible() throws Exception {
        addTracks(3);
        SyncJob.request(context, true);
        var advanced = new AtomicBoolean();
        assertTrue(runSync(() -> {
            if (store.library().size() == 1 && advanced.compareAndSet(false, true)) {
                ShadowSystemClock.advanceBy(Duration.ofMinutes(6));
            }
        }));
        assertTrue(advanced.get());
        assertEquals(1, store.library().size());
        assertEquals(2, store.queue(false).size());
        assertFalse(runSync(() -> {}));
        assertEquals(3, store.library().size());
    }

    @Test public void aLargeRetryQueueCompletesAcrossThreeBoundedBatches() throws Exception {
        addTracks(61);
        SyncJob.request(context, true);
        assertTrue(runSync(() -> {}));
        assertEquals(30, store.library().size());
        assertTrue(runSync(() -> {}));
        assertEquals(60, store.library().size());
        assertFalse(runSync(() -> {}));
        assertEquals(61, store.library().size());
    }

    @Test public void resumingDoesNotReleaseBackoffForAnEarlierFailureAgain() throws Exception {
        addTracks(2);
        SyncJob.request(context, true);
        var failed = store.songs(link.key()).get(0);
        assertThrows(Stopped.class, () -> runSync(() -> {
            store.failed(store.songs(link.key()).get(0), failed.video(), "retry queued", false);
            throw new Stopped();
        }));
        long retryAt = store.songs(link.key()).get(0).retryAt();
        assertFalse(runSync(() -> {}));
        assertFalse("A failed attempt must keep its newly assigned backoff", store.hasAudio(failed.video()));
        assertEquals(1, store.library().size());
        assertEquals(retryAt, store.songs(link.key()).get(0).retryAt());
        assertEquals(2, store.songs(link.key()).get(0).attempts());
        assertTrue(store.queue(false).isEmpty());
    }

    @Test public void aNewExplicitRequestCanRetryAnEarlierFailure() throws Exception {
        addTracks(2);
        SyncJob.request(context, true);
        var failed = store.songs(link.key()).get(0);
        assertThrows(Stopped.class, () -> runSync(() -> {
            store.failed(store.songs(link.key()).get(0), failed.video(), "retry queued", false);
            throw new Stopped();
        }));
        SyncJob.request(context, true);
        assertFalse(runSync(() -> {}));
        assertEquals(2, store.library().size());
        assertTrue(store.hasAudio(failed.video()));
    }

    @Test public void manualPreparationPreservesUnmatchedAndInactiveSongs() throws Exception {
        addTracks(2);
        var waiting = new OfflineStore.RemoteSong("needs-match", "Unmatched", "Artist", 120, null);
        store.snapshot(link, new OfflineStore.Snapshot("Batch playlist", List.of(tracks.get(0), waiting)));
        store.failed(store.songs(link.key()).get(1), null, "match needed", true);
        SyncJob.request(context, true);
        assertFalse(runSync(() -> {}));
        assertEquals(1, store.library().size());
        var unmatched = store.songs(link.key()).get(1);
        assertEquals("match needed", unmatched.status());
        assertEquals(Long.MAX_VALUE, unmatched.retryAt());
        store.snapshot(link, new OfflineStore.Snapshot("Restored", List.of(tracks.get(0), tracks.get(1), waiting)));
        assertTrue(store.queue(false).isEmpty());
        assertEquals(1, store.queue(true).size());
    }

    @Test public void anOlderUnpreparedInFlightRequestIsStillHonored() throws Exception {
        addTracks(2);
        context.getSharedPreferences("sync", 0).edit().putBoolean("forceInFlight", true).commit();
        assertFalse(runSync(() -> {}));
        assertEquals(2, store.library().size());
    }

    @Test public void continuationEligibilitySurvivesAStoreReopen() throws Exception {
        addTracks(31);
        SyncJob.request(context, true);
        runSync(() -> {});
        store.close();
        resetSingleton();
        store = OfflineStore.get(context);
        assertEquals(1, store.queue(false).size());
        assertFalse(runSync(() -> {}));
        assertEquals(31, store.library().size());
    }

    private static final class Stopped extends RuntimeException {}
}
