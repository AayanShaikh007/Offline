package com.ayaanshaikh.offline;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class GlassPlayer extends LinearLayout {
    private static final int MINT = 0xFFC8F973;
    private static final int TEXT = 0xFFF1F6ED;
    private static final int SUBTLE = 0xFFB7C4BC;
    private static final int LINE = 0x557DAB93;
    private static final int RIPPLE = 0x447DAB93;

    private final Supplier<PlaybackService> playback;
    private final boolean expanded;
    private final TextView title;
    private final TextView artist;
    private final TextView elapsed;
    private final TextView duration;
    private final TextView play;
    private final TextView previous;
    private final TextView next;
    private final SeekBar seek;
    private final View openDetails;
    private boolean dragging;

    GlassPlayer(Context context, Supplier<PlaybackService> playback, boolean expanded, Runnable changeView) {
        super(context);
        this.playback = playback;
        this.expanded = expanded;
        setOrientation(VERTICAL);
        int side = dp(expanded ? 22 : 12);
        setPadding(side, dp(expanded ? 12 : 5), side, dp(expanded ? 20 : 6));
        if (!expanded) setBackground(surface(context));

        title = text(expanded ? 25 : 14, TEXT, true);
        title.setMaxLines(expanded ? 3 : 1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist = text(expanded ? 14 : 11, SUBTLE, false);
        artist.setMaxLines(expanded ? 3 : 1);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        elapsed = text(11, SUBTLE, false);
        duration = text(11, SUBTLE, false);

        previous = control("‹‹", "Previous track", false, () -> withPlayback(PlaybackService::previous));
        play = control("▶", "Play", true, () -> withPlayback(PlaybackService::toggle));
        next = control("››", "Next track", false, () -> withPlayback(PlaybackService::next));

        LinearLayout controls = horizontal();
        controls.setGravity(Gravity.CENTER);
        for (TextView button : new TextView[]{previous, play, next}) {
            int width = expanded ? (button == play ? 68 : 54) : 48;
            LayoutParams params = new LayoutParams(dp(width), dp(expanded ? 54 : 48));
            int gap = dp(expanded ? 6 : 2);
            params.setMargins(gap, 0, gap, 0);
            controls.addView(button, params);
        }

        seek = new SeekBar(context);
        seek.setProgressTintList(ColorStateList.valueOf(MINT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x667DAB93));
        seek.setThumbTintList(ColorStateList.valueOf(MINT));
        seek.setContentDescription("Playback position");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (!user) return;
                elapsed.setText(time(value));
                if (!dragging) withPlayback(service -> service.seek(value));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                withPlayback(service -> service.seek(bar.getProgress()));
            }
        });

        if (expanded) {
            openDetails = null;
            var heading = horizontal();
            var caption = text(11, MINT, true);
            caption.setText("NOW PLAYING");
            caption.setLetterSpacing(.12f);
            heading.addView(caption, weight());
            heading.addView(control("⌄", "Close now playing", false, changeView), new LayoutParams(dp(48), dp(48)));
            addView(heading);
            addArtwork();
            title.setGravity(Gravity.CENTER);
            artist.setGravity(Gravity.CENTER);
            addView(title, new LayoutParams(-1, -2));
            addView(artist, new LayoutParams(-1, -2));
            addView(seek, new LayoutParams(-1, dp(48)));
            var times = horizontal();
            times.addView(elapsed, weight());
            times.addView(duration);
            addView(times);
            var controlParams = new LayoutParams(-1, -2);
            controlParams.topMargin = dp(12);
            addView(controls, controlParams);
        } else {
            var row = horizontal();
            var info = new LinearLayout(context);
            info.setOrientation(VERTICAL);
            info.setMinimumHeight(dp(48));
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(dp(4), 0, 0, 0);
            info.addView(title);
            info.addView(artist);
            var details = horizontal();
            openDetails = details;
            details.setBackground(ripple(Color.TRANSPARENT, dp(10)));
            details.setOnClickListener(view -> changeView.run());
            details.setFocusable(true);
            details.addView(info, weight());
            var expand = text(16, MINT, true);
            expand.setText("↗");
            expand.setGravity(Gravity.CENTER);
            details.addView(expand, new LayoutParams(dp(24), -2));
            row.addView(details, weight());
            row.addView(controls);
            addView(row);
            addView(seek, new LayoutParams(-1, dp(32)));
        }
    }

    void render(PlaybackService.Now state) {
        boolean hasTrack = !state.file().isEmpty();
        title.setText(state.title().isEmpty() ? "Playback error" : state.title());
        if (openDetails != null) {
            openDetails.setContentDescription("Open now playing: " + title.getText());
        }
        String subtitle = state.error().isEmpty() ? state.artist() : state.error();
        if (!expanded && state.error().isEmpty()) {
            String times = time(state.position()) + " / " + time(state.duration());
            subtitle = subtitle.isEmpty() ? times : times + " · " + subtitle;
        }
        artist.setText(subtitle);
        play.setText(state.playing() ? "Ⅱ" : "▶");
        play.setContentDescription(state.playing() ? "Pause" : "Play");
        previous.setEnabled(hasTrack);
        play.setEnabled(hasTrack);
        next.setEnabled(hasTrack);
        seek.setEnabled(state.ready());
        duration.setText(time(state.duration()));
        if (!dragging) {
            elapsed.setText(time(state.position()));
            seek.setMax(Math.max(1, state.duration()));
            seek.setProgress(state.position());
        }
    }

    private void addArtwork() {
        var art = new FrameLayout(getContext());
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        var background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF34504A, 0xFF302746});
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), LINE);
        art.setBackground(background);
        for (int size : new int[]{88, 64, 40}) {
            var ring = new View(getContext());
            var circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(size == 40 ? 0x443EC09D : Color.TRANSPARENT);
            circle.setStroke(dp(1), 0x88A8D6B5);
            ring.setBackground(circle);
            art.addView(ring, new FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER));
        }
        var note = text(22, TEXT, true);
        note.setText("♪");
        note.setGravity(Gravity.CENTER);
        art.addView(note, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        var params = new LayoutParams(dp(116), dp(116));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(14);
        addView(art, params);
    }

    static GradientDrawable surface(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        var drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xEE294039, 0xF01D232C, 0xF0232035});
        drawable.setCornerRadius(22 * density);
        drawable.setStroke(Math.round(density), LINE);
        return drawable;
    }

    private void withPlayback(Consumer<PlaybackService> action) {
        PlaybackService service = playback.get();
        if (service != null) action.accept(service);
    }

    private TextView control(String glyph, String description, boolean primary, Runnable action) {
        var button = text(expanded ? 23 : 17, primary ? 0xFF172314 : TEXT, true);
        button.setText(glyph);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(ripple(primary ? MINT : 0x226CA88F, dp(16)));
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private RippleDrawable ripple(int fill, int radius) {
        var color = new GradientDrawable();
        color.setColor(fill);
        color.setCornerRadius(radius);
        var mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), color, mask);
    }

    private TextView text(int size, int color, boolean bold) {
        var view = new TextView(getContext());
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private LinearLayout horizontal() {
        var row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private static LayoutParams weight() { return new LayoutParams(0, -2, 1); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String time(int millis) {
        int seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
