package com.ayaanshaikh.offline;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AudioRedirectTest {
    private static final String START = "https://rr1.googlevideo.com/start?signature=private";

    @Test public void followsSupportedRedirectsWithoutAutomaticallyFollowingThem() throws Exception {
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            var redirect = new Response(START, status, "https://rr2.googlevideo.com/audio");
            var audio = new Response("https://rr2.googlevideo.com/audio", 200, null);
            var requests = new Script(redirect, audio);
            assertSame(audio, AudioDownload.open(START, requests));
            assertEquals(List.of(START, "https://rr2.googlevideo.com/audio"), requests.visited);
            assertFalse(redirect.getInstanceFollowRedirects());
            assertFalse(audio.getInstanceFollowRedirects());
            assertTrue(redirect.disconnected);
            assertFalse(audio.disconnected);
            audio.disconnect();
        }
    }

    @Test public void resolvesRelativeLocationsAndAllowsFourRedirects() throws Exception {
        var first = new Response(START, 302, "/second");
        var second = new Response("https://rr1.googlevideo.com/second", 307, "third");
        var third = new Response("https://rr1.googlevideo.com/third", 308, "./fourth?part=4");
        var fourth = new Response("https://rr1.googlevideo.com/fourth?part=4", 301, "//rr2.googlevideo.com/final");
        var audio = new Response("https://rr2.googlevideo.com/final", 200, null);
        var requests = new Script(first, second, third, fourth, audio);
        assertSame(audio, AudioDownload.open(START, requests));
        assertEquals(5, requests.visited.size());
        for (Response connection : new Response[]{first, second, third, fourth}) assertTrue(connection.disconnected);
        assertFalse(audio.disconnected);
        audio.disconnect();
    }

    @Test public void rejectsUntrustedDestinationsBeforeOpeningAnotherConnection() throws Exception {
        for (String location : new String[]{
                "https://example.org/audio?signature=private",
                "https://rr1.googlevideo.com.evil.example/audio",
                "http://rr2.googlevideo.com/audio",
                "https://user:password@rr2.googlevideo.com/audio",
                "https://rr2.googlevideo.com:8443/audio"}) {
            var redirect = new Response(START, 302, location);
            var requests = new Script(redirect);
            var error = assertThrows(IOException.class, () -> AudioDownload.open(START, requests));
            assertEquals("Untrusted audio server", error.getMessage());
            assertEquals(List.of(START), requests.visited);
            assertTrue(redirect.disconnected);
        }
    }

    @Test public void rejectsAnUntrustedInitialAddressWithoutAnyRequests() {
        var requests = new Script();
        assertThrows(IOException.class, () -> AudioDownload.open("https://example.org/audio", requests));
        assertTrue(requests.visited.isEmpty());
    }

    @Test public void aRedirectLoopStopsAfterTheBoundedNumberOfRequests() throws Exception {
        var responses = new Response[5];
        for (int i = 0; i < responses.length; i++) responses[i] = new Response(START, 302, START);
        var requests = new Script(responses);
        assertEquals("Invalid audio redirect",
                assertThrows(IOException.class, () -> AudioDownload.open(START, requests)).getMessage());
        assertEquals(5, requests.visited.size());
        for (Response response : responses) assertTrue(response.disconnected);
    }

    @Test public void missingBlankAndMalformedLocationsCloseTheRedirectConnection() throws Exception {
        for (String location : new String[]{null, "", "  ", "https://[invalid"}) {
            var redirect = new Response(START, 302, location);
            var requests = new Script(redirect);
            assertThrows(IOException.class, () -> AudioDownload.open(START, requests));
            assertEquals(List.of(START), requests.visited);
            assertTrue(redirect.disconnected);
        }
    }

    @Test public void networkFailureClosesTheCurrentConnection() throws Exception {
        var response = new Response(START, 200, null);
        var timeout = new SocketTimeoutException("Timed out");
        response.failure = timeout;
        assertSame(timeout, assertThrows(SocketTimeoutException.class,
                () -> AudioDownload.open(START, new Script(response))));
        assertTrue(response.disconnected);
    }

    @Test public void dnsFailuresAfterRedirectOnlyReportTheFailedHost() throws Exception {
        var redirect = new Response(START, 302, "https://rr2.googlevideo.com/audio?signature=private");
        var failed = new Response("https://rr2.googlevideo.com/audio?signature=private", 200, null);
        failed.failure = new UnknownHostException("resolver details");
        var requests = new Script(redirect, failed);
        var error = assertThrows(Http.DnsFailure.class, () -> AudioDownload.open(START, requests));
        assertEquals("DNS lookup failed for rr2.googlevideo.com", error.getMessage());
        assertTrue(redirect.disconnected);
        assertTrue(failed.disconnected);
    }

    @Test public void nonRedirectErrorsAreLeftForTheTransferToHandle() throws Exception {
        var response = new Response(START, 403, null);
        assertSame(response, AudioDownload.open(START, new Script(response)));
        assertFalse(response.disconnected);
        response.disconnect();
    }

    @Test public void dnsFailureWhileOpeningAConnectionIsSanitizedAndKeepsItsCause() {
        var cause = new UnknownHostException("resolver details");
        var error = assertThrows(Http.DnsFailure.class,
                () -> AudioDownload.open(START, url -> { throw cause; }));
        assertEquals("DNS lookup failed for rr1.googlevideo.com", error.getMessage());
        assertSame(cause, error.getCause());
    }

    @Test public void anAlreadyCancelledRequestDoesNotOpenAConnection() throws Exception {
        var response = new Response(START, 200, null);
        var requests = new Script(response);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> AudioDownload.open(START, requests));
            assertTrue(requests.visited.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void cancellationDuringAResponseClosesItInsteadOfFollowingOrHandingItOff() throws Exception {
        for (int status : new int[]{302, 200}) {
            var response = new Response(START, status, "https://rr2.googlevideo.com/audio");
            response.interrupt = true;
            var requests = new Script(response);
            try {
                assertThrows(InterruptedIOException.class, () -> AudioDownload.open(START, requests));
                assertEquals(List.of(START), requests.visited);
                assertTrue(response.disconnected);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    private static final class Script implements AudioDownload.ConnectionFactory {
        final List<String> visited = new ArrayList<>();
        final List<Response> responses;
        Script(Response... responses) { this.responses = List.of(responses); }
        @Override public HttpURLConnection open(URL url) {
            visited.add(url.toExternalForm());
            assertTrue("Unexpected extra request: " + url.getHost(), visited.size() <= responses.size());
            Response response = responses.get(visited.size() - 1);
            assertEquals(response.getURL().toExternalForm(), url.toExternalForm());
            return response;
        }
    }

    private static final class Response extends HttpURLConnection {
        final int status;
        final String location;
        boolean disconnected;
        boolean interrupt;
        IOException failure;
        Response(String address, int status, String location) throws IOException {
            super(new URL(address));
            this.status = status;
            this.location = location;
        }
        @Override public int getResponseCode() throws IOException {
            if (failure != null) throw failure;
            if (interrupt) Thread.currentThread().interrupt();
            return status;
        }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
