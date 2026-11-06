package com.ayaanshaikh.offline;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

public final class PlaybackService extends Service {
    private static final int NOTIFICATION = 2101;
    private static final String CHANNEL = "offline.playback";
    static final String PLAY = "offline.PLAY";
    private static final String TOGGLE = "offline.TOGGLE";
    private static final String NEXT = "offline.NEXT";
    private static final String PREVIOUS = "offline.PREVIOUS";
    private static final String STOP = "offline.STOP";
    static final String FILE = "file";
    static final String PLAYLIST = "playlist";

    final class LocalBinder extends Binder {
        PlaybackService service() { return PlaybackService.this; }
    }

    record Now(String title, String artist, String file, int position, int duration,
               boolean playing, boolean ready, String error) {}

    private final LocalBinder binder = new LocalBinder();
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(false); }
    };
    private List<OfflineStore.Song> queue = List.of();
    private int index;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean ready;
    private boolean playWhenReady;
    private boolean resumeOnFocus;
    private boolean foreground;
    private String error = "";

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL, "Offline playback", NotificationManager.IMPORTANCE_LOW));
        audio = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocus) {
                        resumeOnFocus = false;
                        resume();
                    } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        boolean wasPlaying = playWhenReady;
                        pause(true);
                        resumeOnFocus = wasPlaying;
                    } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
                        pause(false);
                    }
                }).build();
        session = new MediaSession(this, "Offline");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(false); }
            @Override public void onStop() { stop(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seek((int) pos); }
        });
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, filter);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (PLAY.equals(action)) {
            foreground = true;
            startForeground(NOTIFICATION, notification());
            open(intent.getStringExtra(FILE), intent.getStringExtra(PLAYLIST));
        } else if (TOGGLE.equals(action)) {
            toggle();
        } else if (NEXT.equals(action)) {
            next();
        } else if (PREVIOUS.equals(action)) {
            previous();
        } else if (STOP.equals(action)) {
            stop();
        }
        return START_NOT_STICKY;
    }

    private void open(String file, String playlist) {
        OfflineStore store = OfflineStore.get(this);
        List<OfflineStore.Song> source = playlist == null || playlist.isEmpty() ? store.library() : store.songs(playlist);
        var downloaded = new ArrayList<OfflineStore.Song>();
        int selected = -1;
        for (var song : source) {
            if (song.file() == null || !store.file(song.file()).isFile()) continue;
            if (song.file().equals(file) && selected == -1) selected = downloaded.size();
            downloaded.add(song);
        }
        if (selected == -1) {
            stop();
            error = "Audio file unavailable. Run a sync to restore it.";
            return;
        }
        queue = downloaded;
        index = selected;
        prepare();
    }

    private OfflineStore.Song current() {
        return queue.isEmpty() ? null : queue.get(index);
    }

    private void prepare() {
        releasePlayer();
        error = "";
        var song = current();
        if (song == null) return;
        playWhenReady = true;
        ready = false;
        session.setActive(true);
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, song.title())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist()).build());
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attributes);
            player.setDataSource(OfflineStore.get(this).file(song.file()).getAbsolutePath());
            player.setOnPreparedListener(loaded -> {
                if (player != loaded) return;
                ready = true;
                if (playWhenReady) resume();
                else publish();
            });
            player.setOnCompletionListener(finished -> {
                if (player != finished) return;
                if (index + 1 < queue.size()) next();
                else {
                    pause(false);
                    seek(0);
                }
            });
            player.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) playbackFailed();
                return true;
            });
            player.prepareAsync();
            publish();
        } catch (Exception e) {
            playbackFailed();
        }
    }

    Now now() {
        var song = current();
        return new Now(song == null ? "" : song.title(), song == null ? "" : song.artist(),
                song == null ? "" : song.file(), ready ? player.getCurrentPosition() : 0,
                ready ? player.getDuration() : 0, ready && player.isPlaying(), ready, error);
    }

    void toggle() {
        if (player == null) return;
        if (playWhenReady) pause(false);
        else resume();
    }

    void next() {
        if (index + 1 >= queue.size()) return;
        index++;
        prepare();
    }

    void previous() {
        if (ready && player.getCurrentPosition() > 3000) seek(0);
        else if (index > 0) {
            index--;
            prepare();
        } else {
            seek(0);
        }
    }

    void seek(int position) {
        if (!ready) return;
        player.seekTo(Math.max(0, Math.min(position, player.getDuration())));
        publish();
    }

    private void resume() {
        if (player == null) return;
        playWhenReady = true;
        if (!ready) return;
        try {
            if (!foreground) {
                startForeground(NOTIFICATION, notification());
                foreground = true;
            }
            if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                player.start();
                error = "";
                publish();
                return;
            }
        } catch (IllegalStateException e) {
            error = "Open Offline to resume playback.";
        }
        pause(false);
    }

    private void pause(boolean keepFocus) {
        playWhenReady = false;
        resumeOnFocus = false;
        if (ready && player.isPlaying()) player.pause();
        if (!keepFocus) audio.abandonAudioFocusRequest(focus);
        if (foreground && !keepFocus) {
            stopForeground(STOP_FOREGROUND_DETACH);
            foreground = false;
        }
        publish();
    }

    private void playbackFailed() {
        error = "This audio file could not be played.";
        releasePlayer();
        pause(false);
    }

    // Media-session notifications are exempt from Android's notification permission.
    @android.annotation.SuppressLint("NotificationPermission")
    private void publish() {
        Now state = now();
        int playback = !state.error().isEmpty() ? PlaybackState.STATE_ERROR : state.playing()
                ? PlaybackState.STATE_PLAYING : !state.ready() && playWhenReady
                ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO).setState(playback, state.position(),
                state.playing() ? 1f : 0f, SystemClock.elapsedRealtime()).build());
        if (current() != null) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }

    private Notification notification() {
        var song = current();
        boolean playing = playWhenReady;
        PendingIntent openApp = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_offline)
                .setContentTitle(song == null ? "Offline" : song.title())
                .setContentText(song == null ? "Preparing audio" : song.artist())
                .setContentIntent(openApp).setOnlyAlertOnce(true).setOngoing(playing)
                .setDeleteIntent(control(STOP))
                .addAction(new Notification.Action.Builder(null, "Previous", control(PREVIOUS)).build())
                .addAction(new Notification.Action.Builder(null, playing ? "Pause" : "Play", control(TOGGLE)).build())
                .addAction(new Notification.Action.Builder(null, "Next", control(NEXT)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)).build();
    }

    private PendingIntent control(String action) {
        return PendingIntent.getService(this, 0, new Intent(this, PlaybackService.class)
                .setAction(action), PendingIntent.FLAG_IMMUTABLE);
    }

    private void releasePlayer() {
        ready = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void stop() {
        releasePlayer();
        playWhenReady = false;
        resumeOnFocus = false;
        audio.abandonAudioFocusRequest(focus);
        queue = List.of();
        session.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        stopSelf();
    }

    @Override public void onDestroy() {
        stop();
        session.release();
        unregisterReceiver(noisy);
        super.onDestroy();
    }
}
