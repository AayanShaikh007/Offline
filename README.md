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

## Test locally on Windows

### Test the signed APK on a phone

1. Install [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools) or use the copy installed by Android Studio.
2. Enable **USB debugging** under **Developer options**, connect the unlocked phone with a USB data cable, and approve the computer on the phone.
3. Download the signed APK to **Downloads**. Run these commands in PowerShell, adjusting the paths if needed. If you downloaded Platform Tools separately, point `$adb` to the extracted `adb.exe`.

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb devices
   & $adb -d install -r "$env:USERPROFILE\Downloads\Offline-0.1.4.apk"
   ```

   The phone should appear as `device`. The signed APK can update an earlier release signed with the same key and keep its data. Leave Play Protect enabled and stop if it blocks the install.

### Build and test changes from source

Open the repository in Android Studio and install **Android SDK Platform 35** in **SDK Manager**. Create and start an Android 15 or newer virtual device in **Device Manager**, then select it and press **Run**. Use an emulator for the debug build if your phone already has the signed release, since Android checks that an in-place update uses the same signing key.

For a command-line build, set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your Android SDK directory. From the repository root in PowerShell, run:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

### Verify a download

1. Open Offline and check the version below the app name. Start on unmetered Wi-Fi.
2. Add a small public YouTube or YouTube Music playlist containing audio you have permission to save, or open one you already track. Tap **Sync now**.
3. Watch the sync card on **Sources** or on the playlist page. While a track is transferring, the ring shows a percentage if the server provides a size. It spins if the size is unknown. The playlist's **available offline** count increases when a track finishes.
4. Open **Vault** and confirm the track appears. Enable airplane mode, then play that track from Vault to verify playback without a connection.
5. If a track fails, open **Logs** and note the latest retry or error. If the app crashes, collect the Android crash log with `& $adb logcat -b crash -d` in PowerShell.

Source is available under GPL-3.0-or-later. See [LICENSE](LICENSE).
