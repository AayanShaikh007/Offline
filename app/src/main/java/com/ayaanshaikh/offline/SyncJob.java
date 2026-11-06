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

public final class SyncJob extends JobService {
    static final long HOUR = 3_600_000L;
    private static final int PERIODIC = 1101;
    private static final int IMMEDIATE = 1102;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final Map<JobParameters, Future<?>> jobs = new HashMap<>();
    private static volatile boolean running;
    private static volatile String activity = "";

    static boolean running() { return running; }
    static String activity() { return activity; }

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

    static void request(Context context, boolean force) {
        if (OfflineStore.get(context).playlists().isEmpty()) return;
        schedule(context);
        if (force) flags(context).edit().putBoolean("force", true).apply();
        enqueue(context, 0);
    }

    private static void enqueue(Context context, long delay) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(IMMEDIATE) == null) {
            scheduler.schedule(job(context, IMMEDIATE).setMinimumLatency(delay).build());
        }
    }

    static void cancelIfEmpty(Context context) {
        if (!OfflineStore.get(context).playlists().isEmpty()) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(PERIODIC);
        scheduler.cancel(IMMEDIATE);
        flags(context).edit().remove("force").apply();
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
                if (network == null) network = manager.getActiveNetwork();
                if (usable(manager, network)) {
                    Network selected = network;
                    Http.NETWORK.set(selected);
                    running = true;
                    more = runSync(() -> {
                        if (Thread.currentThread().isInterrupted() || !usable(manager, selected)) throw new InterruptedSync();
                    });
                } else {
                    retry = params.getJobId() == IMMEDIATE;
                }
            } catch (InterruptedSync e) {
                retry = params.getJobId() == IMMEDIATE;
            } catch (RuntimeException e) {
                OfflineStore.get(this).log("Sync stopped · " + e.getClass().getSimpleName());
                retry = params.getJobId() == IMMEDIATE;
            } finally {
                Http.NETWORK.remove();
                running = false;
                activity = "";
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
        if (retry) return;
        var playlists = OfflineStore.get(this).playlists();
        if (playlists.isEmpty()) return;
        boolean dueNow = flags(this).getBoolean("force", false) || playlists.stream().anyMatch(p -> p.checked() == 0);
        if (dueNow || more) {
            MAIN.postDelayed(() -> {
                if (!OfflineStore.get(this).playlists().isEmpty()) enqueue(this, dueNow ? 0 : 60_000);
            }, 400);
        }
    }

    @Override public boolean onStopJob(JobParameters params) {
        Future<?> work = jobs.remove(params);
        if (work != null) work.cancel(true);
        return params.getJobId() == IMMEDIATE;
    }

    private boolean runSync(Runnable checkNetwork) {
        OfflineStore store = OfflineStore.get(this);
        var playlists = store.playlists();
        if (playlists.isEmpty()) {
            flags(this).edit().remove("force").apply();
            return false;
        }
        boolean force = flags(this).getBoolean("force", false);
        flags(this).edit().remove("force").apply();
        var youtube = new YouTubeSource();
        var spotify = new SpotifySource(this);
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
                String problem = fromSpotify ? safeSpotifyError(e) : "YouTube playlist unavailable";
                store.playlistError(link, problem);
                store.log(link.source() + " · " + problem);
            }
        }
        int saved = 0;
        int attempted = 0;
        for (var song : store.queue()) {
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
                activity = "saving " + song.title();
                String file = youtube.save(video, store, checkNetwork);
                store.saved(song, video, file);
                saved++;
            } catch (Exception e) {
                checkNetwork.run();
                store.failed(song, video, "retry queued", false);
                store.log("Download or match will retry · " + song.title());
            }
        }
        if (refreshed || saved > 0) store.log("Sync finished · " + saved + " saved");
        return !store.queue().isEmpty();
    }

    private static String safeSpotifyError(Exception e) {
        String message = e.getMessage();
        return e instanceof java.io.IOException && message != null
                && (message.startsWith("Spotify ") || message.startsWith("Connect Spotify"))
                ? message : "Spotify playlist could not be read";
    }

    private static final class InterruptedSync extends RuntimeException {}
}
