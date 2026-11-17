package com.ayaanshaikh.offline;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Host-driven smoke stages. Only run against a fresh, disposable emulator installation. */
public final class NativeSyncSmoke extends Instrumentation {
    private String stage;
    private Context context;
    private OfflineStore store;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        stage = arguments.getString("stage", "");
        start();
    }

    @Override public void onStart() {
        context = getTargetContext();
        store = OfflineStore.get(context);
        try {
            switch (stage) {
                case "checkpoint" -> checkpoint();
                case "inspect" -> verifyCheckpoint();
                case "block" -> blockWorker();
                case "resumed" -> verifyResumed();
                default -> throw new AssertionError("Unknown smoke stage: " + stage);
            }
            var result = new Bundle();
            result.putString("stream", "PASS " + stage + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            var result = new Bundle();
            result.putString("stream", "FAIL " + stage + ": " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private SharedPreferences flags() { return context.getSharedPreferences("sync", 0); }

    private PlaylistLink link(int i) {
        return new PlaylistLink("spotify", String.format(java.util.Locale.ROOT, "%022d", i));
    }

    private long attempts() {
        return store.playlists().stream().filter(p -> p.status().startsWith("Connect Spotify")).count();
    }

    private void checkpoint() throws Exception {
        require(!SyncJob.wifi(context), "Disable Wi-Fi before seeding");
        require(store.playlists().isEmpty() && store.library().isEmpty(), "Requires empty test data");
        require(!new SpotifySource(context).connected(), "Requires disconnected Spotify");
        for (int i = 0; i < 3; i++) {
            store.add(link(i));
            store.snapshot(link(i), new OfflineStore.Snapshot("Native smoke " + i, List.of()));
        }
        SyncJob.request(context, true);
        var method = SyncJob.class.getDeclaredMethod("runSync", Context.class, Runnable.class);
        method.setAccessible(true);
        try {
            method.invoke(null, context, (Runnable) () -> {
                if (attempts() == 1) throw new Stop();
            });
            throw new AssertionError("Expected controlled interruption");
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof Stop)) throw e;
        }
        long marker = System.currentTimeMillis() - 60_000;
        store.getWritableDatabase().execSQL("UPDATE playlists SET checked=? WHERE key=?",
                new Object[]{marker, link(0).key()});
        require(context.getSharedPreferences("native-smoke", 0).edit()
                .putLong("firstCheck", marker).commit(), "Could not persist fixture marker");
        // Flush already completed checkpoints before the host kills us. This does not test apply() crash windows.
        require(flags().edit().commit(), "Could not flush checkpoint");
        verifyCheckpoint();
        signal("CHECKPOINT_READY");
        // Keep SQLite open: the host SIGKILLs this process without orderly helper teardown.
        Thread.sleep(TimeUnit.MINUTES.toMillis(3));
        throw new AssertionError("Host did not kill checkpoint process");
    }

    private void verifyCheckpoint() {
        require(store.playlists().size() == 3 && attempts() == 1, "Unexpected metadata attempts");
        require(flags().getBoolean("forceInFlight", false), "Lost in-flight intent");
        require(flags().getBoolean("forcePrepared", false), "Lost preparation marker");
        require(flags().getStringSet("forceRefreshes", Set.of()).equals(Set.of(link(1).key(), link(2).key())),
                "Lost remaining refresh targets");
        verifyFirstCheck();
    }

    private void verifyFirstCheck() {
        long marker = context.getSharedPreferences("native-smoke", 0).getLong("firstCheck", -1);
        require(marker > 0 && store.playlists().get(0).checked() == marker, "Repeated visited source");
    }

    private void blockWorker() throws Exception {
        verifyCheckpoint();
        require(!SyncJob.wifi(context), "Keep Wi-Fi disabled during cancellation test");
        SyncJob.request(context, false);
        var field = SyncJob.class.getDeclaredField("WORK");
        field.setAccessible(true);
        var worker = (ExecutorService) field.get(null);
        File release = new File(context.getFilesDir(), "native-smoke-release");
        require(!release.exists(), "Stale release marker");
        var entered = new java.util.concurrent.CountDownLatch(1);
        worker.submit(() -> {
            entered.countDown();
            try { awaitRelease(release); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        require(entered.await(30, TimeUnit.SECONDS), "Worker did not start");
        signal("BLOCK_READY");
        long networkDeadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(3);
        while (!SyncJob.wifi(context)) {
            require(SystemClock.elapsedRealtime() < networkDeadline, "No eligible emulator Wi-Fi");
            Thread.sleep(100);
        }
        signal("WIFI_READY");
        awaitRelease(release);
        require(SyncJob.wifi(context), "Eligible Wi-Fi is needed to detect an uncancelled worker");
        // Drain behind the job. onStopJob must have cancelled its queued Future before this runs.
        worker.submit(() -> {}).get(30, TimeUnit.SECONDS);
        verifyCheckpoint();
    }

    private void awaitRelease(File release) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(5);
        while (!release.exists()) {
            require(SystemClock.elapsedRealtime() < deadline, "Host did not release worker");
            Thread.sleep(100);
        }
    }

    private void verifyResumed() {
        require(store.playlists().size() == 3 && attempts() == 3, "Native job did not finish remaining sources");
        for (String key : List.of("force", "forceInFlight", "forcePrepared", "forceRefreshes")) {
            require(!flags().contains(key), "Unacknowledged request: " + key);
        }
        verifyFirstCheck();
    }

    private void signal(String text) {
        var result = new Bundle();
        result.putString("stream", text + "\n");
        sendStatus(1, result);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Stop extends RuntimeException {}
}
