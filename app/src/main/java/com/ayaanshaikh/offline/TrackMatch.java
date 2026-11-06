package com.ayaanshaikh.offline;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class TrackMatch {
    private static final Pattern VIDEO_LABEL = Pattern.compile(
            "\\s*[\\[(](?:(?:official|music|lyric)\\s+)*(?:audio|video|lyrics|visualizer)[\\])]\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EDITION = Pattern.compile(
            "\\b(cover|karaoke|instrumental|sped up|slowed|nightcore|remix|live|acoustic|demo)\\b");

    static int score(String title, String artist, long seconds, String videoTitle, String uploader, long duration) {
        if (seconds <= 0 || duration <= 0 || Math.abs(seconds - duration) > 8) return 0;
        String name = clean(title);
        String performer = clean(artist);
        String candidate = clean(VIDEO_LABEL.matcher(videoTitle).replaceAll(" "));
        String channel = clean(uploader).replaceFirst(" topic$", "").replaceFirst(" vevo$", "");
        if (name.isEmpty() || performer.isEmpty()) return 0;
        if (candidate.equals(name) && channel.equals(performer)) return 110;
        String prefix = performer + " ";
        String suffix = " " + performer;
        if (!candidate.equals(prefix + name) && !candidate.equals(name + suffix)) return 0;
        var editions = EDITION.matcher(candidate);
        while (editions.find()) {
            if (!(" " + name + " ").contains(" " + editions.group() + " ")
                    && !(" " + performer + " ").contains(" " + editions.group() + " ")) return 0;
        }
        return 100;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
