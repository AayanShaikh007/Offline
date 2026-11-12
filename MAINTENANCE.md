# Offline maintenance backlog

This is the durable to-do list for the daily Offline maintenance task. The owner authorized ongoing bug investigation, tested improvements, GitHub pushes, and pull requests on 2026-11-11. Routine work does not need a new request each day.

A ChatGPT coding automation is enabled daily at approximately 08:00 America/Toronto, beginning 2026-11-11. The scheduler lives in ChatGPT; this document is its persistent backlog, not a standalone GitHub cron job.

## Daily working loop

1. Read this file, repository instructions, the latest available user feedback, open GitHub issues, and open pull requests. Prioritize concrete user-reported bugs. Never assume access to unrelated chats; a GitHub issue is the durable place for new reports.
2. Fetch current branches and inspect pending work before editing. Preserve other contributors' changes. Reuse an existing maintenance PR when it concerns the same work; avoid duplicate fixes or accumulating overlapping PRs.
3. Advance the project on every run where useful work is possible. Reproduce the highest-priority actionable bug first. Otherwise proactively choose a valuable feature, UX/accessibility improvement, performance/reliability change, test, tooling improvement, or documentation that removes a real blocker. Do not stop just because there are no new bug reports. Explain the benefit, split large work into reviewable increments, and carry unfinished work forward.
4. Implement and run checks appropriate to the change. The normal Android checks are `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` using JDK 17 and Android SDK Platform 35. Run emulator checks when available and relevant. Never report unexecuted checks as passed.
5. Update this backlog with findings, completion evidence, blockers, and the next concrete step. Push a focused branch and create or update a pull request containing the problem, change, validation, and limitations. Keep unverified implementation work in a draft PR. Do not automatically merge, publish releases, change credentials, or delete user data.
6. Report completed work and PR links. If nothing justified a change, explain what was checked; do not invent a bug or manufacture a cosmetic change to meet a daily quota.

## Priority 0 — reported bugs and build failures

