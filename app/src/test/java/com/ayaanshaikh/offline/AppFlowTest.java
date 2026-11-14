package com.ayaanshaikh.offline;

import static org.junit.Assert.*;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class AppFlowTest {
    @Before public void reset() throws Exception {
        var field = OfflineStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
    @After public void cleanup() { OfflineStore.get(RuntimeEnvironment.getApplication()).close(); }
    private TextView find(View root, String text) {
        if (root instanceof TextView label && text.contentEquals(label.getText())) return label;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
    @Test public void emptyAppNavigatesAcrossAllTabsAndRestoresSettings() {
        var service = Robolectric.buildService(PlaybackService.class).create();
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .setComponentNameAndServiceForBindService(
                        new android.content.ComponentName(RuntimeEnvironment.getApplication(), PlaybackService.class),
                        service.get().onBind(new Intent()));
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            var app = controller.get();
            View root = app.getWindow().getDecorView();
            assertNotNull(find(root, "[ No sources linked ]"));
            find(root, "02 / vault").performClick();
            assertNotNull(find(root, "[ No local audio yet ]"));
            find(root, "03 / logs").performClick();
            assertNotNull(find(root, "[ No events yet ]"));
            find(root, "⚙").performClick();
            assertNotNull(find(root, "Connect Spotify"));
            controller.recreate();
            assertNotNull(find(controller.get().getWindow().getDecorView(), "Connect Spotify"));
        } finally { service.destroy(); }
    }
    @Test public void spotifyPkceIsUniqueAndRejectsForgedAndExpiredCallbacks() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        var spotify = new SpotifySource(context);
        assertThrows(java.io.IOException.class, () -> spotify.authorize("bad"));
        Uri first = spotify.authorize("0123456789abcdef0123456789abcdef");
        Uri second = spotify.authorize("0123456789abcdef0123456789abcdef");
        assertEquals("S256", second.getQueryParameter("code_challenge_method"));
        assertEquals(SpotifySource.REDIRECT, second.getQueryParameter("redirect_uri"));
        assertNotEquals(first.getQueryParameter("state"), second.getQueryParameter("state"));
        assertNotEquals(first.getQueryParameter("code_challenge"), second.getQueryParameter("code_challenge"));
        assertThrows(java.io.IOException.class, () -> spotify.complete(Uri.parse(SpotifySource.REDIRECT + "?state=forged&code=x")));
        assertThrows(java.io.IOException.class, () -> spotify.complete(Uri.parse("https://evil.example/callback")));
        context.getSharedPreferences("spotify", 0).edit().putLong("started", 1).commit();
        assertThrows(java.io.IOException.class, () -> spotify.complete(Uri.parse(SpotifySource.REDIRECT + "?state=" + second.getQueryParameter("state") + "&code=x")));
        assertFalse(spotify.connected());
    }
    @Test public void playbackMissingFileReportsActionableError() {
        var controller = Robolectric.buildService(PlaybackService.class).create();
        try {
            var service = controller.get();
            service.onStartCommand(new Intent(service, PlaybackService.class)
                    .setAction(PlaybackService.PLAY).putExtra(PlaybackService.FILE, "missing.m4a"), 0, 1);
            assertFalse(service.now().ready());
            assertTrue(service.now().error().contains("unavailable"));
            service.next();
            service.previous();
            service.seek(-1);
            service.toggle();
            assertFalse(service.now().playing());
            service.onStartCommand(new Intent(service, PlaybackService.class).setAction("offline.STOP"), 0, 2);
            assertEquals("", service.now().error());
        } finally { controller.destroy(); }
    }
}
