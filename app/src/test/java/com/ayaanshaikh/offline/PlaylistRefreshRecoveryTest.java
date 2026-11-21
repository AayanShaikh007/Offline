package com.ayaanshaikh.offline;

import static org.junit.Assert.*;

import android.app.job.JobScheduler;
import android.content.Context;
import java.lang.reflect.InvocationTargetException;
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
public class PlaylistRefreshRecoveryTest {
    private Context context;
    private OfflineStore store;
    private JobScheduler scheduler;
    private final List<PlaylistLink> links = new ArrayList<>();

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        var field = OfflineStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
        context.getSharedPreferences("sync", 0).edit().clear().commit();
        store = OfflineStore.get(context);
        scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancelAll();
        for (int i = 0; i < 3; i++) {
            var link = link(i);
            links.add(link);
            store.add(link);
            store.snapshot(link, new OfflineStore.Snapshot("Playlist " + i, List.of()));
        }
        assertFalse(SyncJob.wifi(context));
        // Metadata attempts fail locally before HTTP, allowing control-flow tests without credentials.
        assertFalse(new SpotifySource(context).connected());
    }

    @After public void cleanup() {
        if (scheduler != null) scheduler.cancelAll();
        if (store != null) store.close();
    }

    private PlaylistLink link(int i) {
        return new PlaylistLink("spotify", String.format(java.util.Locale.ROOT, "%022d", i));
    }

    private OfflineStore.Playlist playlist(PlaylistLink link) {
        return store.playlists().stream().filter(p -> p.link().equals(link)).findFirst().orElseThrow();
    }

    private long attemptedCount() {
        return store.playlists().stream().filter(p -> p.status().startsWith("Connect Spotify")).count();
    }

    private long recentCheckMarker(PlaylistLink link) {
        long marker = System.currentTimeMillis() - 60_000;
        store.getWritableDatabase().execSQL("UPDATE playlists SET checked=? WHERE key=?",
                new Object[]{marker, link.key()});
        return marker;
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

    private boolean onePlaylistBudget() throws Exception {
        long before = attemptedCount();
        var advanced = new AtomicBoolean();
        return runSync(() -> {
            if (attemptedCount() == before + 1 && advanced.compareAndSet(false, true)) {
                ShadowSystemClock.advanceBy(Duration.ofMinutes(6));
            }
        });
    }

    @Test public void manualRefreshContinuesWithoutRepeatingTheVisitedPlaylist() throws Exception {
        SyncJob.request(context, true);
        boolean more = onePlaylistBudget();
        assertEquals(1, attemptedCount());
        assertTrue("Unvisited refreshes must request another batch", more);
        long firstCheck = recentCheckMarker(links.get(0));
        assertFalse(runSync(() -> {}));
        assertEquals(3, attemptedCount());
        assertEquals(firstCheck, playlist(links.get(0)).checked());
    }

    @Test public void repeatedBudgetCutsMakeProgressThroughTheRefreshQueue() throws Exception {
        SyncJob.request(context, true);
        assertTrue(onePlaylistBudget());
        assertEquals(1, attemptedCount());
        assertTrue(onePlaylistBudget());
        assertEquals(2, attemptedCount());
        assertFalse(onePlaylistBudget());
        assertEquals(3, attemptedCount());
    }

    @Test public void interruptionRetainsOnlyTheUnvisitedRefreshes() throws Exception {
        SyncJob.request(context, true);
        assertThrows(Stopped.class, () -> runSync(() -> {
            if (attemptedCount() == 1) {
                ShadowSystemClock.advanceBy(Duration.ofMinutes(1));
                throw new Stopped();
            }
        }));
        long firstCheck = recentCheckMarker(links.get(0));
        assertFalse(runSync(() -> {}));
        assertEquals(3, attemptedCount());
        assertEquals("A completed lookup should not be repeated on resume",
                firstCheck, playlist(links.get(0)).checked());
    }

    @Test public void aNewExplicitRequestIncludesPreviouslyVisitedPlaylists() throws Exception {
        SyncJob.request(context, true);
        onePlaylistBudget();
        long firstCheck = recentCheckMarker(links.get(0));
        SyncJob.request(context, true);
        assertFalse(runSync(() -> {}));
        assertEquals(3, attemptedCount());
        assertTrue(playlist(links.get(0)).checked() > firstCheck);
    }

    @Test public void removingAnUnvisitedPlaylistDoesNotLeaveRefreshWorkStuck() throws Exception {
        SyncJob.request(context, true);
        onePlaylistBudget();
        long firstCheck = recentCheckMarker(links.get(0));
        store.remove(links.get(1));
        SyncJob.cancelIfEmpty(context);
        assertFalse(runSync(() -> {}));
        assertEquals(2, attemptedCount());
        assertEquals(firstCheck, playlist(links.get(0)).checked());
    }

    @Test public void olderInFlightStateWithoutARefreshQueueIsStillHonored() throws Exception {
        context.getSharedPreferences("sync", 0).edit()
                .putBoolean("forceInFlight", true).putBoolean("forcePrepared", true).commit();
        assertFalse(runSync(() -> {}));
        assertEquals(3, attemptedCount());
    }

    @Test public void automaticDueRefreshesAlsoContinueAfterTheBudget() throws Exception {
        store.getWritableDatabase().execSQL("UPDATE playlists SET checked=?",
                new Object[]{System.currentTimeMillis() - SyncJob.HOUR});
        boolean more = onePlaylistBudget();
        assertEquals(1, attemptedCount());
        assertTrue(more);
        long firstCheck = recentCheckMarker(links.get(0));
        assertFalse(runSync(() -> {}));
        assertEquals(3, attemptedCount());
        assertEquals(firstCheck, playlist(links.get(0)).checked());
    }

    @Test public void newlyAddedSourcesAreNotHiddenByTheManualRefreshQueue() throws Exception {
        SyncJob.request(context, true);
        onePlaylistBudget();
        store.add(link(3));
        assertFalse(runSync(() -> {}));
        assertEquals(4, attemptedCount());
    }

    private static final class Stopped extends RuntimeException {}
}