- [ ] Triage new owner reports and failing checks first; capture reproduction steps, expected/actual behavior, affected Android version, and redacted logs.
- [ ] Review the existing glass-player PR (#1) for interactions with playback/lifecycle fixes and coordinate rather than overwrite it.

## Priority 1 — reliability gaps from the testing pass

- [x] Add controlled-connection redirect coverage for supported/relative redirects, the four-hop limit, untrusted destinations, invalid locations, failure cleanup, DNS diagnostics, and cancellation. A stopped sync no longer starts another redirect request or hands off a response after cancellation.
- [ ] Add controlled-server transport coverage for socket/TLS behavior, timeouts, retries, truncated/unknown-length transfers, and cleanup. Keep production HTTPS/media-host restrictions intact; the fixture-based tests do not validate real network transport.
- [ ] Exercise process death and interrupted sync recovery; confirm published audio, the database index, and retry state remain consistent.
- [ ] Verify Wi-Fi loss/reconnection, metered networks, low storage, low battery, and continuation of a queue larger than one sync batch.
- [ ] Extend native playback coverage for corrupt/missing files, rapid track changes, audio-focus loss, headset removal, notification actions, and returning to the app.
- [x] Add reproducible build/test CI: `.github/workflows/android.yml` validates PRs and `main` with JDK 17/SDK 35, publishes test/lint diagnostics, and provides a debug APK after successful checks. Actual hosted results are tracked by each workflow run.

## Priority 2 — features, usability, and compatibility

- [ ] Identify and implement the next valuable feature consistent with offline playlist tracking and playback, using owner feedback and observed friction to prioritize it. Expand the roadmap as needs become clear.

- [ ] Check small screens, large font sizes, rotation/recreation, accessibility labels, long titles, empty/error states, and large-playlist pagination.
- [ ] Review remaining lint warnings individually; preserve deliberately synchronous account-state persistence unless a tested alternative is appropriate.
- [ ] Review extractor/library compatibility and dependency updates; validate behavior before upgrading.
- [ ] Inspect crash/error messages for actionable recovery steps and accidental credential exposure.

## Checks requiring external setup

- [ ] Verify live YouTube playlist extraction and a permitted audio download end to end using a suitable test playlist.
- [ ] Verify Spotify sign-in, refresh, denied consent, account switching, disconnection during sync, access restrictions, and rate limiting using an authorized developer app/account. Never request tokens or secrets in chat.
- [ ] Verify real-device Bluetooth/phone-call behavior and OEM battery restrictions.

Mark these blocked when setup is unavailable and continue independent items instead of claiming success.

## Confirmed work

- [x] Restore Gradle wrapper/distribution configuration and normalize cross-platform script line endings.
- [x] Fix null metadata/input handling and support manual matches using Shorts/live/embed links, encoded IDs, and padded video IDs.
- [x] Clear stale playback errors on stop; make legacy backup policy explicit.
- [x] Add 36 passing unit/Robolectric cases and an Android 15 software-VM verification pass. Historical evidence and unverified areas are recorded in `TESTING.md`; rerun relevant checks for later changes.

## Run log

### 2026-11-11 — initialize autonomous maintenance

- Created the prioritized backlog and daily working loop.
- Published the previously tested fixes on `fix/android-regression-testing` at the owner's request.
- Merged current `main` while preserving version 0.1.4, manual-sync retries, DNS diagnostics, and newer audio-transfer tests. Adapted regression coverage to `queue(boolean force)` and checked that manual sync bypasses backoff without retrying unresolved matches.
- Enabled the recurring coding task for approximately 08:00 America/Toronto; broadened it at the owner's request to advance the project through features and other useful work even without new bug reports.
- Integration validation: debug build and lint succeeded; 40 tests passed with no failures or skips. See `TESTING.md` for the distinction between this check and the historical emulator pass.
- Next: review incoming reports and the pending player PR, then choose an evidence-backed item from the reliability backlog.

### 2026-11-11 — automatic pull request validation

- Triage: no open bug issues or PR feedback were reported, and the repository had no GitHub Actions workflows or runs. PR #1 still contains the separate glass-player UI work; this increment changes CI/documentation only and extends maintenance PR #2.
- Added Android CI for pull requests, pushes to `main`, and manual dispatch. The job validates the Gradle wrapper, installs SDK 35/build tools 35.0.0, uses JDK 17, and runs debug build, unit/Robolectric tests, and lint.
- Added seven-day test/lint report artifacts (including failure diagnostics when present) and a debug APK artifact after successful checks. Actions are pinned to inspected release commits; the token has read-only repository access and PR cache access is read-only.
- Validation: actionlint 1.7.12 passed; a fresh local run completed all 40 tests with 0 failures/skips, debug build, and lint (0 errors, 6 warnings). The first [hosted CI run](https://github.com/AayanShaikh007/Offline/actions/runs/43030307614) passed on commit `e740b54`, including wrapper/SDK setup, build/test/lint, report upload, and debug APK upload.
- No new emulator run is needed for this CI-only change; live-service and native-device gaps remain open.
- Blockers: none for this CI increment; the existing live-service/device checks still require external setup.
- Next: add bounded redirect-chain regression tests (including a trusted media host redirecting to an untrusted host and a redirect loop), then interrupted-sync recovery coverage. Continue to triage new owner reports first.

### 2026-11-12 — stop cancelled redirect requests and cover redirect boundaries

- Triage: no owner-reported bug issues or PR feedback; the latest CI was green. PR #1 (glass player) and PR #3 (product goals/provider roadmap) remain separate. This increment extends maintenance PR #2 without editing those branches or the README.
- Reproduced two cancellation regressions before the fix: an interrupted thread still opened a connection, and interruption during a response could cause another redirect request or a response handoff. Redirect handling now throws `InterruptedIOException` before opening each hop and after a response, preserves the interruption flag, and disconnects any owned response.
- Added 12 deterministic redirect test cases using an injectable connection creator. Production still uses `Http.open`, with the same HTTPS/Google media host policy and four-redirect limit applied before each request. Tests compare URL strings so assertions do not trigger DNS lookups.
- Coverage includes 301/302/303/307/308, relative and scheme-relative locations, exactly four redirects, loops, untrusted hosts/ports/userinfo/HTTP, missing/malformed Location, HTTP errors, timeouts, DNS failures, and cancellation.
- Closed SQLite test fixtures in teardown after the full run exposed CloseGuard warnings; the final run contained no such warnings.
- Validation: debug build, all 52 tests (0 failures/skips), and lint passed (0 errors, 6 existing warnings) using JDK 17/SDK 35. Hosted validation is tracked by the [checks on PR #2](https://github.com/AayanShaikh007/Offline/pull/2/checks).
- Limitations: this is controlled-connection/JVM coverage, not a live CDN/TLS or Android VM run. Cancellation is cooperative between operations; an already-blocked HTTP operation still relies on the existing network timeouts.
- Next: test recovery when a fully published audio file exists but the process stopped before database indexing, then review interrupted queue recovery. Continue to prioritize any new owner reports.
