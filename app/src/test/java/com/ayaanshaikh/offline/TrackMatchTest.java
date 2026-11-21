package com.ayaanshaikh.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TrackMatchTest {
    @Test public void incompleteExtractorMetadataDoesNotCrashMatching() {
        assertEquals(0, TrackMatch.score("Song", "Artist", 120, null, "Artist", 120));
        assertEquals(0, TrackMatch.score(null, "Artist", 120, "Song", null, 120));
        assertEquals(0, TrackMatch.score("Song", null, 120, "Song", null, 120));
    }

    @Test public void durationToleranceIncludesBoundaryButRejectsUnknownDurations() {
        assertTrue(TrackMatch.score("Song", "Artist", 120, "Song", "Artist", 128) > 0);
        assertEquals(0, TrackMatch.score("Song", "Artist", 120, "Song", "Artist", 129));
        assertEquals(0, TrackMatch.score("Song", "Artist", 0, "Song", "Artist", 120));
    }
    @Test public void prefersExactSongOnMatchingArtistChannel() {
        int topic = TrackMatch.score("Déjà Vu", "Artist", 201, "Deja Vu", "Artist - Topic", 201);
        int video = TrackMatch.score("Déjà Vu", "Artist", 201, "Artist – Déjà Vu (Official Audio)", "Artist", 202);
        assertTrue(topic > video);
        assertTrue(video > 0);
    }

    @Test public void ignoresWrongArtistAndAlternateEditions() {
        assertEquals(0, TrackMatch.score("Pulse", "Artist", 201, "Pulse", "Other Artist - Topic", 201));
        assertEquals(0, TrackMatch.score("Pulse", "Artist", 201, "Artist - Pulse (Live)", "Artist", 201));
        assertEquals(0, TrackMatch.score("Pulse", "Artist", 201, "Artist - Pulse", "Artist", 245));
    }

    @Test public void acceptsEditionExplicitlyNamedByThePlaylist() {
        assertTrue(TrackMatch.score("Pulse (Live)", "Artist", 201,
                "Artist - Pulse (Live) [Official Audio]", "Artist", 203) > 0);
    }
}
