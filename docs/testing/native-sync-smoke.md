# Native sync smoke procedure

This host-driven smoke test checks Android 15 behavior that JVM tests cannot establish. It uses the platform instrumentation API, with no new test dependency or production hook. CI compiles the test APK; **CI does not run this emulator procedure**.

Use a **fresh disposable API 35 emulator**, not your phone or your everyday emulator. The script requires an explicit emulator serial, owner user 0, and no existing Offline app/test installation. It installs fixtures and retains them for inspection; it never clears, uninstalls, or overwrites an existing app. Use a new disposable AVD for another complete run.

## Run

Install Android SDK Platform 35, build tools 35.0.0, platform-tools, emulator, and an API 35 system image. Use the default open `AndroidWifi` emulator access point. Create and boot a fresh AVD using Android Studio or `avdmanager`. Wait for `adb -s <serial> shell getprop sys.boot_completed` to return `1`. Hardware acceleration is recommended; software emulation can take several minutes and is unsuitable for performance conclusions.

With JDK 17:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
python3 scripts/native-sync-smoke.py \
  --serial emulator-5554 \
  --disposable-emulator \
  --evidence /tmp/offline-native-smoke
```

Pass `--adb /path/to/platform-tools/adb` if needed. The evidence directory must not already exist. The custom instrumentation runner is driven by this script, rather than `connectedAndroidTest`.

## What it verifies

1. With Wi-Fi disabled, seed three recently checked, empty Spotify playlist fixtures. No Spotify account is connected, so metadata lookups fail locally before HTTP. Interrupt the real coordinator after one recorded lookup. Flush the completed preference checkpoint, retain the open SQLite helper, and report readiness.
2. SIGKILL that app process without force-stopping the package or closing SQLite. Start a new process and assert the persisted two-target refresh cursor, preparation marker, and first-source timestamp survived.
3. Block the production executor using test reflection, start immediate job 1102 on eligible Wi-Fi through Android's actual JobScheduler, and time it out through the shell service. Wait for Offline itself to report eligible Wi-Fi, then release and drain the executor; assert the cancelled queued worker made no metadata progress and preserved the cursor.
4. Allow the real job service to resume. Assert both unvisited sources were attempted, completed intent was acknowledged, and the already visited source was not replayed.
5. Launch the actual activity, verify fixture Sources render, tap Logs, verify the diagnostic renders, and capture screenshots, UI XML, job dumps, and crash buffer.

The script explicitly connects the built-in open `AndroidWifi` access point and temporarily disables captive-portal probing on this disposable VM so a validated emulator Wi-Fi network does not depend on an external probe endpoint. It restores the original setting and Wi-Fi state in `finally`. No provider credentials or production network restrictions are changed.

## Limits and failure evidence

This exercises cancellation of an executor-queued native job, not interruption of an already blocked socket or database operation. Checkpoints are explicitly flushed before SIGKILL; the test does not cover the asynchronous `apply()` crash window or power loss. It neither proves arbitrary WAL corruption recovery nor supplies a cross-store SQLite/preferences transaction. Fixtures do not validate successful Spotify access, YouTube extraction/downloads, or audio decoding.

A failed script preserves its partial logs and fixture installation. Read the exact failing stage before changing behavior; do not convert a timeout or missing native callback into a passing result. The bounded waits accommodate ordinary emulator slowness, not unlimited hangs. Screenshot success is not a performance benchmark. No test data is removed afterward.

On slow software VMs, a boot-time **System UI** ANR can obscure Sources. The harness preserves the original screenshot/XML, records the dialog, selects Wait once, and retries the UI assertion. It does not suppress logs or dismiss an Offline ANR. Treat such a run as functional evidence only, not performance evidence.
