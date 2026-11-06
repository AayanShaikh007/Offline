package com.ayaanshaikh.offline;

import static org.junit.Assert.*;
import android.content.Context;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class OfflineStoreTest {
    private OfflineStore store;
    private final PlaylistLink link = new PlaylistLink(PlaylistLink.YOUTUBE, "PL0123456789abc");
    private final OfflineStore.RemoteSong first = new OfflineStore.RemoteSong("abcdefghijk", "First", "Artist", 120, "abcdefghijk");
    private final OfflineStore.RemoteSong second = new OfflineStore.RemoteSong("12345678901", "Second", "Artist", 130, "12345678901");

    @Before public void setup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        // Reset the singleton because each Robolectric test owns a new application/database.
        var field = OfflineStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
        store = OfflineStore.get(context);
        store.add(link);
    }
    private void snapshot(OfflineStore.RemoteSong... songs) {
        store.snapshot(link, new OfflineStore.Snapshot("Playlist", List.of(songs)));
    }
    @Test public void duplicatePlaylistsAndDuplicateTracksAreIdempotent() {
        assertFalse(store.add(link));
        assertEquals(1, store.snapshot(link, new OfflineStore.Snapshot("Name", List.of(first, first))));
        assertEquals(1, store.songs(link.key()).size());
    }
    @Test public void snapshotsReorderDeactivateAndRestoreTracks() {
        snapshot(first, second);
        snapshot(second);
        assertEquals("Second", store.songs(link.key()).get(0).title());
        assertEquals(1, store.queue().size());
        snapshot(second, first);
        assertEquals("First", store.songs(link.key()).get(1).title());
        snapshot();
        assertTrue(store.songs(link.key()).isEmpty());
        assertTrue(store.queue().isEmpty());
    }
    @Test public void untrackingKeepsAudioAndRejectsLateSnapshots() throws Exception {
        snapshot(first);
        var song = store.songs(link.key()).get(0);
        store.ensureAudioDir();
        assertTrue(store.file("abcdefghijk.m4a").createNewFile());
        store.saved(song, first.video(), "abcdefghijk.m4a");
        assertTrue(store.queue().isEmpty());
        store.remove(link);
        assertEquals(1, store.library().size());
        assertEquals(0, store.snapshot(link, new OfflineStore.Snapshot("late", List.of(first))));
        assertTrue(store.songs(link.key()).isEmpty());
    }
    @Test public void missingFilesAreRequeued() {
        snapshot(first);
        store.saved(store.songs(link.key()).get(0), first.video(), "missing.m4a");
        store.reconcileAudio();
        assertTrue(store.library().isEmpty());
        assertEquals(1, store.queue().size());
    }
    @Test public void retryResetsBackoffButPreservesManualMatchRequests() {
        var spotify = new PlaylistLink(PlaylistLink.SPOTIFY, "1a2b3c4d5e6f7g8h9i0j12");
        store.add(spotify);
        store.snapshot(spotify, new OfflineStore.Snapshot("Spotify", List.of(
                new OfflineStore.RemoteSong("track", "Song", "Artist", 120, null))));
        var unmatched = store.songs(spotify.key()).get(0);
        store.failed(unmatched, null, "match needed", true);
        snapshot(first);
        store.failed(store.songs(link.key()).get(0), first.video(), "retry queued", false);
        assertTrue(store.queue().isEmpty());
        store.retry();
        assertEquals(1, store.queue().size());
        assertEquals("match needed", store.songs(spotify.key()).get(0).status());
        assertTrue(store.setMatch(unmatched, first.video()));
        assertFalse(store.setMatch(unmatched, second.video()));
        assertEquals(2, store.queue().size());
    }
    @Test public void disconnectRemovesOnlySpotifySourcesAndRetainsVault() {
        store.add(new PlaylistLink(PlaylistLink.SPOTIFY, "1a2b3c4d5e6f7g8h9i0j12"));
        store.saved(new OfflineStore.Song("", "", "Saved", "Artist", 0, first.video(), "", 0, 0, true, null), first.video(), "saved.m4a");
        store.clearSpotify();
        assertEquals(List.of(link), store.playlists().stream().map(OfflineStore.Playlist::link).toList());
        assertEquals(1, store.library().size());
    }
    @Test public void eventLogIsBoundedAndNewestFirst() {
        for (int i = 0; i < 100; i++) store.log("Event " + i);
        assertEquals(80, store.events().size());
        assertEquals("Event 99", store.events().get(0).message());
    }
}
