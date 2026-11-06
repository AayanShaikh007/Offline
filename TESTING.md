# Verification report — 2026-11-06

Baseline: `d3a5c7ebb94ca5ee3db56ae40d8575e540977a96`.

## Build and automated checks

Verified using Temurin JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and Gradle 8.11.1 on Linux:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- Debug APK: built successfully.
- Unit/Robolectric tests: **36 passed, 0 failed**.
- Lint: **0 errors, 6 warnings**. Remaining warnings concern synchronous Spotify preference commits, the programmatically constructed download view, and untranslated status text.
- Wrapper: downloaded the pinned distribution, checked its SHA-256, and executed the documented command successfully.

| Area | Verified behavior |
| --- | --- |
| Links | YouTube/Music and localized Spotify playlist links; deceptive domains, credentials in URLs, missing playlist IDs, unsupported radio links; null/empty input; short links, Shorts/live links, encoded IDs, whitespace |
| Matching | Artist identity, accents, official labels, explicitly requested editions, rejected alternate editions, duration tolerance, missing extractor metadata |
| Audio response validation | Trusted HTTPS media hosts, rejected impostor domains/ports, valid MP4/WebM signatures, truncated/mismatched/HTML responses |
| HTTP | UTF-8, stream closure, bounded response size, interruption, rejected cleartext |
| Storage | Duplicate playlists/tracks, ordering, removals/reappearances, empty snapshots, saved-audio exclusion from the queue, missing-file reconciliation, untracking during sync, retry backoff, manual matching, Spotify source removal, bounded logs |
| Android lifecycle | Empty Sources/Vault/Logs, Settings navigation and recreation; Android 8/API 26 and Android 15/API 35 |
| Scheduler | Empty-source no-op, offline deferral, repeated requests, persisted hourly job, battery/storage constraints, cancellation after last source removal; API 26 and 35 |
| Spotify authorization | Client ID validation, distinct PKCE/state values, exact redirect URI, forged and expired callbacks, disconnected state |
| Playback failure | Missing-file message; harmless transport/seek actions without a ready player; stop clears error state; API 26 and 35 |

## Fixed defects

1. Restored missing Gradle wrapper scripts/JAR and the distribution URL. The documented build previously could not run from a fresh checkout.
2. Missing video titles no longer throw during automatic matching.
3. Missing link input now produces normal validation instead of a null-pointer exception.
4. Manual matches accept standard Shorts/live/embed links, padded raw IDs, and URL-encoded watch IDs.
5. Stopping playback clears the previous error so the empty error panel can disappear.
6. Made the legacy backup policy explicit alongside the existing Android 12+ rules, resolving the manifest lint warning.

## Android VM

Installed and interacted with the debug APK on an Android 15/API 35 x86_64 VM, using Android Emulator 37.2.13, software CPU/GPU emulation, and a 480×800 display. Hardware virtualization was unavailable. The documented build command and the APK used in this VM both passed verification.

Two locally generated, two-minute AAC tones were placed in the fresh VM's app storage and indexed in a fixture database. Wi-Fi was disabled for playback. These files were seeded fixtures, not downloads from YouTube. Emulator audio output was disabled; playback was checked through the rendered controls, advancing position, duration, and native media-session state rather than listening to sound.

| Interactive check | Result |
| --- | --- |
| Install and launch | Passed; Sources rendered successfully |
| Invalid playlist input | Passed; actionable inline error and dialog remained open |
| Playlist details | Passed; fixture showed 2 / 2 available offline |
| Vault | Passed; both generated tracks appeared |
| Offline AAC playback | Passed; two-minute duration and advancing playback position; native session `PLAYING(3)` |
| Next track | Passed; title/session metadata changed from Tone one to Tone two |
| Background playback | Passed; after Home, media session stayed playing and service remained foreground with notification ID 2101 |
| Return to app | Passed; playback controls and position restored |
| Pause | Passed; UI changed to Play and native session became paused |
| Seek while paused | Passed; position changed from 1:05 to 0:20 and native session reported `PAUSED(2)`, position 20004 ms |
| Untrack | Passed; confirmation shown, untrack event logged, and both audio files remained in Vault |
| Logs and Settings | Passed; untrack log and Spotify/setup/retry controls rendered after playback |

Screenshots: [offline playback](docs/testing/android15-offline-playback.png), [input validation](docs/testing/android15-invalid-playlist.png).

The VM took roughly 4.5 minutes to boot and its System UI repeatedly timed out under software emulation. System UI was restarted and repeated system-error dialogs were suppressed to complete interactions; logs remained enabled. This run is not a useful performance benchmark. No Offline entries appeared in the collected crash buffer.

## Scope and remaining checks

Robolectric runs Android behavior on the JVM; it does not establish real audio decoding, background-service survival, or external service availability. The VM uses generated AAC fixtures to exercise local playback; fixture playback does not prove a YouTube download succeeded.

Live YouTube extraction and downloads were not verified end to end. Full Spotify authorization, refresh, account switching, API permission/rate-limit behavior, and live playlist access require a Spotify developer app and authorized account. No Spotify credentials were provided. Real-device checks remain appropriate for Bluetooth/headset removal, phone-call audio focus, notification controls, OEM battery policies, network transitions during transfers, low-storage behavior, process death, and long multi-batch syncs. Download redirect handling and full transfer interruption are reviewed but not exercised against a controlled media server by these automated tests.
