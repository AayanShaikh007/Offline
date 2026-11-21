package com.ayaanshaikh.offline;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HttpTest {
    @Test public void readsUtf8AndClosesStreams() throws Exception {
        boolean[] closed = {false};
        var stream = new ByteArrayInputStream("Beyoncé 日本語".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException { closed[0] = true; super.close(); }
        };
        assertEquals("Beyoncé 日本語", Http.read(stream));
        assertTrue(closed[0]);
        assertEquals("", Http.read(null));
    }
    @Test public void refusesOversizedResponsesAndClosesStream() {
        boolean[] closed = {false};
        InputStream stream = new InputStream() {
            @Override public int read() { return 'a'; }
            @Override public int read(byte[] bytes) { java.util.Arrays.fill(bytes, (byte) 'a'); return bytes.length; }
            @Override public void close() { closed[0] = true; }
        };
        assertThrows(IOException.class, () -> Http.read(stream));
        assertTrue(closed[0]);
    }
    @Test public void interruptedReadStopsWithoutClearingCancellation() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> Http.read(new ByteArrayInputStream(new byte[10])));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test public void refusesCleartextBeforeOpeningConnection() {
        assertThrows(IOException.class, () -> Http.open("http://example.com"));
    }
}
