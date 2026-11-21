package com.ayaanshaikh.offline;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real loopback sockets. Test routing/trust is per connection; production policy is unchanged. */
public class AudioTransportTest {
    private static final String MEDIA = "https://rr1.googlevideo.com/videoplayback?signature=fixture";
    private static final byte[] BODY = Arrays.copyOf(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}, 4096);
    private static final char[] PASSWORD = "test-only-password".toCharArray();
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File complete() { return new File(folder.getRoot(), "track.m4a"); }
    private File partial() { return new File(folder.getRoot(), "track.m4a.part"); }
    private void absent() { assertFalse(complete().exists()); assertFalse(partial().exists()); }

    private String save(HttpURLConnection connection) throws IOException {
        return AudioDownload.save(connection, complete(), () -> {}, (a, b) -> {});
    }

    @Test public void fixedLengthResponsePublishesExactBytes() throws Exception {
        try (var server = new Server(false, false, (socket, release) -> fixed(socket, BODY, BODY.length))) {
            assertEquals("track.m4a", save(server.connection(false, false, 5000)));
            assertArrayEquals(BODY, Files.readAllBytes(complete().toPath()));
            assertFalse(partial().exists());
        }
    }

    @Test public void chunkedResponseReportsUnknownLengthAndPublishes() throws Exception {
        try (var server = new Server(false, false, (socket, release) -> {
            headers(socket, "Transfer-Encoding: chunked\r\n");
            socket.getOutputStream().write((Integer.toHexString(BODY.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(BODY);
            socket.getOutputStream().write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        })) {
            var updates = new java.util.ArrayList<Long>();
            AudioDownload.save(server.connection(false, false, 5000), complete(), () -> {}, (received, total) -> {
                assertEquals(-1, total);
                assertFalse(complete().exists());
                updates.add(received);
            });
            assertEquals(Long.valueOf(BODY.length), updates.get(updates.size() - 1));
            assertArrayEquals(BODY, Files.readAllBytes(complete().toPath()));
            assertFalse(partial().exists());
        }
    }

    @Test public void prematureSocketClosePreservesAnExistingCompleteFile() throws Exception {
        byte[] previous = Arrays.copyOf(BODY, 24);
        Files.write(complete().toPath(), previous);
        try (var server = new Server(false, false, (socket, release) -> fixed(socket, BODY, BODY.length * 2))) {
            assertThrows(IOException.class, () -> save(server.connection(false, false, 5000)));
            assertArrayEquals(previous, Files.readAllBytes(complete().toPath()));
            assertFalse(partial().exists());
        }
    }

    @Test public void stalledBodyTimesOutCleansUpAndCanBeRetried() throws Exception {
        try (var server = new Server(false, false, (socket, release) -> {
            headers(socket, "Content-Length: " + BODY.length + "\r\n");
            socket.getOutputStream().write(BODY, 0, 12);
            socket.getOutputStream().flush();
            assertTrue("Fixture was not released", release.await(15, TimeUnit.SECONDS));
        })) {
            assertThrows(SocketTimeoutException.class, () -> save(server.connection(false, false, 250)));
            absent();
        }
        try (var server = new Server(false, false, (socket, release) -> fixed(socket, BODY, BODY.length))) {
            save(server.connection(false, false, 5000));
            assertArrayEquals(BODY, Files.readAllBytes(complete().toPath()));
            assertFalse(partial().exists());
        }
    }

    @Test public void eligibilityLossAfterHeaderAbortsARealResponse() throws Exception {
        var eligible = new AtomicBoolean(true);
        try (var server = new Server(false, false, (socket, release) -> fixed(socket, BODY, BODY.length))) {
            assertThrows(Stopped.class, () -> AudioDownload.save(server.connection(false, false, 5000), complete(), () -> {
                if (!eligible.get()) throw new Stopped();
            }, (received, total) -> {
                if (received >= 12) eligible.set(false);
            }));
            absent();
        }
    }

    @Test public void missingFinalChunkIsNotPublished() throws Exception {
        try (var server = new Server(false, false, (socket, release) -> {
            headers(socket, "Transfer-Encoding: chunked\r\n");
            socket.getOutputStream().write("1000\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(BODY);
            socket.getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        })) {
            assertThrows(IOException.class, () -> save(server.connection(false, false, 5000)));
            absent();
        }
    }

    @Test public void aShortHeaderFromTheSocketIsRejected() throws Exception {
        try (var server = new Server(false, false, (socket, release) -> fixed(socket, new byte[5], 5))) {
            assertThrows(IOException.class, () -> save(server.connection(false, false, 5000)));
            absent();
        }
    }

    @Test public void trustedLoopbackTlsWithMatchingHostnamePublishes() throws Exception {
        try (var server = new Server(true, false, (socket, release) -> fixed(socket, BODY, BODY.length))) {
            save(server.connection(true, false, 5000));
            assertArrayEquals(BODY, Files.readAllBytes(complete().toPath()));
        }
    }

    @Test public void defaultTrustRejectsTheFixtureCertificate() throws Exception {
        try (var server = new Server(true, true, (socket, release) -> fixed(socket, BODY, BODY.length))) {
            var failure = assertThrows(SSLHandshakeException.class, () -> server.connection(false, false, 5000));
            assertTrue("Expected certificate path rejection", failure.getMessage().contains("PKIX"));
            absent();
        }
    }

    @Test public void trustingTheCertificateDoesNotBypassHostnameVerification() throws Exception {
        try (var server = new Server(true, true, (socket, release) -> fixed(socket, BODY, BODY.length))) {
            // JSSE endpoint identification and HttpsURLConnection's fallback verifier use
            // different IOException subclasses, but both must identify the rejected host.
            var failure = assertThrows(IOException.class, () -> server.connection(true, true, 5000));
            String message = failure.getMessage().toLowerCase(java.util.Locale.ROOT);
            assertTrue("Expected hostname rejection: " + failure, message.contains("127.0.0.1"));
            assertTrue("Expected hostname rejection: " + failure,
                    message.contains("hostname wrong") || message.contains("subject alternative"));
            absent();
        }
    }

    private static void headers(Socket socket, String framing) throws IOException {
        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: audio/mp4\r\nConnection: close\r\n"
                + framing + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static void fixed(Socket socket, byte[] body, int declaredLength) throws IOException {
        headers(socket, "Content-Length: " + declaredLength + "\r\n");
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private static SSLContext tls(boolean trustFixture) throws Exception {
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = AudioTransportTest.class.getResourceAsStream("/loopback-test.p12")) {
            assertNotNull("Missing public loopback fixture", input);
            keys.load(input, PASSWORD);
        }
        // Robolectric installs Conscrypt globally; this fixture deliberately tests JDK SunJSSE.
        var km = KeyManagerFactory.getInstance("SunX509", "SunJSSE");
        km.init(keys, PASSWORD);
        var tm = TrustManagerFactory.getInstance("PKIX", "SunJSSE");
        tm.init(trustFixture ? keys : null);
        var context = SSLContext.getInstance("TLS", "SunJSSE");
        context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        return context;
    }

    private interface Response { void send(Socket socket, CountDownLatch release) throws Exception; }

    private static final class Server implements AutoCloseable {
        private final ServerSocket listener;
        private final boolean secure;
        private final boolean rejectedHandshake;
        private final CountDownLatch release = new CountDownLatch(1);
        private final FutureTask<Void> task;
        private volatile Socket client;

        Server(boolean secure, boolean rejectedHandshake, Response response) throws Exception {
            this.secure = secure;
            this.rejectedHandshake = rejectedHandshake;
            var address = InetAddress.getByName("127.0.0.1");
            listener = secure ? tls(true).getServerSocketFactory().createServerSocket(0, 1, address)
                    : new ServerSocket(0, 1, address);
            listener.setSoTimeout(5000);
            task = new FutureTask<>(() -> {
                try (Socket socket = listener.accept()) {
                    client = socket;
                    socket.setSoTimeout(5000);
                    var request = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = request.readLine();
                    if (first == null) throw new java.io.EOFException("Client closed before GET");
                    assertTrue("Expected GET", first.startsWith("GET "));
                    String line;
                    while ((line = request.readLine()) != null && !line.isEmpty()) {}
                    response.send(socket, release);
                }
                return null;
            });
            var thread = new Thread(task, "offline-loopback-response");
            thread.setDaemon(true);
            thread.start();
        }

        HttpURLConnection connection(boolean trustFixture, boolean wrongHost, int readTimeout) throws Exception {
            // Only the existing test injection seam maps an already validated media URL to loopback.
            return AudioDownload.open(MEDIA, logical -> {
                var endpoint = new URL((secure ? "https" : "http") + "://"
                        + (secure && !wrongHost ? "localhost" : "127.0.0.1") + ":" + listener.getLocalPort() + "/audio");
                var conn = (HttpURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(readTimeout);
                if (secure) {
                    try { ((HttpsURLConnection) conn).setSSLSocketFactory(tls(trustFixture).getSocketFactory()); }
                    catch (Exception e) { throw new IOException(e); }
                }
                return conn;
            });
        }

        @Override public void close() throws Exception {
            release.countDown();
            listener.close();
            try { task.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException e) {
                if (!(rejectedHandshake && e.getCause() instanceof IOException)) throw e;
            } finally {
                if (client != null) client.close();
            }
        }
    }

    private static final class Stopped extends RuntimeException {}
}
