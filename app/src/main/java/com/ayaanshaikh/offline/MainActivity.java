package com.ayaanshaikh.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(14, 17, 15);
    private static final int PANEL = Color.rgb(23, 29, 24);
    private static final int LINE = Color.rgb(49, 60, 48);
    private static final int INK = Color.rgb(233, 242, 224);
    private static final int MUTED = Color.rgb(160, 176, 154);
    private static final int GREEN = Color.rgb(200, 249, 115);
    private static final int AMBER = Color.rgb(248, 197, 113);
    private static final ExecutorService ACCOUNT_IO = Executors.newSingleThreadExecutor();

    private static final int TRACK_PAGE_SIZE = 60;

    private enum Page { SOURCES, VAULT, LOG, SETTINGS }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private OfflineStore store;
    private SpotifySource spotify;
    private Page page = Page.SOURCES;
    private OfflineStore.Playlist selected;
    private Object rendered;
    private TextView network, pulse, nowTitle, nowInfo, play;
    private LinearLayout body, tabs, player;
    private ScrollView scroll;
    private SeekBar seek;
    private PlaybackService playback;
    private boolean bound;
    private boolean dragging;
    private int trackPage;

    private final ServiceConnection playerConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            updatePlayer();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            updatePlayer();
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateChrome();
            updateBody(false);
            updatePlayer();
            main.postDelayed(this, 1400);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(0);
        store = OfflineStore.get(this);
        spotify = new SpotifySource(this);
        setContentView(screen());
        if (state != null) {
            page = Page.valueOf(state.getString("page", "SOURCES"));
            String key = state.getString("playlist");
            for (var playlist : store.playlists()) if (playlist.link().key().equals(key)) selected = playlist;
        }
        selectPage(page, selected);
        handleCallback(getIntent());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page.name());
        if (selected != null) state.putString("playlist", selected.link().key());
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), playerConnection, Context.BIND_AUTO_CREATE);
        SyncJob.onOpen(this);
        main.post(tick);
    }

    @Override protected void onStop() {
        main.removeCallbacks(tick);
        if (bound) unbindService(playerConnection);
        bound = false;
        playback = null;
        super.onStop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleCallback(intent);
    }

    private void handleCallback(Intent intent) {
        Uri callback = intent.getData();
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || callback == null) return;
        intent.setData(null);
        ACCOUNT_IO.execute(() -> {
            try {
                spotify.complete(callback);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    toast("Spotify connected");
                    SyncJob.request(this, true);
                    selectPage(Page.SETTINGS, null);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) toast(e instanceof java.io.IOException ? e.getMessage() : "Spotify connection failed");
                });
            }
        });
    }

    private View screen() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });

        LinearLayout header = row();
        header.setPadding(dp(18), dp(6), dp(12), dp(4));
        TextView brand = label(">_ OFFLINE", 19, GREEN, true);
        brand.setLetterSpacing(.10f);
        LinearLayout identity = column();
        identity.addView(brand);
        identity.addView(label("v" + BuildConfig.VERSION_NAME, 11, MUTED, false));
        header.addView(identity, weighted());
        network = label("", 11, GREEN, true);
        header.addView(network);
        header.addView(button("⚙", false, () -> selectPage(Page.SETTINGS, null)));
        root.addView(header);
        root.addView(rule());

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        body = column();
        body.setPadding(dp(18), dp(19), dp(18), dp(20));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        player = createPlayer();
        root.addView(player);
        root.addView(rule());
        tabs = row();
        tabs.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(tabs);
        return root;
    }

    private void selectPage(Page target, OfflineStore.Playlist playlist) {
        page = target;
        selected = playlist;
        trackPage = 0;
        rendered = null;
        tabs.removeAllViews();
        nav("01 / sources", Page.SOURCES);
        nav("02 / vault", Page.VAULT);
        nav("03 / logs", Page.LOG);
        scroll.scrollTo(0, 0);
        updateChrome();
        updateBody(true);
        updatePlayer();
    }

    private void nav(String text, Page target) {
        TextView view = button(text, page == target, () -> selectPage(target, null));
        view.setTextSize(11);
        view.setPadding(dp(3), dp(5), dp(3), dp(5));
        tabs.addView(view, weighted());
    }

    private void updateChrome() {
        boolean wifi = SyncJob.wifi(this);
        network.setText(wifi ? "● WI-FI" : "○ OFFLINE");
        network.setTextColor(wifi ? GREEN : MUTED);
        if (pulse != null) {
            pulse.setText("sys.status  " + SyncJob.status(wifi));
        }
    }

    private void updateBody(boolean force) {
        List<?> state;
        if (page == Page.SOURCES && selected != null) {
            var refreshed = store.playlists().stream().filter(p -> p.link().equals(selected.link())).findFirst();
            if (refreshed.isEmpty()) {
                selectPage(Page.SOURCES, null);
                return;
            }
            selected = refreshed.get();
            state = List.of(selected, store.songs(selected.link().key()));
        } else if (page == Page.SOURCES) {
            state = store.playlists();
        } else if (page == Page.VAULT) {
            state = store.library();
        } else if (page == Page.LOG) {
            state = store.events();
        } else {
            state = List.of(spotify.connected(), spotify.clientId());
        }
        if (!force && state.equals(rendered)) return;
        rendered = state;
        body.removeAllViews();
        pulse = null;
        switch (page) {
            case SOURCES -> {
                if (selected == null) sources();
                else playlist();
            }
            case VAULT -> vault();
            case LOG -> log();
            case SETTINGS -> settings();
        }
        updateChrome();
    }

    private void sources() {
        heading("01 / INPUT", "~/playlists", "Track a playlist. Take it offline.");
        LinearLayout status = card();
        status.addView(label("$ sync --watch --wifi", 14, GREEN, true));
        pulse = label("", 12, MUTED, false);
        pulse.setMaxLines(2);
        status.addView(pulse);
        body.addView(status, cardLayout());
        LinearLayout actions = row();
        actions.addView(button("+ add playlist", true, this::addPlaylist), weighted());
        actions.addView(button("↻ sync now", false, this::syncNow), weighted());
        body.addView(actions, cardLayout());
        var playlists = store.playlists();
        if (playlists.isEmpty()) {
            empty("No sources linked", "Paste a public YouTube or YouTube Music playlist, or a public Spotify playlist you own or collaborate on.");
        }
        int i = 1;
        for (var entry : playlists) {
            LinearLayout tile = card();
            tile.addView(label(String.format(Locale.ROOT, "%02d  /  %s", i++, entry.link().source().toUpperCase(Locale.ROOT)),
                    11, GREEN, true));
            tile.addView(label(entry.title(), 17, INK, true));
            String statusText = entry.checked() == 0 ? "awaiting first sync"
                    : entry.status().equals("synced") ? "last check " + when(entry.checked()) : entry.status();
            tile.addView(label(statusText + "   ›", 12, MUTED, false));
            click(tile, () -> selectPage(Page.SOURCES, entry));
            body.addView(tile, cardLayout());
        }
    }

    private void playlist() {
        String status = selected.checked() == 0 ? "Awaiting first sync" : selected.status();
        heading("01 / " + selected.link().source().toUpperCase(Locale.ROOT), selected.title(), status);
        var songs = store.songs(selected.link().key());
        long saved = songs.stream().filter(s -> s.file() != null).count();
        if (selected.checked() != 0) {
            body.addView(label(saved + " / " + songs.size() + " available offline", 12, GREEN, true), cardLayout());
        }
        LinearLayout actions = row();
        actions.addView(button("← sources", false, () -> selectPage(Page.SOURCES, null)), weighted());
        actions.addView(button("↗ original", false, () -> launch(Uri.parse(selected.link().url()))), weighted());
        actions.addView(button("− untrack", false, this::untrack), weighted());
        body.addView(actions, cardLayout());
        pulse = label("", 12, MUTED, false);
        body.addView(pulse, cardLayout());
        body.addView(button("↻ sync now", true, this::syncNow), cardLayout());
        if (songs.isEmpty() && "synced".equals(selected.status())) {
            empty("No tracks found", "This playlist returned no available tracks.");
        }
        tracks(songs);
    }

    private void vault() {
        var library = store.library();
        heading("02 / STORAGE", "~/vault", library.size() + " saved tracks · ready without a network");
        if (library.isEmpty()) empty("No local audio yet", "Tracks appear here as their downloads finish.");
        tracks(library);
    }

    private void tracks(List<OfflineStore.Song> songs) {
        if (songs.isEmpty()) return;
        int pages = (songs.size() + TRACK_PAGE_SIZE - 1) / TRACK_PAGE_SIZE;
        trackPage = Math.min(trackPage, pages - 1);
        int start = trackPage * TRACK_PAGE_SIZE;
        int end = Math.min(start + TRACK_PAGE_SIZE, songs.size());
        for (int i = start; i < end; i++) trackRow(songs.get(i), i + 1);
        if (pages == 1) return;
        var controls = row();
        var earlier = button("‹ previous", false, () -> changeTrackPage(-1));
        earlier.setEnabled(trackPage > 0);
        if (!earlier.isEnabled()) earlier.setAlpha(.4f);
        controls.addView(earlier, weighted());
        var count = label((trackPage + 1) + " / " + pages, 11, MUTED, true);
        controls.addView(count);
        var later = button("next ›", false, () -> changeTrackPage(1));
        later.setEnabled(trackPage + 1 < pages);
        if (!later.isEnabled()) later.setAlpha(.4f);
        controls.addView(later, weighted());
        body.addView(controls);
    }

    private void changeTrackPage(int direction) {
        trackPage += direction;
        updateBody(true);
        scroll.scrollTo(0, 0);
    }

    private void trackRow(OfflineStore.Song song, int number) {
        LinearLayout tile = row();
        tile.setPadding(dp(4), dp(10), dp(2), dp(10));
        TextView index = label(String.format(Locale.ROOT, "%02d", number), 11, MUTED, false);
        tile.addView(index, new LinearLayout.LayoutParams(dp(35), -2));
        LinearLayout meta = column();
        var title = label(song.title(), 14, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(title);
        var subtitle = label(song.artist().isEmpty() ? song.status() : song.artist(), 12, MUTED, false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(subtitle);
        tile.addView(meta, weighted());
        boolean match = "match needed".equals(song.status());
        if (song.file() != null) {
            tile.addView(button("▶", true, () -> playSong(song)));
            click(meta, () -> playSong(song));
        } else if (match) {
            tile.addView(button("match", false, () -> match(song)));
        } else {
            var status = label("retry queued".equals(song.status()) ? "RETRY" : "QUEUED", 10, AMBER, true);
            status.setPadding(dp(8), 0, 0, 0);
            tile.addView(status);
        }
        body.addView(tile);
        body.addView(rule());
    }

    private void log() {
        heading("03 / OUTPUT", "~/logs", "Playlist checks, downloads and tracks that need a match.");
        var events = store.events();
        if (events.isEmpty()) empty("No events yet", "Sync activity will appear here.");
        for (var event : events) {
            LinearLayout line = row();
            line.setGravity(Gravity.TOP);
            line.setPadding(0, dp(8), 0, dp(8));
            TextView timestamp = label(when(event.at()), 11, GREEN, true);
            line.addView(timestamp, new LinearLayout.LayoutParams(dp(61), -2));
            line.addView(label(event.message(), 12, INK, false), weighted());
            body.addView(line);
            body.addView(rule());
        }
    }

    private void settings() {
        heading("00 / CONFIG", "~/settings", "Connection and sync controls");
        LinearLayout account = card();
        account.addView(label("SPOTIFY / " + (spotify.connected() ? "CONNECTED" : "SETUP REQUIRED"), 13, GREEN, true));
        account.addView(label("Create a Spotify developer app, register this redirect URI, and enter its Client ID.", 13, MUTED, false));
        TextView redirect = label(SpotifySource.REDIRECT, 12, INK, false);
        redirect.setTextIsSelectable(true);
        account.addView(redirect);
        account.addView(button(spotify.connected() ? "Reconnect Spotify" : "Connect Spotify", false, this::connectSpotify));
        if (spotify.connected()) account.addView(button("Disconnect Spotify", false, this::disconnectSpotify));
        body.addView(account, cardLayout());
        LinearLayout sync = card();
        sync.addView(label("SYNC / UNMETERED WI-FI", 13, GREEN, true));
        sync.addView(label("Checks about once an hour. Android may defer checks on low battery or low storage.", 13, MUTED, false));
        sync.addView(button("Retry failed downloads", false, () -> {
            store.retry();
            SyncJob.request(this, false);
            toast("Retries queued for Wi-Fi");
        }));
        body.addView(sync, cardLayout());
    }

    private void heading(String eyebrow, String title, String detail) {
        TextView overline = label(eyebrow, 11, GREEN, true);
        overline.setLetterSpacing(.15f);
        body.addView(overline);
        TextView name = label(title, 31, INK, true);
        name.setPadding(0, dp(4), 0, dp(2));
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(name);
        TextView summary = label(detail, 13, MUTED, false);
        summary.setPadding(0, 0, 0, dp(15));
        body.addView(summary);
    }

    private void empty(String title, String detail) {
        LinearLayout panel = card();
        panel.addView(label("[ " + title + " ]", 14, INK, true));
        panel.addView(label(detail, 13, MUTED, false));
        body.addView(panel, cardLayout());
    }

    private LinearLayout createPlayer() {
        LinearLayout shell = column();
        shell.setBackgroundColor(PANEL);
        shell.setPadding(dp(14), dp(5), dp(14), 0);
        LinearLayout controls = row();
        LinearLayout info = column();
        nowTitle = label("", 14, GREEN, true);
        nowTitle.setMaxLines(1);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowInfo = label("", 11, MUTED, false);
        nowInfo.setMaxLines(1);
        nowInfo.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(nowTitle);
        info.addView(nowInfo);
        controls.addView(info, weighted());
        var previous = button("‹‹", false, () -> { if (playback != null) playback.previous(); });
        previous.setContentDescription("Previous track");
        controls.addView(previous);
        play = button("▶", true, () -> { if (playback != null) playback.toggle(); });
        controls.addView(play);
        var next = button("››", false, () -> { if (playback != null) playback.next(); });
        next.setContentDescription("Next track");
        controls.addView(next);
        shell.addView(controls);
        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(GREEN));
        seek.setThumbTintList(ColorStateList.valueOf(GREEN));
        seek.setContentDescription("Playback position");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user && playback != null && !dragging) playback.seek(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (playback != null) playback.seek(bar.getProgress());
            }
        });
        shell.addView(seek, new LinearLayout.LayoutParams(-1, dp(28)));
        shell.setVisibility(View.GONE);
        return shell;
    }

    private void updatePlayer() {
        var state = playback == null ? null : playback.now();
        if (state == null || (state.file().isEmpty() && state.error().isEmpty())) {
            player.setVisibility(View.GONE);
            return;
        }
        player.setVisibility(View.VISIBLE);
        nowTitle.setText(state.title().isEmpty() ? "Playback error" : state.title());
        nowInfo.setText(!state.error().isEmpty() ? state.error()
                : elapsed(state.position()) + " / " + elapsed(state.duration()) + "  " + state.artist());
        play.setText(state.playing() ? "Ⅱ" : "▶");
        play.setContentDescription(state.playing() ? "Pause" : "Play");
        seek.setEnabled(state.ready());
        if (!dragging) {
            seek.setMax(Math.max(1, state.duration()));
            seek.setProgress(state.position());
        }
    }

    private void addPlaylist() {
        input("Add playlist", "Paste a playlist URL", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                value -> {
                    PlaylistLink link = PlaylistLink.parse(value);
                    if (!store.add(link)) throw new IllegalArgumentException("Playlist already tracked");
                    SyncJob.request(this, false);
                    updateBody(true);
                    if (PlaylistLink.SPOTIFY.equals(link.source()) && !spotify.connected()) {
                        toast("Playlist added. Connect Spotify in Settings.");
                    } else {
                        toast(SyncJob.wifi(this) ? "Starting playlist sync" : "Waiting for unmetered Wi-Fi");
                    }
                });
    }

    private void untrack() {
        var playlist = selected;
        new AlertDialog.Builder(this).setTitle("Untrack playlist?")
                .setMessage("Saved audio stays in your vault.")
                .setNegativeButton("Cancel", null).setPositiveButton("Untrack", (dialog, which) -> {
                    store.remove(playlist.link());
                    SyncJob.cancelIfEmpty(this);
                    selectPage(Page.SOURCES, null);
                }).show();
    }

    private void match(OfflineStore.Song song) {
        input("Match " + song.title(), "Paste a YouTube video URL", "", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI, value -> {
            String video = PlaylistLink.videoId(value);
            if (video == null) throw new IllegalArgumentException("Paste a valid YouTube video URL");
            if (!store.setMatch(song, video)) throw new IllegalStateException("This match has already changed.");
            SyncJob.request(this, false);
            updateBody(true);
        });
    }

    private void connectSpotify() {
        input("Connect Spotify", "Spotify Client ID", spotify.clientId(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                value -> launch(spotify.authorize(value)));
    }

    private void disconnectSpotify() {
        new AlertDialog.Builder(this).setTitle("Disconnect Spotify?")
                .setMessage("Your Spotify playlists are removed from Offline. Saved audio stays in your vault.")
                .setNegativeButton("Cancel", null).setPositiveButton("Disconnect", (dialog, which) -> {
                    spotify.disconnect(store);
                    SyncJob.cancelIfEmpty(this);
                    updateBody(true);
                }).show();
    }

    private void syncNow() {
        if (store.playlists().isEmpty()) {
            toast("Add a playlist first");
            return;
        }
        SyncJob.request(this, true);
        updateChrome();
        toast(SyncJob.wifi(this) ? "Starting sync" : "Waiting for unmetered Wi-Fi");
    }

    private void playSong(OfflineStore.Song song) {
        startForegroundService(new Intent(this, PlaybackService.class).setAction(PlaybackService.PLAY)
                .putExtra(PlaybackService.FILE, song.file()).putExtra(PlaybackService.PLAYLIST, song.playlist()));
        main.postDelayed(this::updatePlayer, 200);
    }

    private void launch(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (android.content.ActivityNotFoundException e) { toast("Install a browser to open this link"); }
    }

    private interface InputAction { void submit(String value) throws Exception; }

    private void input(String title, String hint, String initial, int type, InputAction action) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(type);
        field.setHint(hint);
        field.setText(initial);
        LinearLayout padding = column();
        padding.setPadding(dp(20), dp(5), dp(20), 0);
        padding.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(padding)
                .setNegativeButton("Cancel", null).setPositiveButton("Continue", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                action.submit(field.getText().toString().trim());
                dialog.dismiss();
            } catch (Exception e) {
                field.setError(e.getMessage() == null ? "Could not complete this request" : e.getMessage());
            }
        }));
        dialog.show();
    }

    private String when(long millis) { return millis == 0 ? "pending" : time.format(new Date(millis)); }
    private String elapsed(int millis) {
        int seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        var view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout row() {
        var view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private LinearLayout.LayoutParams cardLayout() {
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        return params;
    }
    private View rule() {
        View line = new View(this);
        line.setBackgroundColor(LINE);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return line;
    }
    private LinearLayout card() {
        var view = column();
        view.setPadding(dp(13), dp(12), dp(13), dp(12));
        view.setBackground(box(PANEL, LINE));
        return view;
    }
    private GradientDrawable box(int color, int stroke) {
        var shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(5));
        shape.setStroke(dp(1), stroke);
        return shape;
    }
    private TextView label(String text, int size, int color, boolean bold) {
        var view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }
    private TextView button(String text, boolean primary, Runnable action) {
        var view = label(text, 13, primary ? GREEN : INK, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
        if (primary) view.setBackground(box(PANEL, LINE));
        click(view, action);
        return view;
    }
    private void click(View view, Runnable action) {
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        var highlight = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, highlight, true);
        if (highlight.resourceId != 0) view.setForeground(getDrawable(highlight.resourceId));
    }
}
