package com.ayaanshaikh.offline;

import static org.junit.Assert.*;

import android.app.job.JobParameters;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.util.ReflectionHelpers;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class ActiveSyncJobTest {
    private static final String VIDEO = "abcdefghijk";
    private static final byte[] BODY = Arrays.copyOf(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}, 4096);
    private final PlaylistLink link = new PlaylistLink("spotify", "1a2b3c4d5e6f7g8h9i0j12");
    private final ControlledTransfer transfer = new ControlledTransfer();
    private Context context;
    private OfflineStore store;
    private SyncJob service;
    private ServiceController<SyncJob> controller;
    private ExecutorService worker;
    private ShadowConnectivityManager networks;
    private Network original;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(OfflineStore.class, "instance", null);
        context.getSharedPreferences("sync", 0).edit().clear().commit();
        store = OfflineStore.get(context);
        store.add(link);
        store.snapshot(link, new OfflineStore.Snapshot("Recent source", List.of(
                new OfflineStore.RemoteSong("track", "Deferred track", "Artist", 120, VIDEO))));
        store.failed(song(), VIDEO, "retry queued", false);
        assertEquals(1, song().attempts());
        assertTrue(store.queue(false).isEmpty());
        assertFalse(new SpotifySource(context).connected());
        networks = Shadows.shadowOf(context.getSystemService(ConnectivityManager.class));
        networks.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI, 0, true, true));
        original = context.getSystemService(ConnectivityManager.class).getActiveNetwork();
        eligible(original, true);
        assertTrue(SyncJob.wifi(context));
        controller = ServiceController.of(new SyncJob(transfer), null).create();
        service = controller.get();
        worker = ReflectionHelpers.getStaticField(SyncJob.class, "WORK");
        context.getSharedPreferences("sync", 0).edit().putBoolean("force", true).commit();
    }

    @After public void cleanup() throws Exception {
        transfer.release.countDown();
        if (service != null) service.onStopJob(params());
        if (worker != null) drain();
        if (controller != null) controller.destroy();
        if (store != null) store.close();
    }

    private OfflineStore.Song song() { return store.songs(link.key()).get(0); }
    private JobParameters params() {
        var params = Shadow.newInstanceOf(JobParameters.class);
        ReflectionHelpers.setField(params, "jobId", 1102);
        return params;
    }
    private void eligible(Network network, boolean valid) {
        var caps = ShadowNetworkCapabilities.newInstance();
        var shadow = Shadows.shadowOf(caps);
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        if (valid) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        networks.setNetworkCapabilities(network, caps);
    }
    private void drain() throws Exception {
        assertNull("Network binding must not leak to later work", worker.submit(Http.NETWORK::get).get(5, TimeUnit.SECONDS));
    }
    private void startPaused(boolean afterPublication) throws Exception {
        transfer.afterPublication = afterPublication;
        assertTrue(service.onStartJob(params()));
        assertTrue("Transfer never reached its barrier", transfer.entered.await(5, TimeUnit.SECONDS));
        assertEquals(original, transfer.usedNetwork);
    }
    private void interruptedState() {
        assertFalse("Stopped work must defer indexing", store.hasAudio(VIDEO));
        assertEquals(1, song().attempts());
        assertEquals(0, song().retryAt());
        assertEquals(1, store.queue(false).size());
        assertTrue(context.getSharedPreferences("sync", 0).getBoolean("forceInFlight", false));
        assertNull(SyncJob.downloadProgress());
    }
    private void retryAndVerify(int expectedTransfers) throws Exception {
        transfer.pause = false;
        transfer.fail = false;
        service.onStartJob(params());
        drain();
        assertTrue(store.hasAudio(VIDEO));
        assertTrue(store.queue(false).isEmpty());
        assertArrayEquals(BODY, java.nio.file.Files.readAllBytes(store.file(VIDEO + ".m4a").toPath()));
        assertEquals(expectedTransfers, transfer.downloads.get());
        assertFalse(context.getSharedPreferences("sync", 0).contains("forceInFlight"));
        assertNull(SyncJob.downloadProgress());
    }

    @Test public void stoppingAnActiveReadPreservesIntentAndRetryEligibility() throws Exception {
        startPaused(false);
        assertTrue(store.file(VIDEO + ".m4a.part").exists());
        assertTrue(service.onStopJob(params()));
        drain();
        interruptedState();
        assertFalse(store.file(VIDEO + ".m4a.part").exists());
        retryAndVerify(2);
    }

    @Test public void stoppingAfterPublicationDefersIndexingUntilResume() throws Exception {
        startPaused(true);
        assertTrue(store.file(VIDEO + ".m4a").exists());
        service.onStopJob(params());
        drain();
        interruptedState();
        retryAndVerify(1);
    }

    @Test public void aCapturedNetworkLossAfterPublicationDefersIndexing() throws Exception {
        startPaused(true);
        eligible(original, false);
        transfer.release.countDown();
        drain();
        interruptedState();
        eligible(original, true);
        retryAndVerify(1);
    }

    @Test public void losingTheCapturedNetworkDuringReadDoesNotCreateFailureBackoff() throws Exception {
        startPaused(false);
        eligible(original, false);
        transfer.release.countDown();
        drain();
        interruptedState();
        assertFalse(store.file(VIDEO + ".m4a.part").exists());
        eligible(original, true);
        retryAndVerify(2);
    }

    @Test public void aTransferFailureOnEligibleWifiStillReceivesNormalBackoff() throws Exception {
        transfer.fail = true;
        startPaused(false);
        transfer.release.countDown();
        drain();
        assertEquals(2, song().attempts());
        assertTrue(song().retryAt() > System.currentTimeMillis());
        assertTrue(store.queue(false).isEmpty());
        assertFalse(store.hasAudio(VIDEO));
        assertFalse(store.file(VIDEO + ".m4a.part").exists());
        assertFalse(context.getSharedPreferences("sync", 0).contains("forceInFlight"));
    }

    private final class ControlledTransfer implements SyncJob.Transfer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger downloads = new AtomicInteger();
        volatile boolean pause = true;
        volatile boolean afterPublication;
        volatile boolean fail;
        volatile Network usedNetwork;

        @Override public String save(String video, OfflineStore store, Runnable check, AudioDownload.Progress progress) throws Exception {
            usedNetwork = Http.NETWORK.get();
            store.ensureAudioDir();
            String recovered = store.findPublishedAudio(video);
            if (recovered != null) return recovered;
            downloads.incrementAndGet();
            var connection = new HttpURLConnection(new URL("https://rr1.googlevideo.com/fixture")) {
                @Override public int getResponseCode() { return 200; }
                @Override public long getContentLengthLong() { return BODY.length; }
                @Override public String getContentType() { return "audio/mp4"; }
                @Override public InputStream getInputStream() {
                    return new ByteArrayInputStream(BODY) {
                        @Override public synchronized int read(byte[] bytes, int offset, int count) {
                            if (pos >= 12 && pause && !afterPublication) {
                                entered.countDown();
                                try { release.await(); }
                                catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new ReadStopped();
                                }
                                if (fail) throw new ReadFailed();
                            }
                            return super.read(bytes, offset, count);
                        }
                    };
                }
                @Override public void disconnect() {}
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() {}
            };
            String file = AudioDownload.save(connection, store.file(video + ".m4a"), check, progress);
            if (pause && afterPublication) {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return file;
        }
    }
    private static final class ReadStopped extends RuntimeException {}
    private static final class ReadFailed extends RuntimeException {}
}
