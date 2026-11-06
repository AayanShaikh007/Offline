package com.ayaanshaikh.offline;

import java.net.URI;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;

record PlaylistLink(String source, String id) {
    static final String YOUTUBE = "youtube";
    static final String SPOTIFY = "spotify";

    static PlaylistLink parse(String input) {
        try {
            URI uri = URI.create(input.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getUserInfo() != null || uri.getPort() != -1) {
                throw new IllegalArgumentException();
            }
            if (host.equalsIgnoreCase("open.spotify.com")) {
                var match = java.util.regex.Pattern
                        .compile("/(?:intl-[a-zA-Z-]+/)?playlist/([a-zA-Z0-9]{22})/?")
                        .matcher(uri.getPath());
                if (match.matches()) return new PlaylistLink(SPOTIFY, match.group(1));
            } else if (isYouTube(host) && uri.getRawQuery() != null) {
                for (String field : uri.getRawQuery().split("&")) {
                    if (!field.startsWith("list=")) continue;
                    String id = URLDecoder.decode(field.substring(5), "UTF-8");
                    if (id.matches("[a-zA-Z0-9_-]{8,128}") && !id.startsWith("RD")) {
                        return new PlaylistLink(YOUTUBE, id);
                    }
                }
            }
        } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
            // Use one actionable message for malformed and unsupported links.
        }
        throw new IllegalArgumentException("Paste a Spotify or public YouTube playlist URL.");
    }

    static String videoId(String input) {
        try {
            if (input.matches("[A-Za-z0-9_-]{11}")) return input;
            URI uri = URI.create(input.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() != -1) return null;
            String id = null;
            if (uri.getHost().equalsIgnoreCase("youtu.be")) {
                id = uri.getPath().substring(1);
            } else if (isYouTube(uri.getHost()) && "/watch".equals(uri.getPath())
                    && uri.getRawQuery() != null) {
                for (String field : uri.getRawQuery().split("&")) {
                    if (field.startsWith("v=")) id = field.substring(2);
                }
            }
            return id != null && id.matches("[A-Za-z0-9_-]{11}") ? id : null;
        } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
            return null;
        }
    }

    private static boolean isYouTube(String host) {
        return switch (host.toLowerCase(java.util.Locale.ROOT)) {
            case "www.youtube.com", "music.youtube.com", "m.youtube.com", "youtube.com", "youtu.be" -> true;
            default -> false;
        };
    }

    String key() { return source + "/" + id; }
    String url() {
        return YOUTUBE.equals(source) ? "https://www.youtube.com/playlist?list=" + id
                : "https://open.spotify.com/playlist/" + id;
    }
}
