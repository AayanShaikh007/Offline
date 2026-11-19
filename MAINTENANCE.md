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
- [x] Add JDK loopback HTTP/TLS transport coverage for fixed/chunked bodies, truncated transfers, stalled-read timeout/cleanup/retry, cooperative eligibility loss, certificate trust, and hostname verification. Production HTTPS/media-host restrictions remain intact.
- [x] Port TLS/body transport checks to Android instrumentation: certificate/hostname rejection, real Wi-Fi eligibility loss and retry, partial cleanup/existing-file preservation, and interruption after observing a blocked socket-read stack frame.
- [ ] Extend active-transfer testing into native JobService with captured-network/interface-bound sockets and queue/index state. Current native transport uses loopback plus real eligibility changes; it does not physically sever the socket route or establish immediate production I/O cancellation.
- [x] Cover the published-file/database-index restart boundary with SQLite reopen tests on API 26 and 35: recover M4A/WebM without a new transfer, keep partial/invalid files queued, preserve retry/manual-match state, and exclude shared saved audio from both queues.
- [x] Preserve manual-sync retry intent across cooperative interruptions and app reopen, acknowledge only completed batches, and retain a newer request made while an older batch runs.
- [ ] Extend native recovery beyond a flushed metadata checkpoint and executor-queued job: test termination during writes/transfers, asynchronous preference crash windows, and power loss. The 2026-11-17 smoke test now covers real SIGKILL with SQLite open, native queued-job cancellation, and resume; it does not establish arbitrary WAL/power-loss recovery.
- [x] Verify forced retry queues across the 30-track/time budget. Release prior backoff once per request, persist ordinary queue eligibility for remaining tracks, and preserve backoff assigned by new failures when resuming.
- [x] Persist unvisited manual playlist refresh targets across metadata time limits and interruptions; continue automatic due refreshes after the budget and skip recently attempted sources on resume.
- [ ] Verify Wi-Fi loss/reconnection, metered networks, low storage, low battery, and continuation of a queue larger than one sync batch.
- [ ] Extend native playback coverage for corrupt/missing files, rapid track changes, audio-focus loss, headset removal, notification actions, and returning to the app.
- [x] Cancel native jobs by stable job ID, reject stale completion callbacks, and add a repeatable disposable-emulator smoke procedure with a CI-compiled instrumentation APK.
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

### 2026-11-13 — published audio and database restart recovery

