package com.ayaanshaikh.offline;

import android.net.Network;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Http {
    static final ThreadLocal<Network> NETWORK = new ThreadLocal<>();

    static HttpURLConnection open(String address) throws IOException {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())) throw new IOException("An HTTPS source is required");
        Network network = NETWORK.get();
        HttpURLConnection conn = (HttpURLConnection) (network == null
                ? url.openConnection() : network.openConnection(url));
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(15_000);
        return conn;
    }

    static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (stream; ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Connection interrupted");
                if (data.size() + count > 12_000_000) throw new IOException("Response exceeded size limit");
                data.write(buffer, 0, count);
            }
            return new String(data.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
