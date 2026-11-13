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

## Integration verification — 2026-11-11

Merged current `main` at `880ec1a` into the fixes branch, preserving version 0.1.4 and its manual-sync retry, DNS, and audio-transfer changes. Updated the storage regression tests for `queue(boolean force)` and verified that forced sync bypasses retry backoff while continuing to exclude unresolved manual matches.

The debug build, **40 tests (0 failures, 0 skipped)**, and lint completed successfully using JDK 17 and SDK 35. This is a fresh build/test/lint check; the Android VM screenshots above remain the historical 2026-11-06 test of version 0.1.3. The VM pass was not repeated during this scheduling/publication update.

## Automatic CI — 2026-11-11

Added `.github/workflows/android.yml` to run wrapper validation, SDK/build-tool setup, and the documented build/test/lint tasks for PRs and pushes to `main`. The job retains available test/lint diagnostics on failure and uploads a debug APK only after successful checks. Artifacts expire after seven days; CI does not sign release updates or run live-service/device tests.

Validation before publication: actionlint 1.7.12 reported no errors, and a fresh local test execution passed all 40 cases (0 failures/skips), with debug build and lint successful. Hosted runner results and downloadable artifacts are recorded in GitHub Actions for each commit; this local check is not a claim about a hosted run.

The first [hosted Android checks run](https://github.com/AayanShaikh007/Offline/actions/runs/43030307614) completed successfully on `e740b54`, including clean-runner wrapper validation, JDK/SDK setup, build/test/lint, and both artifact uploads. This hosted run validates CI execution, not live downloads or device playback.

## Redirect and cancellation verification — 2026-11-12

Two new cancellation cases failed against the previous redirect loop: cancellation before starting a request still opened a connection, and cancellation during a response could continue redirecting or hand off the response. After adding interruption checks before each hop and after each response, both cases pass, the interrupt flag remains set for the sync caller, and owned connections are disconnected.

`AudioRedirectTest` adds 12 deterministic cases for all supported redirect codes, relative locations, the allowed four-hop chain, loops, host/scheme/port/userinfo rejection before another connection is opened, missing/malformed locations, failure cleanup, DNS message sanitization, non-redirect errors, and cancellation. Only connection creation is injected; production uses the same policy with `Http.open`. No test-only trusted host or TLS bypass was added, and these fixtures do not make external network requests.

The full local debug build, **52 tests (0 failures, 0 skipped)**, and lint passed with JDK 17/SDK 35. SQLite test fixture teardown now closes the helpers; the final output no longer contains the observed CloseGuard resource-leak warnings. Lint retains 6 pre-existing warnings and 0 errors. Hosted execution is visible on PR #2 for each pushed commit.

No emulator or live service was used for these deterministic HTTP control-flow tests. Already-blocked socket operations remain bounded by existing HTTP timeouts; the fix does not promise immediate socket cancellation. Real TLS/socket tests and process-death recovery remain open backlog items.

## Published-file restart recovery — 2026-11-13

Added `AudioRecoveryTest` with nine scenarios on both API 26 and API 35 (18 additional cases). Controlled responses publish signature-valid file fixtures through `AudioDownload.save`; tests deliberately omit database indexing, then close and reopen SQLite while retaining those files. M4A/WebM reuse is also exercised through `YouTubeSource.save`, with transfer-progress callbacks rejected on the reuse path.

The cases verify durable indexing after reuse, exact saved bytes, exclusion from normal/forced queues, shared-file de-duplication across playlists, partial-file rejection, truncated/mismatched container rejection, invalid M4A fallback to valid WebM without deletion, replacement of a stale partial file, reconciliation of a missing indexed file, and persisted retry/backoff/manual-match state.

The local lookup is now a store helper called before extractor initialization. This extracts the existing recovery algorithm rather than introducing a new format or relaxing its validation. The debug build, **70 tests (0 failures, 0 skipped)**, and lint passed with JDK 17/SDK 35; lint has 0 errors and 6 existing warnings. GitHub checks on PR #2 record hosted results for each push.

These are deterministic JVM/Robolectric persistence-boundary tests. They do not kill an Android process, exercise an unclosed SQLite WAL after a crash, emulate power loss, decode the signature fixtures as audio, or contact live services. Those validation gaps remain explicit in the maintenance backlog.
