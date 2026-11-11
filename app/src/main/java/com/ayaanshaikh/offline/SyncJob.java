package com.ayaanshaikh.offline;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SyncJob extends JobService {
    static final long HOUR = 3_600_000L;
    private static final int PERIODIC = 1101;
    private static final int IMMEDIATE = 1102;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean FOREGROUND_PENDING = new AtomicBoolean();
    private static final Object FORCE_LOCK = new Object();
    private final Map<JobParameters, Future<?>> jobs = new HashMap<>();
    private static volatile boolean running;
    private static volatile String activity = "";
    private static volatile DownloadProgress downloadProgress;

    record DownloadProgress(String title, long received, long total) {
        int percent() {
            return total > 0 ? (int) Math.min(100, received * 100 / total) : -1;
        }
    }

    static DownloadProgress downloadProgress() { return downloadProgress; }

    static String status(boolean wifi) {
        if (running) return activity;
        if (FOREGROUND_PENDING.get()) return "starting sync";
        return wifi ? "watching · ~60 min" : "waiting for unmetered Wi-Fi";
    }

    private static SharedPreferences flags(Context c) {
        return c.getSharedPreferences("sync", Context.MODE_PRIVATE);
    }

    private static JobInfo.Builder job(Context context, int id) {
        JobInfo.Builder job = new JobInfo.Builder(id, new ComponentName(context, SyncJob.class));
        if (Build.VERSION.SDK_INT >= 28) {
            job.setRequiredNetwork(new android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    .build());
        } else {
            job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
        }
        return job.setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true);
    }

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(PERIODIC) == null) {
            scheduler.schedule(job(context, PERIODIC).setPeriodic(HOUR, 15 * 60_000L)
                    .setPersisted(true).build());
        }
    }

    // UI requests start in-process on eligible Wi-Fi. JobScheduler handles deferred work.
    static void request(Context context, boolean force) {
        Context app = context.getApplicationContext();
        if (OfflineStore.get(app).playlists().isEmpty()) return;
        schedule(app);
        if (force) {
            synchronized (FORCE_LOCK) {
                flags(app).edit().putBoolean("force", true).apply();
            }
        }
        if (!wifi(app)) {
            enqueue(app, 0);
            return;
        }
        if (!FOREGROUND_PENDING.compareAndSet(false, true)) return;
        WORK.execute(() -> {
            FOREGROUND_PENDING.set(false);
            var manager = app.getSystemService(ConnectivityManager.class);
            Network network = manager.getActiveNetwork();
            try {
                if (!usable(manager, network)) throw new InterruptedSync();
                boolean more = runOnNetwork(app, manager, network);
                MAIN.post(() -> scheduleRemaining(app, more));
            } catch (InterruptedSync e) {
                MAIN.post(() -> enqueue(app, 0));
            } catch (RuntimeException e) {
                OfflineStore.get(app).log("Sync stopped · " + e.getClass().getSimpleName());
                MAIN.post(() -> enqueue(app, 60_000));
            }
        });
    }

    private static void enqueue(Context context, long delay) {
        if (OfflineStore.get(context).playlists().isEmpty()) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(IMMEDIATE) == null) {
            JobInfo.Builder work = job(context, IMMEDIATE);
            if (delay > 0) work.setMinimumLatency(delay);
            if (scheduler.schedule(work.build()) != JobScheduler.RESULT_SUCCESS) {
                OfflineStore.get(context).log("Android couldn't schedule background sync · open Offline on Wi-Fi to retry");
            }
        }
    }

    static void cancelIfEmpty(Context context) {
        if (!OfflineStore.get(context).playlists().isEmpty()) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(PERIODIC);
        scheduler.cancel(IMMEDIATE);
        takeForce(context);
    }

    private static boolean takeForce(Context context) {
        synchronized (FORCE_LOCK) {
            boolean force = flags(context).getBoolean("force", false);
            flags(context).edit().remove("force").apply();
            return force;
        }
    }

    static void onOpen(Context context) {
        var playlists = OfflineStore.get(context).playlists();
        if (playlists.isEmpty()) return;
        schedule(context);
        long now = System.currentTimeMillis();
        for (var playlist : playlists) {
            if (playlist.checked() == 0 || now - playlist.checked() >= HOUR) {
                request(context, false);
                return;
            }
        }
    }

    static boolean wifi(Context context) {
        var manager = context.getSystemService(ConnectivityManager.class);
        return usable(manager, manager.getActiveNetwork());
    }

    private static boolean usable(ConnectivityManager manager, Network network) {
        if (network == null) return false;
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    @Override public boolean onStartJob(JobParameters params) {
        Future<?> work = WORK.submit(() -> {
            boolean retry = false;
            boolean more = false;
            try {
                var manager = getSystemService(ConnectivityManager.class);
                Network network = Build.VERSION.SDK_INT >= 28 ? params.getNetwork() : manager.getActiveNetwork();
                if (!usable(manager, network)) network = manager.getActiveNetwork();
                if (usable(manager, network)) {
                    more = runOnNetwork(this, manager, network);
                } else {
                    retry = params.getJobId() == IMMEDIATE;
                }
            } catch (InterruptedSync e) {
                retry = params.getJobId() == IMMEDIATE;
            } catch (RuntimeException e) {
                OfflineStore.get(this).log("Sync stopped · " + e.getClass().getSimpleName());
                retry = params.getJobId() == IMMEDIATE;
            } finally {
                boolean reschedule = retry;
                boolean remaining = more;
                MAIN.post(() -> complete(params, reschedule, remaining));
            }
        });
        jobs.put(params, work);
        return true;
    }

    private void complete(JobParameters params, boolean retry, boolean more) {
        if (jobs.remove(params) == null) return;
        jobFinished(params, retry);
        if (!retry) MAIN.postDelayed(() -> scheduleRemaining(this, more), 400);
    }

    private static void scheduleRemaining(Context context, boolean more) {
        var playlists = OfflineStore.get(context).playlists();
        if (playlists.isEmpty()) return;
        boolean dueNow = flags(context).getBoolean("force", false) || playlists.stream().anyMatch(p -> p.checked() == 0);
        if (dueNow || more) enqueue(context, dueNow ? 0 : 60_000);
    }

    @Override public boolean onStopJob(JobParameters params) {
        Future<?> work = jobs.remove(params);
        if (work != null) work.cancel(true);
        return params.getJobId() == IMMEDIATE;
    }

    private static boolean runOnNetwork(Context context, ConnectivityManager manager, Network network) {
        activity = "starting sync";
        running = true;
        Http.NETWORK.set(network);
        try {
            return runSync(context, () -> {
                if (Thread.currentThread().isInterrupted() || !usable(manager, network)) throw new InterruptedSync();
            });
        } finally {
            Http.NETWORK.remove();
            running = false;
            activity = "";
        }
    }

    private static boolean runSync(Context context, Runnable checkNetwork) {
        OfflineStore store = OfflineStore.get(context);
        boolean force = takeForce(context);
        var playlists = store.playlists();
        if (playlists.isEmpty()) return false;
        var youtube = new YouTubeSource();
        var spotify = new SpotifySource(context);
        long end = SystemClock.elapsedRealtime() + 6 * 60_000L;
        store.reconcileAudio();
        boolean refreshed = false;
        for (var playlist : playlists) {
            checkNetwork.run();
            if (!force && System.currentTimeMillis() - playlist.checked() < HOUR - 15 * 60_000L) continue;
            if (SystemClock.elapsedRealtime() >= end) break;
            PlaylistLink link = playlist.link();
            activity = "reading " + link.source() + " playlist";
            boolean fromSpotify = PlaylistLink.SPOTIFY.equals(link.source());
            String session = spotify.session();
            try {
                var snapshot = fromSpotify ? spotify.snapshot(link) : youtube.snapshot(link);
                checkNetwork.run();
                int newSongs = fromSpotify ? spotify.apply(store, link, snapshot, session)
                        : store.snapshot(link, snapshot);
                if (!fromSpotify || session.equals(spotify.session())) {
                    store.log(link.source() + " · " + snapshot.name() + " · " + newSongs + " new");
                }
                refreshed = true;
            } catch (Exception e) {
                checkNetwork.run();
                String problem = fromSpotify ? safeSpotifyError(e) : "YouTube playlist · " + youtubeProblem(e);
                store.playlistError(link, problem);
                store.log(link.source() + " · " + problem);
            }
        }
        int saved = 0;
        int attempted = 0;
        for (var song : store.queue(force)) {
            checkNetwork.run();
            if (SystemClock.elapsedRealtime() >= end || attempted >= 30) break;
            if (!store.isActive(song)) continue;
            String video = song.video();
            if (video != null && store.hasAudio(video)) continue;
            attempted++;
            try {
                if (video == null) {
                    activity = "matching " + song.title();
                    video = youtube.match(song);
                    checkNetwork.run();
                    if (video == null) {
                        store.failed(song, null, "match needed", true);
                        store.log("Needs a YouTube match · " + song.title());
                        continue;
                    }
                    if (!store.setMatch(song, video)) continue;
                }
                if (store.hasAudio(video)) continue;
                activity = "downloading audio";
                downloadProgress = new DownloadProgress(song.title(), 0, -1);
                String file = youtube.save(video, store, checkNetwork, (received, total) ->
                        downloadProgress = new DownloadProgress(song.title(), received, total));
                store.saved(song, video, file);
                saved++;
            } catch (Exception e) {
                checkNetwork.run();
                store.failed(song, video, "retry queued", false);
                store.log("Retry queued · " + song.title() + " · " + youtubeProblem(e));
            } finally {
                downloadProgress = null;
            }
        }
        if (refreshed || attempted > 0) {
            store.log("Sync finished · " + saved + " saved · " + attempted + " attempted");
        }
        return !store.queue(false).isEmpty();
    }

    private static String youtubeProblem(Exception error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof Http.DnsFailure) return cause.getMessage();
            if (cause instanceof java.net.SocketTimeoutException) return "connection timed out";
            if (cause instanceof java.net.UnknownHostException) return "could not reach the server";
            if (cause instanceof javax.net.ssl.SSLException) return "secure connection failed";
            if (cause instanceof org.schabi.newpipe.extractor.exceptions.ReCaptchaException) return "YouTube rate limited the request";
            String message = cause.getMessage();
            if (cause instanceof java.io.IOException && message != null
                    && (message.matches("Audio server returned HTTP [0-9]{3}")
                    || message.equals("Audio server returned a page")
                    || message.equals("Untrusted audio server")
                    || message.equals("No supported audio stream")
                    || message.equals("Audio response does not match its container format"))) return message;
        }
        return "request failed (" + error.getClass().getSimpleName() + ")";
    }

    private static String safeSpotifyError(Exception e) {
        String message = e.getMessage();
        return e instanceof java.io.IOException && message != null
                && (message.startsWith("Spotify ") || message.startsWith("Connect Spotify"))
                ? message : "Spotify playlist could not be read";
    }

    private static final class InterruptedSync extends RuntimeException {}
}