- Triage: no new owner bug issues or PR feedback; current CI was green. Main remains at version 0.1.4. PR #1's player UI and PR #3's product goals remain separate; this increment extends maintenance PR #2.
- Isolated existing published-file lookup in `OfflineStore.findPublishedAudio`. `YouTubeSource.save` now checks local files before initializing the extractor, so the recovery path can be verified independently of live providers. File-name and container-signature checks are unchanged; invalid files are preserved rather than deleted.
- Added nine recovery scenarios, each executed on Android API 26 and 35 (18 additional tests): M4A/WebM publication before indexing, partial files, invalid-first-format fallback, truncated/mismatched containers, stale-part replacement, missing indexed files, shared audio, and durable backoff/manual-match state. The first two exercise `YouTubeSource.save` returning an existing file without starting a transfer.
- Validation: debug APK build, all 70 tests (0 failures/skips), and lint passed locally with JDK 17/SDK 35; lint has 0 errors and 6 existing warnings. Hosted validation is tracked by the [checks on PR #2](https://github.com/AayanShaikh007/Offline/pull/2/checks).
- Limits: tests deliberately omit the index write, close/reopen SQLite, and reuse filesystem fixtures. This is not an actual OS process kill, unclean-WAL/power-loss test, audio decoding check, or live YouTube/Spotify run. No new Android VM test was performed.
- Blockers: none for this increment; native crash/restart and live-service validation remain open.
- Next: inspect whether a forced manual sync interrupted before queue completion retains its retry intent when JobScheduler reschedules it; add a reproduction before changing behavior. Continue to triage new owner reports first.

### 2026-11-14 — retain interrupted manual-sync intent

- Triage: no new owner issues, review feedback, or failed checks; main remains at version 0.1.4. PR #1's player UI and PR #3's product-goal work remain separate. This increment extends maintenance PR #2.
- Reproduced the consumed-force bug with a deferred track and a locally published file: after an interruption, a subsequent run lost the backoff override and did not index the file. App reopen also failed to resume a pending manual request when the playlist had been checked recently. The baseline had 10 failures across API 26/35.
- Manual intent now moves atomically from pending to an in-progress preference instead of being discarded at run start. A normal batch return acknowledges only in-progress intent; interruption leaves it available to the next run. A newer manual request stays pending. App-open/deferred scheduling checks both states, and removing the last source clears both.
- Added eight behavioral scenarios on API 26 and 35 (16 cases): early/after-lookup/repeated interruptions, app-open recovery, successful acknowledgement without a retry loop, a newer request during an older run, clearing intent with all sources removed, and preserving automatic-run backoff. Fixtures use a disconnected Spotify session and existing local audio, so provider HTTP is not needed.
- Validation: debug build, all 86 tests (0 failures/skips), and lint passed locally with JDK 17/SDK 35. Lint has 0 errors and 6 existing warnings; hosted validation is tracked by the [checks on PR #2](https://github.com/AayanShaikh007/Offline/pull/2/checks).
- Limits: tests invoke the sync coordinator with controlled interruption callbacks and Robolectric scheduling; no Android OS-kill, power-loss, live provider, or new VM run was performed. SharedPreferences retains its existing asynchronous write semantics. This change does not add per-track forced-pass checkpoints or alter batch limits.
- Next: reproduce manual retries across the 30-track/time budget, particularly when all remaining songs are deferred by backoff, before changing continuation semantics. Native process/job tests remain open.

### 2026-11-15 — continue manual retries across batch limits

- Triage: no new owner issues/review feedback or failing checks; main remains at 0.1.4. PRs #1 and #3 remain separate; this increment extends maintenance PR #2.
- Reproduced five failing scenarios on API 26 and 35 (10 baseline failures): a deferred retry beyond track 30, a six-minute cutoff, a 61-track queue, queue eligibility after reopening SQLite, and re-bypassing backoff for an earlier failure after interruption.
- Manual request preparation now releases only the existing backoff of active, non-match-needed songs in SQLite, retaining attempt counts and statuses. Preparation is recorded after that update. Sync uses the ordinary due queue, so remaining tracks stay eligible for subsequent batches while newly failed tracks receive normal backoff.
- An in-progress preparation marker prevents a resumed request from releasing new backoff again. New explicit requests prepare a fresh retry pass; older in-flight requests without the marker are honored. Completion and last-source removal clear preparation state. No schema migration or batch-limit change was introduced.
- Added eight scenarios on API 26/35 (16 cases): 31/61-track boundaries, the time budget, interrupted failure backoff, a new explicit retry, unmatched/inactive preservation, older in-flight state, and SQLite reopen between batches. Fixtures use local files and a disconnected Spotify session, not provider HTTP.
- Validation: debug build, all 102 tests (0 failures/skips), and lint passed locally under JDK 17/SDK 35. Lint has 0 errors and 6 existing warnings. Hosted results are tracked by the [checks on PR #2](https://github.com/AayanShaikh007/Offline/pull/2/checks).
- Limits: controlled coordinator callbacks, Robolectric clocks, and clean database reopen do not establish native JobScheduler, OS-kill/WAL/power-loss, or live-provider behavior. SharedPreferences keeps apply() durability semantics; no per-playlist refresh checkpoint was added.
- Next: reproduce a forced refresh that exhausts the time budget before all recently checked playlists are visited; keep native scheduler/process tests open.


### 2026-11-16 — resume playlist metadata refreshes

- Triage: no new owner issues, PR feedback, or failing checks. Inspected main and pending branches; PR #1 (glass player) and #3 (product goals) remain separate. This increment extends maintenance PR #2.
- Reproduced 12 failing cases across API 26/35: normal deadline returns discarded unfinished manual metadata intent, continuation revisited already attempted sources, and automatic metadata-only work did not request another batch.
- Manual requests now snapshot playlist keys and persist the remaining targets after each completed lookup or recorded provider error. Deadline/interruption retains unvisited targets; removed sources are pruned, new explicit requests start a fresh target set, and legacy in-flight requests initialize the set once. Newly added/due sources still follow normal freshness rules. Automatic due metadata also requests continuation when the deadline interrupts it.
- Added eight scenarios on API 26/35 (16 cases): repeated deadlines, interruption, no replay of completed recent sources, new requests, removal, legacy state, automatic due work, and newly added sources.
- Validation: debug build, all 118 tests (0 failures/errors/skips), and lint passed locally using JDK 17/SDK 35. Lint retains 0 errors and 6 existing warnings. Hosted validation is tracked by the [checks on PR #2](https://github.com/AayanShaikh007/Offline/pull/2/checks).
- Limits: disconnected Spotify fails before HTTP; controlled callbacks and Robolectric elapsed clocks establish coordinator behavior, not successful provider access, native scheduling, OS kills, or power-loss durability. Preferences retain apply() semantics. No new emulator interaction was performed for this increment.
- Blockers: none for the coordinator fix; live Spotify validation requires an authorized developer account/app.
- Next: exercise native job cancellation/restart on an Android emulator, including inspection of persisted pending metadata state, before claiming OS lifecycle recovery. Continue to prioritize reproducible owner reports.


### 2026-11-17 — native job cancellation and process restart

- Triage: no new owner bug issues, review comments, or failing checks; main remains `880ec1a` (0.1.4). Inspected pending branches and kept PR #1's glass player and PR #3's product goals separate. This increment extends maintenance PR #2.
- The stronger native cancellation test reproduced a real bug on production baseline `c0ee06c`: Android reported job 1102 stopped, but releasing its queued executor still consumed the remaining metadata targets. `jobs` used JobParameters object identity, while start/stop callbacks receive separate parcelled objects.
- SyncJob now cancels by job ID. Each run has a separate identity so a stopped run's late completion cannot acknowledge or remove a replacement. A repeated start cancels its older worker; immediate/periodic retry policies remain intact.
- Added five lifecycle scenarios on API 26/35 (10 cases): distinct stop parcels, periodic cancellation, independent job IDs, stale completion after replacement, and repeated starts. Local debug build, native test APK compilation, all 128 JVM/Robolectric tests (0 failures/errors/skips), and lint passed using JDK 17/SDK 35; lint retains 0 errors and 6 existing warnings. actionlint 1.7.12 passed.
- Added the host-driven native smoke runner and [procedure](docs/testing/native-sync-smoke.md). It refuses existing app installations and requires an explicit fresh disposable API 35 emulator. CI compiles its APK but does not run an emulator.
- Native evidence: real SIGKILL with SQLite open retained a flushed two-target metadata checkpoint; the corrected queued job was cancelled through Android's shell JobScheduler service, then the native service resumed only the unvisited sources and acknowledged completion. Spotify fixtures fail before HTTP and require no credentials.
- Harness development corrected package-absence exit handling, Android 15's stop-response wording, and a false readiness signal from historical connectivity dump entries. The runner now reports eligibility through Offline's own Wi-Fi check and connects the built-in emulator AP. Failed-attempt evidence was retained outside the repository.
- Environment: no KVM; software emulation required a fresh formatted sparse data image because workspace space was limited. A boot-time System UI ANR blocked the first UI assertion; selecting Wait recovered Sources. No Offline crash was recorded in the inspected crash buffer. The final full smoke procedure passed, including Sources/Logs UI interaction, with screenshots and a native run summary linked from TESTING.md.
- Limits: checkpoints are explicitly flushed before SIGKILL. Queued-job cancellation does not prove cancellation of an already blocked socket or active database write, arbitrary WAL corruption/power-loss recovery, live-provider success, or decoding. Existing apply() durability limits remain.
- Next: extend the native harness to Wi-Fi loss/reconnection while work is active, backed by a bounded controlled transport fixture; keep production HTTPS/media-host restrictions intact. Continue triaging owner reports first.


### 2026-11-18 — real loopback download transport coverage

- Triage: no new owner bug issues, PR review comments, or failing checks; main remains `880ec1a`. Inspected pending branches; PR #1's glass player and PR #3's product goals remain separate. This increment extends maintenance PR #2 and advances the controlled-transport prerequisite from yesterday's next step.
- Added ten real JDK socket cases using bounded loopback servers and HttpURLConnection/HttpsURLConnection: fixed and chunked success, truncated known-length preservation, a stalled body timing out then retrying successfully, cooperative eligibility loss after the header, missing final chunk, short header, trusted fixture TLS, default-trust rejection, and hostname rejection despite trusting the certificate.
- Existing download behavior passed all new cases; no production bug was reproduced and no production implementation or dependency changed. Test routing uses the existing connection-factory seam after the normal media URL validation. Plain HTTP is confined to loopback tests. TLS trust is per connection; the hostname verifier and global trust stores are unchanged.
- Added a documented public localhost PKCS12 test fixture, with no provider or signing credentials. Socket accept/read waits and worker completion are bounded; peers and temporary partial files are checked/closed. Tests bypass host proxies only for their loopback connections.
- Validation: debug app and instrumentation APK builds, all 138 tests (0 failures/errors/skips), and lint passed locally under JDK 17/SDK 35; lint has 0 errors and 6 existing warnings. Verified the loopback certificate is absent from both APKs. Hosted validation is tracked by the [checks on PR #2](https://github.com/AayanShaikh007/Offline/pull/2/checks).
- Integration testing exposed global Conscrypt effects from Robolectric, including Java 17 reflective access and later elliptic-curve initialization failures despite selecting SunJSSE. Transport cases now run in a dedicated Test-task JVM, automatically required by testDebugUnitTest; production/global providers are unchanged. CI uploads both test report sets. Initial hosted CI also exposed an overly narrow hostname exception assertion; it now requires IOException plus the rejected IP and hostname/SAN mismatch text, while default-trust rejection still requires SSLHandshakeException with a PKIX path failure.
- Final local validation after isolation: 128 unit/Robolectric cases plus 10 transport cases, all passing with no failures/errors/skips; app/native-test APK builds and lint passed (0 errors, 6 existing warnings), and actionlint passed. The standalone transport task's dry-run includes compilation/resource prerequisites.
- Limits: these are JDK transport tests, not Android's networking stack, native JobScheduler/Wi-Fi transitions, live YouTube/Spotify, media decoding, or power-loss checks. Eligibility loss is a controlled callback while reading a real socket, not an OS network disconnection. The shorter fixture read timeout does not change production's 15-second read timeout or promise immediate blocked-I/O cancellation. No new emulator run was performed for this host-side prerequisite.
- Next: port the bounded TLS fixture to Android instrumentation, then reproduce Wi-Fi loss/reconnection and cancellation during an active body read. Keep production media-host/HTTPS policy intact and continue prioritizing owner reports.


### 2026-11-19 — native Android TLS and active body recovery

- Triage: no new owner bug issues, review comments, or failing checks; main remains `880ec1a` and PRs #1/#3 are unchanged. Read current instructions/backlog and inspected pending branches. This increment extends maintenance PR #2 without editing the glass player or product goals.
- Added a native transport stage and repeatable [host procedure](docs/testing/native-transport-smoke.md). It uses actual Android HTTPS/TLS peers, connection-specific fixture trust, and a public test certificate shared into instrumentation assets only. The main application APK still excludes the certificate; production networking and dependencies are unchanged.
- Native API 35 checks passed: exact TLS publication; default-trust rejection; wrong-hostname rejection; real Wi-Fi eligibility loss during an active response preserving the old complete file and removing partial output; reconnection/retry with exact bytes; and interruption after observing java.net.SocketInputStream.socketRead0. The interrupted read ended with SocketTimeoutException under the fixture's five-second timeout, then cleaned up successfully. No new production bug was reproduced.
- Warmed-VM verification passed, but cold repeats reproduced a fixture readiness failure. Added full worker/cause diagnostics and a 30-second stack capture: the client was still deriving the public PKCS12 key in BouncyCastle after the server accept budget had expired. The fixture now prepares separate trusted/default TLS contexts once before starting any peer or timed transfer. This fixes test setup ownership rather than changing application timeouts. Failed attempts are retained and are not counted as passes. The corrected procedure passed all six checks after a further cold boot, without a delayed-header diagnostic; evidence and APK digests are linked from TESTING.md. Sources/Logs interaction also passed after Wait on two queued Android system ANR dialogs; no Offline ANR or crash was recorded.
- Environment: emulator startup initially failed its free-space check. Removed duplicate SDK packages and an unused Gradle distribution, preserving the active wrapper/SDK and all AVD data. The existing API 35 AVD then booted under software emulation; no KVM is available. Debug APK updates preserved app data, and downloads used unique cache fixture directories.
- Local validation: debug app and instrumentation APK builds, 128 unit/Robolectric plus 10 isolated JDK transport tests (0 failures/errors/skips), and lint passed with JDK 17/SDK 35 (0 errors, 6 existing warnings). Native instrumentation passes are separate from the 138 host cases. Hosted results are tracked by the [checks on PR #2](https://github.com/AayanShaikh007/Offline/pull/2/checks).
- Limits: Wi-Fi is actually toggled and the production eligibility helper is used, but TLS packets stay on loopback. This is direct AudioDownload testing, not an active JobService/coordinator transfer, a severed interface-bound socket, provider access, media decoding, or power-loss recovery. The fixture timeout does not change production's 15-second timeout or prove immediate cancellation.
- Next: integrate the controlled active transfer with a native JobService run and verify retained queue/index intent after onStopJob or a captured-network change. Preserve current HTTPS/media-host restrictions, then expand to metered/battery/storage constraints. Continue prioritizing reproducible owner reports.
