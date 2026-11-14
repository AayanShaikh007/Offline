package com.ayaanshaikh.offline;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;

final class AudioDownload {
    private static final int MAX_REDIRECTS = 4;
    private static final int HEADER_LENGTH = 12;
    private static final long MAX_BYTES = 150L * 1024 * 1024;

    interface Progress {
        void update(long received, long total);
    }

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    static HttpURLConnection open(String address) throws IOException {
        return open(address, url -> Http.open(url.toExternalForm()));
    }

    static HttpURLConnection open(String address, ConnectionFactory connections) throws IOException {
        URL url = new URL(address);
        for (int redirects = 0; ; redirects++) {
            checkInterrupted();
            if (!isTrustedUrl(url)) throw new IOException("Untrusted audio server");
            HttpURLConnection connection = null;
            boolean keep = false;
            try {
                connection = connections.open(url);
                connection.setInstanceFollowRedirects(false);
                int code = connection.getResponseCode();
                checkInterrupted();
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                    keep = true;
                    return connection;
                }
                String location = connection.getHeaderField("Location");
                if (location == null || location.isBlank() || redirects == MAX_REDIRECTS) {
                    throw new IOException("Invalid audio redirect");
                }
                url = new URL(url, location);
            } catch (UnknownHostException e) {
                throw new Http.DnsFailure(url, e);
            } finally {
                if (!keep && connection != null) connection.disconnect();
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Audio request interrupted");
    }

    static String save(HttpURLConnection connection, File complete, Runnable checkNetwork,
                       Progress progress) throws IOException {
        String filename = complete.getName();
        File partial = new File(complete.getParentFile(), filename + ".part");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("Audio server returned HTTP " + code);
            long expected = connection.getContentLengthLong();
            if (expected > MAX_BYTES) throw new IOException("Audio file exceeds 150 MB");
            String contentType = connection.getContentType();
            if (contentType != null && (contentType.startsWith("text/") || contentType.contains("json"))) {
                throw new IOException("Audio server returned a page");
            }

            progress.update(0, expected);
            long copied;
            try (var input = connection.getInputStream(); var output = new FileOutputStream(partial)) {
                byte[] header = readHeader(input, filename);
                checkNetwork.run();
                output.write(header);
                copied = header.length;
                progress.update(copied, expected);
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkNetwork.run();
                    copied += count;
                    if (copied > MAX_BYTES) throw new IOException("Audio file exceeds 150 MB");
                    output.write(buffer, 0, count);
                    progress.update(copied, expected);
                }
                output.getFD().sync();
            }
            checkNetwork.run();
            if ((expected >= 0 && copied != expected) || !partial.renameTo(complete)) {
                throw new IOException("Audio transfer did not complete");
            }
            return filename;
        } catch (UnknownHostException e) {
            throw new Http.DnsFailure(connection.getURL(), e);
        } finally {
            connection.disconnect();
            partial.delete();
        }
    }

    static boolean isTrustedUrl(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(url.getProtocol()) && url.getUserInfo() == null
                && (url.getPort() == -1 || url.getPort() == 443)
                && ("googlevideo.com".equals(host) || host.endsWith(".googlevideo.com"));
    }

    static byte[] readHeader(InputStream input, String filename) throws IOException {
        byte[] header = new byte[HEADER_LENGTH];
        new DataInputStream(input).readFully(header);
        boolean mp4 = header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
        boolean ebml = header[0] == 0x1a && header[1] == 0x45
                && header[2] == (byte) 0xdf && header[3] == (byte) 0xa3;
        if ((filename.endsWith(".m4a") && mp4) || (filename.endsWith(".webm") && ebml)) return header;
        throw new IOException("Audio response does not match its container format");
    }
}
