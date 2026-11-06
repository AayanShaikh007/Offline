package com.ayaanshaikh.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SpotifySource {
    static final String REDIRECT = "com.ayaanshaikh.offline://spotify/callback";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String API = "https://api.spotify.com/v1";
    private static final Object ACCOUNT_LOCK = new Object();
    private static final Object REFRESH_LOCK = new Object();
    private static final int BASE64 = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

    private final SharedPreferences account;

    SpotifySource(Context context) { account = context.getSharedPreferences("spotify", Context.MODE_PRIVATE); }

    boolean connected() { return account.contains("token"); }
    String clientId() { return account.getString("client", ""); }
    String session() { return account.getString("session", ""); }

    Uri authorize(String clientId) throws Exception {
        String client = clientId.trim();
        if (!client.matches("[0-9a-fA-F]{32}")) throw new IOException("Enter a 32-character Spotify Client ID");
        String verifier = random(48);
        String state = random(24);
        String challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)), BASE64);
        synchronized (ACCOUNT_LOCK) {
            if (!account.edit().putString("pendingClient", client).putString("state", state)
                    .putString("verifier", verifier).putLong("started", System.currentTimeMillis()).commit()) {
                throw new IOException("Could not save Spotify authorization request");
            }
        }
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                .appendQueryParameter("client_id", client).appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("scope", "playlist-read-collaborative").build();
    }

    void complete(Uri callback) throws Exception {
        if (!"com.ayaanshaikh.offline".equals(callback.getScheme()) || !"spotify".equals(callback.getHost())
                || !"/callback".equals(callback.getPath())) throw new IOException("Invalid Spotify callback");
        String client;
        String verifier;
        String state;
        synchronized (ACCOUNT_LOCK) {
            state = account.getString("state", "");
            if (state.isEmpty() || !state.equals(callback.getQueryParameter("state"))
                    || System.currentTimeMillis() - account.getLong("started", 0) > 15 * 60_000L) {
                throw new IOException("Spotify sign-in expired. Start again from settings.");
            }
            verifier = account.getString("verifier", "");
            client = account.getString("pendingClient", "");
            account.edit().remove("verifier").commit();
        }
        String code = callback.getQueryParameter("code");
        if (code == null || verifier.isEmpty()) throw new IOException("Spotify sign-in was cancelled or already used");
        JSONObject tokens = request(TOKEN_URL, null, Map.of("grant_type", "authorization_code",
                "client_id", client, "code", code, "redirect_uri", REDIRECT, "code_verifier", verifier));
        synchronized (ACCOUNT_LOCK) {
            if (!state.equals(account.getString("state", ""))) throw new IOException("Spotify sign-in was superseded");
            save(tokens, client, "", random(24));
            account.edit().remove("state").remove("pendingClient").remove("started").commit();
        }
    }

    void disconnect(OfflineStore store) {
        synchronized (ACCOUNT_LOCK) {
            account.edit().clear().commit();
            store.clearSpotify();
        }
    }

    int apply(OfflineStore store, PlaylistLink link, OfflineStore.Snapshot snapshot, String session) {
        synchronized (ACCOUNT_LOCK) {
            return session.equals(session()) && connected() ? store.snapshot(link, snapshot) : 0;
        }
    }

    private String accessToken(boolean force) throws Exception {
        synchronized (REFRESH_LOCK) {
            String session;
            String client;
            JSONObject current;
            synchronized (ACCOUNT_LOCK) {
                session = session();
                client = clientId();
                String encrypted = account.getString("token", null);
                if (encrypted == null) throw new IOException("Connect Spotify in Settings");
                current = decrypt(encrypted);
                if (!force && current.getLong("expires") > System.currentTimeMillis() + 60_000) {
                    return current.getString("access");
                }
            }
            String refresh = current.getString("refresh");
            JSONObject updated = request(TOKEN_URL, null, Map.of("grant_type", "refresh_token",
                    "client_id", client, "refresh_token", refresh));
            synchronized (ACCOUNT_LOCK) {
                if (!session.equals(session())) throw new IOException("Spotify account changed");
                save(updated, client, refresh, session);
            }
            return updated.getString("access_token");
        }
    }

    private void save(JSONObject response, String client, String oldRefresh, String session) throws Exception {
        JSONObject tokens = new JSONObject();
        tokens.put("access", response.getString("access_token"));
        tokens.put("refresh", response.optString("refresh_token", oldRefresh));
        tokens.put("expires", System.currentTimeMillis() + response.getLong("expires_in") * 1000);
        if (!account.edit().putString("token", encrypt(tokens)).putString("client", client)
                .putString("session", session).commit()) throw new IOException("Could not store Spotify credentials");
    }

    OfflineStore.Snapshot snapshot(PlaylistLink link) throws Exception {
        String base = API + "/playlists/" + link.id();
        JSONObject metadata = get(base + "?fields=name,public");
        if (!metadata.optBoolean("public", true)) throw new IOException("Spotify playlist must be public.");
        String name = metadata.getString("name");
        var songs = new ArrayList<OfflineStore.RemoteSong>();
        int offset = 0;
        for (int page = 0; page < 250; page++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Sync interrupted");
            JSONObject response = get(base + "/items?limit=50&offset=" + offset);
            var entries = response.getJSONArray("items");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null || entry.optBoolean("is_local")) continue;
                JSONObject track = entry.optJSONObject("item");
                if (track == null) track = entry.optJSONObject("track");
                if (track == null || !"track".equals(track.optString("type"))) continue;
                String id = track.optString("id", "");
                var artists = track.optJSONArray("artists");
                JSONObject first = artists == null ? null : artists.optJSONObject(0);
                if (id.isEmpty() || first == null) continue;
                songs.add(new OfflineStore.RemoteSong(id, track.getString("name"), first.getString("name"),
                        Math.round(track.optLong("duration_ms") / 1000.0), null));
            }
            if (response.isNull("next")) return new OfflineStore.Snapshot(name, songs);
            if (entries.length() == 0) break;
            offset += entries.length();
        }
        throw new IOException("Spotify playlist pagination did not finish");
    }

    private JSONObject get(String url) throws Exception {
        try {
            return request(url, accessToken(false), null);
        } catch (SpotifyError e) {
            if (e.status != 401) throw e;
            return request(url, accessToken(true), null);
        }
    }

    private static JSONObject request(String address, String token, Map<String, String> form) throws Exception {
        HttpURLConnection conn = Http.open(address);
        conn.setInstanceFollowRedirects(false);
        try {
            conn.setRequestProperty("Accept", "application/json");
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (form != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                var values = new ArrayList<String>();
                for (var field : new LinkedHashMap<>(form).entrySet()) {
                    values.add(URLEncoder.encode(field.getKey(), "UTF-8") + "="
                            + URLEncoder.encode(field.getValue(), "UTF-8"));
                }
                byte[] data = String.join("&", values).getBytes(StandardCharsets.UTF_8);
                try (var body = conn.getOutputStream()) { body.write(data); }
            }
            int status = conn.getResponseCode();
            if (status != 200) throw new SpotifyError(status, form != null);
            return new JSONObject(Http.read(conn.getInputStream()));
        } finally {
            conn.disconnect();
        }
    }

    private static String random(int size) {
        byte[] random = new byte[size];
        new SecureRandom().nextBytes(random);
        return Base64.encodeToString(random, BASE64);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        String name = "offline.spotify.v1";
        KeyStore.Entry old = store.getEntry(name, null);
        if (old instanceof KeyStore.SecretKeyEntry secret) return secret.getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(name,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static String encrypt(JSONObject tokens) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.getIV(), BASE64) + "." + Base64.encodeToString(
                cipher.doFinal(tokens.toString().getBytes(StandardCharsets.UTF_8)), BASE64);
    }

    private static JSONObject decrypt(String encoded) throws Exception {
        String[] parts = encoded.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], BASE64)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], BASE64)), StandardCharsets.UTF_8));
    }

    private static final class SpotifyError extends IOException {
        final int status;

        SpotifyError(int status, boolean login) {
            super(switch (status) {
                case 400, 401 -> login ? "Spotify sign-in failed. Check the Client ID and reconnect."
                        : "Spotify session expired. Reconnect in Settings.";
                case 403 -> "Spotify denied access. Check app access and playlist ownership or collaboration.";
                case 404 -> "Spotify playlist unavailable.";
                case 429 -> "Spotify rate limit reached. The next sync will retry.";
                default -> "Spotify returned HTTP " + status;
            });
            this.status = status;
        }
    }
}
