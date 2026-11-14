package com.ayaanshaikh.offline;

import static org.junit.Assert.*;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class AudioRecoveryTest {
    private static final String VIDEO = "abcdefghijk";
    private static final byte[] M4A = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 1, 2, 3, 4};
    private static final byte[] WEBM = {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4};
    private final PlaylistLink link = new PlaylistLink("youtube", "PLrecovery1234");
    private Context context;
    private OfflineStore store;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        resetSingleton();
        store = OfflineStore.get(context);
        store.add(link);
        store.snapshot(link, new OfflineStore.Snapshot("Recovery", List.of(
                new OfflineStore.RemoteSong(VIDEO, "Saved song", "Artist", 120, VIDEO))));
        store.ensureAudioDir();
    }

    @After public void cleanup() { if (store != null) store.close(); }

    private void resetSingleton() throws Exception {
        var field = OfflineStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    private void reopen() throws Exception {
        store.close();
        resetSingleton();
        store = OfflineStore.get(context);
    }

    private void publish(String extension, byte[] bytes) throws Exception {
        var connection = new FixtureConnection(bytes);
        assertEquals(VIDEO + "." + extension, AudioDownload.save(connection,
                store.file(VIDEO + "." + extension), () -> {}, (received, total) -> {}));
        assertTrue(connection.disconnected);
    }

    private void indexRecoveredAudio(String filename) {
        var queued = store.queue(false);
        assertEquals(1, queued.size());
        store.saved(queued.get(0), VIDEO, filename);
    }

    @Test public void publishedM4aSurvivesReopenBeforeIndexing() throws Exception {
        publish("m4a", M4A);
        assertTrue(store.library().isEmpty());
        assertFalse(store.hasAudio(VIDEO));
        reopen();
        assertEquals(VIDEO + ".m4a", store.findPublishedAudio(VIDEO));
        assertArrayEquals(M4A, Files.readAllBytes(store.file(VIDEO + ".m4a").toPath()));
        String recovered = new YouTubeSource().save(VIDEO, store, () -> {},
                (received, total) -> fail("Recovery must not start a new transfer"));
        assertEquals(VIDEO + ".m4a", recovered);
        indexRecoveredAudio(recovered);
        reopen();
        assertTrue(store.hasAudio(VIDEO));
        assertTrue(store.queue(false).isEmpty());
        assertTrue(store.queue(true).isEmpty());
        assertEquals("Saved song", store.library().get(0).title());
        assertEquals(VIDEO + ".m4a", store.songs(link.key()).get(0).file());
    }

    @Test public void publishedWebmSurvivesReopenBeforeIndexing() throws Exception {
        publish("webm", WEBM);
        reopen();
        assertEquals(VIDEO + ".webm", store.findPublishedAudio(VIDEO));
        String recovered = new YouTubeSource().save(VIDEO, store, () -> {},
                (received, total) -> fail("Recovery must not start a new transfer"));
        assertEquals(VIDEO + ".webm", recovered);
        indexRecoveredAudio(recovered);
        reopen();
        assertTrue(store.hasAudio(VIDEO));
        assertEquals(1, store.library().size());
        assertTrue(store.queue(false).isEmpty());
    }

    @Test public void partialFilesStayUnindexedAndQueuedAfterReopen() throws Exception {
        Files.write(store.file(VIDEO + ".m4a.part").toPath(), M4A);
        Files.write(store.file(VIDEO + ".webm.part").toPath(), WEBM);
        reopen();
        assertNull(store.findPublishedAudio(VIDEO));
        assertTrue(store.library().isEmpty());
        assertEquals(1, store.queue(false).size());
        assertArrayEquals(M4A, Files.readAllBytes(store.file(VIDEO + ".m4a.part").toPath()));
    }

    @Test public void invalidM4aDoesNotHideAValidWebm() throws Exception {
        byte[] invalid = "<html>Not audio</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(store.file(VIDEO + ".m4a").toPath(), invalid);
        publish("webm", WEBM);
        reopen();
        assertEquals(VIDEO + ".webm", store.findPublishedAudio(VIDEO));
        indexRecoveredAudio(store.findPublishedAudio(VIDEO));
        assertArrayEquals(invalid, Files.readAllBytes(store.file(VIDEO + ".m4a").toPath()));
        assertEquals(VIDEO + ".webm", store.library().get(0).file());
    }

    @Test public void truncatedAndMismatchedFilesAreNotRecovered() throws Exception {
        Files.write(store.file(VIDEO + ".m4a").toPath(), Arrays.copyOf(M4A, 5));
        Files.write(store.file(VIDEO + ".webm").toPath(), M4A);
        reopen();
        assertNull(store.findPublishedAudio(VIDEO));
        assertFalse(store.hasAudio(VIDEO));
        assertTrue(store.library().isEmpty());
        assertEquals(1, store.queue(false).size());
    }

    @Test public void aNewTransferReplacesAnOldPartialRatherThanAppendingToIt() throws Exception {
        Files.write(store.file(VIDEO + ".m4a.part").toPath(), new byte[50_000]);
        reopen();
        assertNull(store.findPublishedAudio(VIDEO));
        publish("m4a", M4A);
        indexRecoveredAudio(store.findPublishedAudio(VIDEO));
        reopen();
        assertArrayEquals(M4A, Files.readAllBytes(store.file(VIDEO + ".m4a").toPath()));
        assertFalse(store.file(VIDEO + ".m4a.part").exists());
        assertTrue(store.queue(false).isEmpty());
    }

    @Test public void aMissingIndexedFileReturnsToTheQueueAfterReconciliation() throws Exception {
        publish("m4a", M4A);
        indexRecoveredAudio(store.findPublishedAudio(VIDEO));
        reopen();
        assertTrue(store.file(VIDEO + ".m4a").delete());
        store.reconcileAudio();
        assertTrue(store.library().isEmpty());
        assertFalse(store.hasAudio(VIDEO));
        assertEquals(1, store.queue(false).size());
        assertEquals(VIDEO, store.queue(false).get(0).video());
    }

    @Test public void sharedAudioIsExcludedFromBothPlaylistsAfterRecovery() throws Exception {
        var other = new PlaylistLink("youtube", "PLrecovery5678");
        store.add(other);
        store.snapshot(other, new OfflineStore.Snapshot("Other", List.of(
                new OfflineStore.RemoteSong(VIDEO, "Shared song", "Artist", 120, VIDEO))));
        publish("m4a", M4A);
        reopen();
        var first = store.songs(link.key()).get(0);
        store.saved(first, VIDEO, store.findPublishedAudio(VIDEO));
        reopen();
        assertEquals(1, store.library().size());
        assertTrue(store.queue(false).isEmpty());
        assertEquals(VIDEO + ".m4a", store.songs(other.key()).get(0).file());
    }

    @Test public void retryBackoffAndManualMatchesPersistAcrossReopen() throws Exception {
        var spotify = new PlaylistLink("spotify", "1a2b3c4d5e6f7g8h9i0j12");
        store.add(spotify);
        store.snapshot(spotify, new OfflineStore.Snapshot("Needs match", List.of(
                new OfflineStore.RemoteSong("spotify-track", "Unmatched", "Artist", 120, null))));
        store.failed(store.songs(link.key()).get(0), VIDEO, "retry queued", false);
        store.failed(store.songs(spotify.key()).get(0), null, "match needed", true);
        long retryAt = store.songs(link.key()).get(0).retryAt();
        reopen();
        assertTrue(store.queue(false).isEmpty());
        assertEquals(1, store.queue(true).size());
        assertEquals(VIDEO, store.queue(true).get(0).video());
        assertEquals(1, store.songs(link.key()).get(0).attempts());
        assertEquals(retryAt, store.songs(link.key()).get(0).retryAt());
        assertEquals("match needed", store.songs(spotify.key()).get(0).status());
        assertEquals(Long.MAX_VALUE, store.songs(spotify.key()).get(0).retryAt());
    }

    private static final class FixtureConnection extends HttpURLConnection {
        final byte[] bytes;
        boolean disconnected;
        FixtureConnection(byte[] bytes) throws IOException {
            super(new URL("https://rr1.googlevideo.com/audio"));
            this.bytes = bytes;
        }
        @Override public int getResponseCode() { return 200; }
        @Override public long getContentLengthLong() { return bytes.length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
