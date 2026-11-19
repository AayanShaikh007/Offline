# Android active-transfer smoke procedure

This procedure runs real Android HttpsURLConnection/SSLServerSocket traffic on a disposable API 35 emulator. It extends the host JDK tests to the Android TLS stack and actual Wi-Fi eligibility changes. CI compiles this instrumentation code; it does not execute an emulator.

Build with JDK 17 and SDK Platform 35:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
python3 scripts/native-transport-smoke.py \
  --serial emulator-5554 --disposable-emulator \
  --evidence /tmp/offline-native-transport
```

Use `--adb /path/to/platform-tools/adb` if needed. The evidence directory must be new. Android must have completed booting. The emulator must use the built-in open AndroidWifi access point and have Spotify disconnected.

The host script requires an explicit emulator serial, API 35, owner user 0, and the disposable-emulator acknowledgement. It updates the two debug APKs in place, retaining existing app data; it never uninstalls or clears the app. Test downloads use a unique directory under the target app's cache, not the user's audio library or database. The certificate/key are public test material from `src/test/resources`, shared into instrumentation assets only; the main application APK does not include them.

## Native checks

- Save an exact fixed-length TLS body with a connection-specific trust manager and normal hostname verification.
- Reject the fixture certificate with default Android trust, then reject the wrong hostname even when the fixture certificate is trusted.
- Pause a TLS response after its 12-byte container header while a download is active. The host disables Wi-Fi; the instrumentation waits until Offline's real eligibility check observes the loss, then releases the body. The download must reject the transfer, remove its partial file, and preserve the exact existing complete file.
- Re-enable/connect Wi-Fi, wait for eligible connectivity, and retry successfully with exact bytes and no partial file.
- Observe the reader in a socket-read stack frame, then interrupt it while its TLS peer withholds the body. Verify failure, reader termination, partial cleanup, and preservation of the existing complete file. This fixture uses a five-second read timeout; production retains its 15-second timeout.

The host temporarily disables captive-portal probing on the disposable VM to avoid external validation endpoints and restores the original setting and radio state in `finally`. The two test TLS contexts are prepared once before any peer is started; key derivation is excluded from the body-read readiness deadline. Positive TLS trust is per connection. The hostname verifier, global trust stores, production HTTPS/media-host policy, and provider credentials are unchanged. No provider request or extractor initialization is required.

## Interpretation and limits

TLS packets travel over loopback. Turning Wi-Fi off changes the actual Android eligibility state observed by the callback; it does not physically sever the loopback socket. This tests cooperative download cleanup and retry, not a Wi-Fi-bound socket losing its route. The active reader runs directly under instrumentation, rather than inside native JobService or the sync coordinator. Active native-job cancellation, captured-network identity changes, interface-bound transfer failures, and end-to-end database/queue indexing remain further work.

A read failure after interruption may be a timeout or platform interruption exception. It is not a promise of immediate cancellation of production I/O. Signature-valid bytes do not prove audio decoding or a successful provider download. This test does not establish process-death/power-loss durability.

Body readiness has a bounded 90-second allowance, with native thread stacks captured if it exceeds 30 seconds; socket connect/read deadlines are separate. Keep partial evidence on failure and inspect the exact stage. Emulator boot/System UI delays are not performance measurements. The script records instrumentation output, command results, and the crash buffer; the cache fixtures remain available for inspection.
