# Offline

Offline is a sideloaded Android music player that watches playlists and downloads new tracks on unmetered Wi-Fi. Its terminal interface shows tracked sources, a local audio vault, and a sync log.

## Use the app

1. Install the APK on Android 8.0 or newer.
2. Open **sources → add playlist** and paste a public YouTube or YouTube Music playlist link. The playlist can belong to anyone. Use a regular playlist link with a `list=` parameter.
3. Leave the phone on unmetered Wi-Fi or tap **sync now**. The first sync queues existing tracks. Later checks queue additions and keep previously saved audio. Android may delay jobs for battery or storage. Large queues continue in batches while constraints are met.
4. Play saved tracks from **vault** or a tracked playlist. Untracking a playlist leaves its downloads in the vault.

## Connect Spotify

1. Create a Web API app in the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard). Spotify requires the Development Mode app owner to have Premium and limits new apps to five authorized users.
2. Register this exact redirect URI in the dashboard:

   ```text
   com.ayaanshaikh.offline://spotify/callback
   ```

3. In Offline, open **settings → Connect Spotify**, paste the **Client ID**, then sign in in your browser. The app uses PKCE and keeps the session tokens encrypted with Android Keystore.
4. Paste the link for a public playlist you own or collaborate on. Spotify Development Mode restricts track access to those playlists, even when a followed playlist is public. A Spotify link supplies track metadata. Offline looks for the same recording on YouTube and lets you choose a YouTube URL when the match is uncertain.

YouTube extraction uses [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) and depends on the stream formats YouTube currently makes available. Save audio you have permission to download. Playlist information and audio are stored on the device. The app has no backend or analytics. Disconnecting Spotify clears the Spotify session and tracked Spotify playlists while keeping already saved audio.

## Build from source

Use JDK 17 and Android SDK Platform 35.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is under `app/build/outputs/apk/debug/`. On Windows, use `gradlew.bat`. Use the same signing key when building an update for an already installed APK.

Source is available under GPL-3.0-or-later. See [LICENSE](LICENSE).
