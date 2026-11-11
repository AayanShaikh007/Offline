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

- [ ] Add controlled-server coverage for redirect handling, truncated transfers, unknown content length, cancellation, retry, and partial-file cleanup. Keep production HTTPS/media-host restrictions intact.
- [ ] Exercise process death and interrupted sync recovery; confirm published audio, the database index, and retry state remain consistent.
- [ ] Verify Wi-Fi loss/reconnection, metered networks, low storage, low battery, and continuation of a queue larger than one sync batch.
- [ ] Extend native playback coverage for corrupt/missing files, rapid track changes, audio-focus loss, headset removal, notification actions, and returning to the app.
- [ ] Add reproducible build/test CI if an equivalent workflow is not already present. Use least required permissions and avoid duplicate workflows.

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
