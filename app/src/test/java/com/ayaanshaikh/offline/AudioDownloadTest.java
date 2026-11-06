package com.ayaanshaikh.offline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.Test;

public class AudioDownloadTest {
    private static final byte[] M4A_HEADER = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};

    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void onlyAcceptsHttpsOnTheMediaDomain() throws Exception {
        assertTrue(AudioDownload.isTrustedUrl(new URL("https://rr1.example.googlevideo.com/videoplayback")));
        assertFalse(AudioDownload.isTrustedUrl(new URL("https://googlevideo.com.example.org/videoplayback")));
        assertFalse(AudioDownload.isTrustedUrl(new URL("https://notgooglevideo.com/videoplayback")));
        assertFalse(AudioDownload.isTrustedUrl(new URL("http://rr1.googlevideo.com/videoplayback")));
        assertFalse(AudioDownload.isTrustedUrl(new URL("https://user@rr1.googlevideo.com/videoplayback")));
        assertFalse(AudioDownload.isTrustedUrl(new URL("https://rr1.googlevideo.com:8443/videoplayback")));
    }

    @Test public void rejectsHtmlTruncatedAndMismatchedContainers() throws Exception {
        byte[] m4a = M4A_HEADER;
        byte[] webm = {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 0, 0, 0, 0, 0, 0, 0, 0};
        assertArrayEquals(m4a, AudioDownload.readHeader(new ByteArrayInputStream(m4a), "track.m4a"));
        assertArrayEquals(webm, AudioDownload.readHeader(new ByteArrayInputStream(webm), "track.webm"));
        assertThrows(IOException.class, () -> AudioDownload.readHeader(new ByteArrayInputStream(m4a), "track.webm"));
        assertThrows(IOException.class, () -> AudioDownload.readHeader(new ByteArrayInputStream(new byte[3]), "track.m4a"));
        assertThrows(IOException.class, () -> AudioDownload.readHeader(new ByteArrayInputStream(
                "<html>Bad Gateway</html>".getBytes(StandardCharsets.UTF_8)), "track.m4a"));
    }

    @Test public void savesTheCompleteBodyAndReportsWrittenBytes() throws Exception {
        byte[] body = Arrays.copyOf(M4A_HEADER, 150_000);
        Arrays.fill(body, M4A_HEADER.length, body.length, (byte) 13);
        var connection = new FixtureConnection(body, body.length);
        File complete = new File(folder.getRoot(), "track.m4a");
        var progress = new ArrayList<Long>();
        String file = AudioDownload.save(connection, complete, () -> {}, (received, total) -> {
            assertEquals(body.length, total);
            assertFalse(complete.exists());
            progress.add(received);
        });

        assertEquals(complete.getName(), file);
        assertArrayEquals(body, Files.readAllBytes(complete.toPath()));
        assertEquals(Long.valueOf(0), progress.get(0));
        assertEquals(Long.valueOf(body.length), progress.get(progress.size() - 1));
        assertTrue(progress.size() > 3);
        assertFalse(new File(folder.getRoot(), "track.m4a.part").exists());
        assertTrue(connection.disconnected);
    }

    @Test public void truncatedResponsePreservesAnExistingFile() throws Exception {
        File complete = folder.newFile("track.m4a");
        Files.write(complete.toPath(), M4A_HEADER);
        var connection = new FixtureConnection(Arrays.copyOf(M4A_HEADER, 1024), 2048);
        assertThrows(IOException.class, () -> AudioDownload.save(connection, complete, () -> {}, (a, b) -> {}));
        assertArrayEquals(M4A_HEADER, Files.readAllBytes(complete.toPath()));
        assertFalse(new File(folder.getRoot(), "track.m4a.part").exists());
        assertTrue(connection.disconnected);
    }

    @Test public void networkLossRemovesPartialFileWithUnknownLength() throws Exception {
        File complete = new File(folder.getRoot(), "track.m4a");
        var connection = new FixtureConnection(Arrays.copyOf(M4A_HEADER, 1024), -1);
        var progress = new ArrayList<Long>();
        assertThrows(IllegalStateException.class, () -> AudioDownload.save(connection, complete, () -> {
            if (progress.size() > 1) throw new IllegalStateException("Connection lost");
        }, (received, total) -> {
            assertEquals(-1, total);
            progress.add(received);
        }));
        assertEquals(Long.valueOf(M4A_HEADER.length), progress.get(1));
        assertFalse(complete.exists());
        assertFalse(new File(folder.getRoot(), "track.m4a.part").exists());
        assertTrue(connection.disconnected);
    }

    @Test public void dnsErrorIncludesHostnameOnly() throws Exception {
        File complete = new File(folder.getRoot(), "track.m4a");
        var connection = new FixtureConnection(M4A_HEADER, M4A_HEADER.length) {
            @Override public InputStream getInputStream() throws IOException {
                throw new UnknownHostException("details from resolver");
            }
        };
        var failure = assertThrows(Http.DnsFailure.class,
                () -> AudioDownload.save(connection, complete, () -> {}, (a, b) -> {}));
        assertEquals("DNS lookup failed for rr1.googlevideo.com", failure.getMessage());
        assertFalse(complete.exists());
        assertTrue(connection.disconnected);
    }

    private static class FixtureConnection extends HttpURLConnection {
        private final byte[] body;
        private final long length;
        boolean disconnected;

        FixtureConnection(byte[] body, long length) throws IOException {
            super(new URL("https://rr1.googlevideo.com/videoplayback?signature=fixture"));
            this.body = body;
            this.length = length;
        }

        @Override public int getResponseCode() { return 200; }
        @Override public long getContentLengthLong() { return length; }
        @Override public String getContentType() { return "audio/mp4"; }
        @Override public InputStream getInputStream() throws IOException { return new ByteArrayInputStream(body); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
