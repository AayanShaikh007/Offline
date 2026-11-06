package com.ayaanshaikh.offline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class AudioDownloadTest {
    @Test public void onlyAcceptsHttpsOnTheMediaDomain() throws Exception {
        assertTrue(AudioDownload.isTrustedUrl(new URL("https://rr1.example.googlevideo.com/videoplayback")));
        assertFalse(AudioDownload.isTrustedUrl(new URL("https://googlevideo.com.example.org/videoplayback")));
        assertFalse(AudioDownload.isTrustedUrl(new URL("https://notgooglevideo.com/videoplayback")));
        assertFalse(AudioDownload.isTrustedUrl(new URL("http://rr1.googlevideo.com/videoplayback")));
        assertFalse(AudioDownload.isTrustedUrl(new URL("https://user@rr1.googlevideo.com/videoplayback")));
        assertFalse(AudioDownload.isTrustedUrl(new URL("https://rr1.googlevideo.com:8443/videoplayback")));
    }

    @Test public void rejectsHtmlTruncatedAndMismatchedContainers() throws Exception {
        byte[] m4a = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
        byte[] webm = {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 0, 0, 0, 0, 0, 0, 0, 0};
        assertArrayEquals(m4a, AudioDownload.readHeader(new ByteArrayInputStream(m4a), "track.m4a"));
        assertArrayEquals(webm, AudioDownload.readHeader(new ByteArrayInputStream(webm), "track.webm"));
        assertThrows(IOException.class, () -> AudioDownload.readHeader(new ByteArrayInputStream(m4a), "track.webm"));
        assertThrows(IOException.class, () -> AudioDownload.readHeader(new ByteArrayInputStream(new byte[3]), "track.m4a"));
        assertThrows(IOException.class, () -> AudioDownload.readHeader(new ByteArrayInputStream(
                "<html>Bad Gateway</html>".getBytes(StandardCharsets.UTF_8)), "track.m4a"));
    }
}
