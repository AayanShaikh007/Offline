package com.ayaanshaikh.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public class PlaylistLinkTest {
    private static final String PLAYLIST = "PL0123456789abc";
    private static final String SPOTIFY = "1a2b3c4d5e6f7g8h9i0j12";

    @Test public void publicYouTubeAndMusicLinksResolveToSamePlaylist() {
        var link = PlaylistLink.parse(" https://music.youtube.com/playlist?si=shared&list=" + PLAYLIST + " ");
        assertEquals(PlaylistLink.YOUTUBE, link.source());
        assertEquals("https://www.youtube.com/playlist?list=" + PLAYLIST, link.url());
        assertEquals(link, PlaylistLink.parse("https://www.youtube.com/watch?v=abcdefghijk&list=" + PLAYLIST));
    }

    @Test public void spotifyPlaylistLinksIgnoreShareAndLocaleParameters() {
        var link = PlaylistLink.parse("https://open.spotify.com/intl-en/playlist/" + SPOTIFY + "?si=xyz");
        assertEquals(PlaylistLink.SPOTIFY, link.source());
        assertEquals(SPOTIFY, link.id());
        assertEquals("https://open.spotify.com/playlist/" + SPOTIFY, link.url());
    }

    @Test public void unsupportedAndDeceptiveLinksAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaylistLink.parse("https://youtube.com.example.org/playlist?list=" + PLAYLIST));
        assertThrows(IllegalArgumentException.class,
                () -> PlaylistLink.parse("https://youtube.com@evil.example/playlist?list=" + PLAYLIST));
        assertThrows(IllegalArgumentException.class,
                () -> PlaylistLink.parse("https://www.youtube.com/watch?v=abcdefghijk"));
        assertThrows(IllegalArgumentException.class,
                () -> PlaylistLink.parse("https://www.youtube.com/playlist?list=RD123456789"));
    }

    @Test public void matchLinksExtractOnlyValidVideoIds() {
        assertEquals("abcdefghijk", PlaylistLink.videoId("https://youtu.be/abcdefghijk?si=xyz"));
        assertEquals("abcdefghijk", PlaylistLink.videoId("https://www.youtube.com/watch?v=abcdefghijk&list=" + PLAYLIST));
        assertNull(PlaylistLink.videoId("https://example.com/watch?v=abcdefghijk"));
        assertNull(PlaylistLink.videoId("https://youtu.be/"));
    }
}
