package com.ayaanshaikh.offline;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;

/** Real Android TLS/body reads; all endpoints and trust overrides stay in this test APK. */
final class NativeAudioTransport {
    private static final String MEDIA = "https://rr1.googlevideo.com/videoplayback?signature=native-fixture";
    private static final byte[] BODY = Arrays.copyOf(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}, 4096);
    private final Instrumentation owner;
    private final File complete;
    private final File partial;
    private final SSLContext fixtureTls;
    private final SSLContext defaultTls;

    NativeAudioTransport(Instrumentation owner) throws Exception {
        this.owner = owner;
        var directory = Files.createTempDirectory(owner.getTargetContext().getCacheDir().toPath(), "native-transport-");
        complete = directory.resolve("fixture.m4a").toFile();
        partial = directory.resolve("fixture.m4a.part").toFile();
        // Prepare test credentials before starting peers or timing body-read readiness.
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = owner.getContext().getAssets().open("loopback-test.p12")) {
            keys.load(input, "test-only-password".toCharArray());
        }
        fixtureTls = createTls(keys, true);
        defaultTls = createTls(keys, false);
    }

    void run() throws Exception {
        require(!new SpotifySource(owner.getTargetContext()).connected(), "Disconnect Spotify on the test VM");
        awaitWifi(true);
        try (var server = new Server(false, false)) {
            save(server.connection(true, false, 15000), () -> {}, (a, b) -> {});
            exact(BODY);
        }
        signal("TLS_SAVE_PASS");
        try (var server = new Server(false, true)) {
            try {
                server.connection(false, false, 15000);
                throw new AssertionError("Default trust accepted the public fixture");
            } catch (SSLHandshakeException expected) { /* Expected certificate rejection. */ }
        }
        signal("TLS_TRUST_REJECTION_PASS");
        try (var server = new Server(false, true)) {
            try {
                server.connection(true, true, 15000);
                throw new AssertionError("Wrong hostname accepted");
            } catch (IOException expected) {
                String message = expected.toString().toLowerCase(java.util.Locale.ROOT);
                require(message.contains("127.0.0.1") && (message.contains("hostname") || message.contains("subject alternative")),
                        "Expected specific hostname rejection: " + expected);
            }
        }
        signal("TLS_HOSTNAME_REJECTION_PASS");

        byte[] previous = Arrays.copyOf(BODY, 24);
        Files.write(complete.toPath(), previous);
        try (var server = new Server(true, false)) {
            var header = new CountDownLatch(1);
            var task = download(server, 15000, header);
            awaitHeader(header, task);
            signal("BODY_WAITING_FOR_WIFI_LOSS");
            awaitWifi(false);
            require(!task.isDone(), "Transfer ended before Wi-Fi loss was observed");
            server.release.countDown();
            Throwable failure = failure(task);
            require(failure instanceof Stopped, "Expected eligibility cancellation: " + failure);
            exact(previous);
        }
        signal("WIFI_LOSS_PASS_RECONNECT");
        awaitWifi(true);
        try (var server = new Server(false, false)) {
            save(server.connection(true, false, 15000), this::checkNetwork, (a, b) -> {});
            exact(BODY);
        }
        signal("WIFI_RETRY_PASS");

        Files.write(complete.toPath(), previous);
        try (var server = new Server(true, false)) {
            var header = new CountDownLatch(1);
            var task = new FutureTask<Void>(() -> {
                save(server.connection(true, false, 5000), this::checkNetwork, (received, total) -> {
                    if (received >= 12) header.countDown();
                });
                return null;
            });
            var thread = new Thread(task, "native-interrupted-download");
            thread.start();
            awaitHeader(header, task);
            awaitBlockedRead(thread, task);
            thread.interrupt();
            Throwable failure = failure(task);
            thread.join(1000);
            require(!thread.isAlive(), "Interrupted reader remains active");
            require(failure instanceof IOException || failure instanceof Stopped, "Unexpected interrupted read: " + failure);
            exact(previous);
            signal("ACTIVE_INTERRUPT_PASS " + failure.getClass().getSimpleName());
        }
        signal("TRANSPORT_COMPLETE");
    }

    private FutureTask<Void> download(Server server, int timeout, CountDownLatch header) {
        var task = new FutureTask<Void>(() -> {
            save(server.connection(true, false, timeout), this::checkNetwork, (received, total) -> {
                if (received >= 12) header.countDown();
            });
            return null;
        });
        var thread = new Thread(task, "native-network-loss-download");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private Throwable failure(FutureTask<Void> task) throws Exception {
        try {
            task.get(20, TimeUnit.SECONDS);
            throw new AssertionError("Cancelled transfer succeeded");
        } catch (ExecutionException expected) { return expected.getCause(); }
    }

    private void awaitHeader(CountDownLatch header, FutureTask<Void> task) throws Exception {
        long began = SystemClock.elapsedRealtime();
        boolean reported = false;
        while (!header.await(1, TimeUnit.SECONDS)) {
            if (task.isDone()) throw new AssertionError("Reader ended before header", failure(task));
            long elapsed = SystemClock.elapsedRealtime() - began;
            if (!reported && elapsed >= 30000) {
                var details = new StringBuilder("HEADER_DELAY_DIAGNOSTIC\n");
                for (var entry : Thread.getAllStackTraces().entrySet()) {
                    if (!entry.getKey().getName().startsWith("native-")) continue;
                    details.append(entry.getKey().getName()).append(' ').append(entry.getKey().getState()).append('\n');
                    for (var frame : entry.getValue()) details.append("  ").append(frame).append('\n');
                }
                signal(details.toString());
                reported = true;
            }
            require(elapsed < 90000, "Timed out waiting for header after diagnostic capture");
        }
    }

    private void awaitBlockedRead(Thread thread, FutureTask<Void> task) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (!task.isDone() && SystemClock.elapsedRealtime() < end) {
            for (var frame : thread.getStackTrace()) {
                String type = frame.getClassName();
                if (frame.getMethodName().toLowerCase(java.util.Locale.ROOT).contains("read")
                        && (type.contains("Socket") || type.contains("NativeCrypto") || type.contains("IoBridge"))) {
                    signal("READER_BLOCKED " + type + "." + frame.getMethodName());
                    return;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Reader did not reach a socket read before interruption");
    }

    private void save(HttpURLConnection connection, Runnable check, AudioDownload.Progress progress) throws IOException {
        AudioDownload.save(connection, complete, check, progress);
    }

    private void checkNetwork() {
        if (Thread.currentThread().isInterrupted() || !SyncJob.wifi(owner.getTargetContext())) throw new Stopped();
    }

    private void exact(byte[] expected) throws IOException {
        require(Arrays.equals(expected, Files.readAllBytes(complete.toPath())), "Published bytes changed unexpectedly");
        require(!partial.exists(), "Partial file remains");
    }

    private void awaitWifi(boolean eligible) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(3);
        while (SyncJob.wifi(owner.getTargetContext()) != eligible) {
            require(SystemClock.elapsedRealtime() < end, "Wi-Fi eligibility did not become " + eligible);
            Thread.sleep(100);
        }
    }

    private void signal(String message) {
        var result = new Bundle();
        result.putString("stream", message + "\n");
        owner.sendStatus(1, result);
    }

    private SSLContext tls(boolean trustFixture) { return trustFixture ? fixtureTls : defaultTls; }

    private SSLContext createTls(KeyStore keys, boolean trustFixture) throws Exception {
        javax.net.ssl.KeyManager[] managers = null;
        if (trustFixture) {
            var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys, "test-only-password".toCharArray());
            managers = km.getKeyManagers();
        }
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trustFixture ? keys : null);
        var tls = SSLContext.getInstance("TLS");
        tls.init(managers, tm.getTrustManagers(), null);
        return tls;
    }

    private final class Server implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        private final ServerSocket listener;
        private final FutureTask<Void> response;
        private final boolean rejection;
        private final boolean gated;
        private volatile Socket client;

        Server(boolean gated, boolean rejection) throws Exception {
            this.rejection = rejection;
            this.gated = gated;
            listener = tls(true).getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(30000);
            response = new FutureTask<>(() -> {
                try (Socket socket = listener.accept()) {
                    client = socket;
                    socket.setSoTimeout(30000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = reader.readLine();
                    if (first == null) throw new java.io.EOFException("Client closed before GET");
                    require(first.startsWith("GET "), "Unexpected request");
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                    var out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/mp4\r\nContent-Length: " + BODY.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(BODY, 0, 12);
                    out.flush();
                    if (gated) require(release.await(4, TimeUnit.MINUTES), "Host never released body");
                    out.write(BODY, 12, BODY.length - 12);
                    out.flush();
                }
                return null;
            });
            var thread = new Thread(response, "native-tls-peer");
            thread.setDaemon(true);
            thread.start();
        }

        HttpURLConnection connection(boolean trust, boolean wrongHost, int timeout) throws Exception {
            var ssl = tls(trust).getSocketFactory();
            return AudioDownload.open(MEDIA, logical -> {
                var endpoint = new URL("https://" + (wrongHost ? "127.0.0.1" : "localhost") + ":" + listener.getLocalPort() + "/audio");
                var conn = (HttpsURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
                conn.setSSLSocketFactory(ssl);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(timeout);
                return conn;
            });
        }

        @Override public void close() throws Exception {
            release.countDown();
            listener.close();
            try { response.get(10, TimeUnit.SECONDS); }
            catch (ExecutionException e) {
                if (!(e.getCause() instanceof IOException && (rejection || gated))) throw e;
            } finally { if (client != null) client.close(); }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static final class Stopped extends RuntimeException {}
}
