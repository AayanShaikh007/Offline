package com.ayaanshaikh.offline;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

final class AudioDownload {
    private static final int MAX_REDIRECTS = 4;
    private static final int HEADER_LENGTH = 12;

    static HttpURLConnection open(String address) throws IOException {
        URL url = new URL(address);
        for (int redirects = 0; ; redirects++) {
            if (!isTrustedUrl(url)) throw new IOException("Untrusted audio server");
            HttpURLConnection connection = Http.open(url.toExternalForm());
            connection.setInstanceFollowRedirects(false);
            boolean keep = false;
            try {
                int code = connection.getResponseCode();
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                    keep = true;
                    return connection;
                }
                String location = connection.getHeaderField("Location");
                if (location == null || location.isBlank() || redirects == MAX_REDIRECTS) {
                    throw new IOException("Invalid audio redirect");
                }
                url = new URL(url, location);
            } finally {
                if (!keep) connection.disconnect();
            }
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
