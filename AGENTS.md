# AI coding-agent guide

## 1. Source of truth and working rules

This repository is the native Android **RobotRun** automation app for Cookie Run Classic. The current files under `app/src/main/` are authoritative. Read the relevant Kotlin, manifest, resources, assets, and tests before changing behavior; documentation is an index, not a substitute for call-site inspection. **Start a new agent session with [DEVELOPMENT_HANDOFF.md](DEVELOPMENT_HANDOFF.md)** for the current product baseline, deliberate exclusions, Telegram/updater status, Redfinger profile and verification limitations. This file remains the operational guide; the handoff is a dated snapshot, not a replacement for current source.

- Work only in this repository unless the task explicitly says otherwise. Inspect `git status` first and preserve unrelated edits.
- Keep changes narrow. Never commit tokens, Chat IDs, captured screens, logs containing user data, signing material, or generated `build/`, `.gradle/`, `.kotlin/`, or IDE files.
- Never infer behavior from old commits or porting history. `PORTING_STATUS.md` is the concise current capability map and `PORTING_AUDIT.md` is the current technical audit.
- Capture and gestures are device-dependent. Do not claim phone verification unless it was actually performed. A commit, static review, and written JVM tests are not evidence of a built APK or installed release.
- Search every caller before changing a public/helper contract. Most policy helpers have focused JVM tests under `app/src/test/java/com/example/cookierunbot/`.
- **Documentation is part of completion.** For changes to behavior, new commands, options, dependencies, release/update flow, or architecture, synchronize affected sections of `AGENTS.md`, `DEVELOPMENT_HANDOFF.md`, `PORTING_STATUS.md`, `PORTING_AUDIT.md`, `README.md`, and `ANDROID_TEST_CHECKLIST.md`. Update the Redfinger profile or calibration guide if those areas change. Do not blindly rewrite unaffected technical detail or leave conflicting current-command/version lists.

## 2. Project and entry points

- Package/application ID: `com.example.cookierunbot`; app label: RobotRun; min/target/compile SDK: 26/36/36; Kotlin/JVM 17. At this handoff `app/build.gradle.kts` declares **versionName `1.1.8`, versionCode `11`**; inspect source before referencing a later build.
- `app/src/main/AndroidManifest.xml` declares the launcher `MainActivity`, non-exported diagnostic/settings activities, foreground `ScreenCaptureService`, and exported bind-protected `BotAccessibilityService`.
- `CookieRunBotApplication.onCreate()` initializes the persistent trace, OpenCV, process-level `BotRuntime`, the Telegram command receiver, and runtime diagnostics.
- `MainActivity` is permission/diagnostic UI. It requests notification, overlay, battery, Accessibility, and MediaProjection access, starts/stops capture, attaches a runtime listener, and refreshes diagnostics. Destroying it does **not** stop the process-owned bot.
- `ScreenCaptureService` is `START_NOT_STICKY`. It owns MediaProjection resources and the floating `BotControlOverlay`; stopping capture dismisses the overlay and calls `BotRuntime.stop()`.
- `BotControlOverlay` loads/saves `BotOptions`, exposes Start/Stop, session totals, Friend Life/recovery test controls, and image toggles. Start passes a snapshot of options to `BotRuntime.start()`. Only image-save switches are intentionally read live from `BotOptionsStore` while a run is active.

## 3. Ownership map — inspect these first

| Change area | Current owners |
| --- | --- |
| Startup, permissions, diagnostics | `CookieRunBotApplication.kt`, `MainActivity.kt`, `AndroidManifest.xml` |
| Bubble/settings/start-stop | `automation/BotControlOverlay.kt`, `BotOptions.kt`, `BotOptionsStore.kt` |
| Process lifetime and UI callbacks | `automation/BotRuntime.kt` |
| Main loop, grouping, handlers, workflow timing | `automation/BotController.kt`, `BotStateMachine.kt`, policy helpers |
| State templates, ROIs, scaling, score | `detection/StateDetector.kt`, `app/src/main/assets/templates/` |
| MediaProjection and image ingestion | `capture/ScreenCaptureService.kt`, `ScreenCaptureManager.kt` |
| Latest-frame synchronization | `capture/LatestFrameProvider.kt` |
| Crop/resize and shared geometry | `capture/FrameNormalizer.kt`, `ContentGeometryRegistry.kt` |
| Coordinates and gestures | `automation/CoordinateMapper.kt`, `AutomationConfig.kt`, `ActionExecutor.kt`, `BotAccessibilityService.kt` |
| Friend Life | `BotController.kt`, `FriendLifeScheduler.kt`, `FriendSwipeProfile.kt`, `FriendScrollVerifier.kt`, `FriendLifeConfirmPolicy.kt` |
| Quick Receive/Send Lives | `BotController.kt`, `QuickLivesSafetyPolicy.kt` |
| Progress watchdog/recovery | `GameProgressWatchdog.kt`, `GameProgressWatchdogManager.kt`, `GameProgressRecovery.kt`, `GameProgressCloseRecovery.kt`, `ProgressRecoveryGate.kt` |
| Telegram | `notifications/TelegramConfig*.kt`, `TelegramSecureTokenStore.kt`, `TelegramNotifier.kt`, `TelegramCommandReceiver.kt`, `TelegramDashboardPolicy.kt`, `TelegramDashboardReplies.kt`, `TelegramUpdateReply.kt`, `TelegramApkDelivery.kt`, `TelegramScreenshotEncoder.kt`, `TelegramScreenshotMultipart.kt` |
| In-app updates | `UpdateActivity.kt`, `update/UpdatePolicy.kt`, `update/UpdateFeedClient.kt`, `update/RobotRunUpdateFileProvider.kt`, `res/xml/update_file_paths.xml` |
| Calibration UI | `automation/CoordinateCalibrationOverlay.kt`, `CalibrationImageView.kt`, `CalibrationCoordinatePolicy.kt`, `CalibrationPanelLayoutPolicy.kt`; [CALIBRATION.md](CALIBRATION.md) |
| Overnight diagnostics | `diagnostics/RobotLog.kt`, `OvernightLogArchive.kt`, `OvernightLogPolicy.kt`, `IncidentManager.kt`, `LogPackageBuilder.kt`; `capture/IncidentVideoRecorder.kt`, `IncidentVideoTiming.kt`; `notifications/TelegramLogDelivery.kt`; [docs/OVERNIGHT_DIAGNOSTICS.md](docs/OVERNIGHT_DIAGNOSTICS.md) |
| Round statistics | `statistics/RoundResultCollector.kt`, `RoundResultStore.kt`, `ResultNumberDetector.kt`, `MysteryBoxClassifier.kt` |
| Public images and diagnostics | `PublicImageStore.kt`, `ResultImageSaver.kt`, `NoMatchDebugFrameSaver.kt`, `RuntimeDiagnosticsLogger.kt`, `diagnostics/` |
| Build/release automation | `app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/build-apk.yml` |

## 4. What Start Bot actually does

`BotControlOverlay` validates/builds `BotOptions`, persists them, and calls `BotRuntime.start(options)`. `BotRuntime` forwards to its single process-level `BotController`.

`BotController.start()`:

1. Rejects a duplicate start under its lock, stores the option snapshot, increments the run generation, resets the recovery gate/state/group/round/popup/no-match fields, and starts a new `RoundResultCollector` session.
2. Starts `FriendLifeScheduler` with the saved user-selected minute interval (1–1440 minutes, default 61). The first scheduled run is due that interval after Start when enabled.
3. Starts `GameProgressWatchdogManager`, recording `BOT_START`, and supplies a one-shot recovery callback.
4. Creates the single `cookierun-bot-loop` executor and reports `PRE_GAME` / `WAITING_FOR_READINESS`.
5. `runLoop()` waits until capture is running, the frame generation advances, a frame is at most 1 second old, and Accessibility is both enabled **and connected**.
6. It creates one `StateDetector`. In `IN_GAME`, it blocks up to 500 ms for a generation newer than the last processed one; other groups take the current latest snapshot and retain the 120 ms polling cadence. Every snapshot clone is released.
7. `tick()` enforces running/recovery/readiness/staleness/fresh-frame gates, scans the ordered candidates, invokes one handler, and records local no-match diagnostics when nothing matches.

`stop()` invalidates the run generation (so late callbacks cannot affect a later run), resets recovery, cancels pending gestures, stops auto-tapping/Friend Life/watchdog, interrupts the worker, and updates listeners. Every delayed or callback-driven action must retain equivalent run/current-state checks.

## 5. Frame and coordinate data flow

```text
MediaProjection
  -> ScreenCaptureManager ImageReader (maxImages=2, dedicated HandlerThread)
  -> acquireLatestImage; copy row/pixel-strided plane into reusable RGBA Mat
  -> FrameNormalizer.contentTransform(display size + current cutout insets)
  -> center 16:9 safe-area crop -> resize to logical 1280x720
  -> LatestFrameProvider.updateFrame(raw, normalized, transform, timestamp, fps)
  -> clone from snapshot()/awaitNextSnapshot(generation, timeout)
  -> StateDetector grayscale ROI/template matching
  -> BotController handler
  -> ActionExecutor / GestureArbiter
  -> CoordinateMapper using the same ContentTransform
  -> BotAccessibilityService.dispatchGesture callback
```

Important rules:

- `LatestFrameProvider` is latest-only; it deliberately drops intermediate frames. Do not introduce a queue or process stale backlog. Its generation stays monotonic across capture clears/restarts.
- `snapshot()` and `awaitNextSnapshot()` return caller-owned cloned `Mat`s. Release them in `finally`. Release submats, gray/resized/match/encoded intermediates according to ownership; never release provider-owned storage.
- `FrameNormalizer` computes a 16:9 `contentRect` inside cutout-safe dimensions, then uses `INTER_AREA`. `ContentGeometryRegistry` and the snapshot carry the identical immutable transform used by mapping.
- General taps/swipes are logical 1280×720 coordinates. Do not add device pixels or a second offset formula. Existing fixed-device exceptions are the three legacy 2400×1080 `POST_RELAY_*_PHYSICAL` points, the `TIME_DEAL` absolute physical tap at `(2000,70)`, and the `PARTY_RUN_SEASON_REWARDS` absolute physical tap at `(1260,980)`. Keep all of these paths isolated from normalized coordinate mapping and treat them as device-dependent; do not change their calibration while making unrelated gesture changes.
- `ScreenCaptureManager` rebuilds virtual-display resources on captured-content/display geometry changes and updates cutout geometry. Avoid UI-thread frame work and per-frame unthrottled logs.

## 6. Detection and workflows

`BotStage` enumerates detectable states plus the synthetic `IN_GAME`. `StateDetector` validates 1280×720 input, converts to grayscale, restricts matching to the per-stage ROI, and uses `Imgproc.TM_CCOEFF_NORMED` with threshold `0.8`. Most stages use scale `1.0`. **Legacy** uses `0.70` through `1.30` (seven scales) for `GAME_START`/`GAME_RELAY`; the **opt-in Redfinger profile** uses only `1.0` for both and omits `GAME_START` after it was handled in the current round. Keep these profile differences, templates, exact ROIs, thresholds and stage decisions; see [REDFINGER_PERFORMANCE_PROFILE.md](REDFINGER_PERFORMANCE_PROFILE.md).

`BotController.stagesFor()` defines ordering:

- `ALWAYS`: `CONNECTION_LOST`, `INACTIVE`, prepended to every normal group.
- `PRE_GAME`: reward/daily/relic/league/treasure/party/time-deal/previous-results/settings popups, then `MAINMENU`, then `PURCHASE_ITEM` (with relic states filtered unless enabled).
- `IN_GAME`: `GAME_RELAY`, `GAME_COMPLETE`, `GAME_START`, followed by `ALWAYS`. During the 8-second Fast Start priority window it checks only `GAME_START` then `ALWAYS`.
- `POST_GAME`: `ALWAYS`, then `ANTI_BOT`, `OVERTAKE_BREAK_SCORE`, `MYSTERY_BOX`, the reward-popup subset in PRE_GAME order, and `MAINMENU`.

Normal route:

1. `MAINMENU` is revalidated on a genuinely newer frame before menu-specific work. A pending Friend Life transaction runs only from this safe point. Quick Lives and then the Start Game tap may run according to the option/round flags. If revalidation sees another state, the stale menu workflow aborts to generic detection.
2. `PURCHASE_ITEM` optionally purchases/locates the selected random boost (30-second bounded wait), taps Play, increments the round, prepares result collection, enters `IN_GAME`, and starts configured jump tapping.
3. `GAME_START` and `GAME_RELAY` optionally use owned abilities. Relay can execute the isolated physical post-relay sequence once. `GameAutoTapper` provides none/fixed/continuous jump modes.
4. Confirmed `GAME_COMPLETE` immediately records watchdog progress and commits `POST_GAME`, even if later result handling exits. It waits, requests round-result analysis, requires a fresh frame that still matches before Finish, and optionally saves result imagery.
5. `MYSTERY_BOX` classification can update the same round contribution asynchronously. Reward popups perform their current tap(s), wait for a post-action frame, and return to generic PRE_GAME/POST_GAME redetection. They do not assume a fixed popup chain; visual fingerprints and a three-attempt instance guard limit repeated action on an unchanged popup.
6. `CONNECTION_LOST` and `INACTIVE` tap Reload. They do not kill/restart either app. `ANTI_BOT` detects odd cards and taps their detected logical regions.

## 7. Gestures, callbacks, and fresh frames

- Accessibility setting enabled, live service instance, and connection are distinct checks. A dispatch return means Android accepted the gesture, **not** that it completed successfully.
- `GestureArbiter` serializes actions. `ActionExecutor` owns leases and `GestureCompletionContext` timeouts. Callback failure/timeout is failure; cancellation releases the lease. Preserve priority/preemption behavior and the global action-in-progress guard.
- For callback-sensitive popups, Friend Life, Quick Lives, and progress recovery, take the post-callback generation baseline and require `awaitNextSnapshot()` to return a strictly newer generation. A frame captured while a gesture was still executing is not post-gesture evidence.
- `minimumFreshFrameGeneration` blocks normal detection after state-changing popup actions. The current gate has a bounded 7.5-second fail-open; do not casually remove or broaden it.
- Never dispatch follow-up taps after Stop. Run-generation checks, thread-local detection generation, pending cancellation handles, and `ProgressRecoveryGate` jointly prevent stale callbacks/recovery and ordinary handlers from racing.

## 8. Friend Life, Quick Lives, and progress watchdog

### Friend Life

Friend Life and Quick Lives are separate features. The overlay saves `friendLifeIntervalMinutes` in `BotOptions` (1–1440 minutes, default 61 for existing installs) and snapshots it at Start Bot. `FriendLifeScheduler` retains scheduled/manual pending work until a safe MAINMENU begin, merges simultaneous triggers, retries failed scheduled work no earlier than 15 minutes, and schedules the next successful scheduled run using the configured interval measured from completion. Manual tests queue while running and do not move a pending scheduled due time. Changing the setting takes effect on the next Start Bot.

The controller searches for leaderboard top, traverses downward, detects Send buttons within fixed logical ROIs, taps `SEND_BUTTON -> CONFIRM_1 -> CONFIRM_2` with interrupt scans between steps, and filters relic states according to the option. Do not add an unrequested post-send button-disappearance verification or extra scans. The successful Send-button gesture—not a template match, gesture acceptance alone, or later confirmations—records watchdog progress. Naturalized paths come from `FriendSwipeProfile`; `FriendScrollVerifier` must confirm visual progress. Gesture acceptance or merely obtaining a fresh frame is not scroll progress. Current safety bounds include 30 seconds without verified progress, a 30-minute absolute traversal bound, and a 10-minute top-search bound.

### Quick Receive/Send Lives

`receiveSendLivesAfterEachGame` runs its mailbox workflow from revalidated MAINMENU when the prior round marked it pending. It uses its own templates, gesture callback waits, fresh-frame rules, interrupt handling, and 120-second deadline. Do not merge its state with Friend Life scheduling.

### Watchdog and recovery

`GameProgressWatchdogManager` checks every 30 seconds independently of detector polling. Its progress clock resets only on bot start, confirmed `GAME_COMPLETE`, and successful detected Friend Life Send-button gesture. After 15 minutes it reserves both recovery and at most one successfully delivered Telegram warning per overdue episode; failed warning delivery may retry after 5 minutes, while stale reservations are rejected.

Recovery runs on `game-progress-recovery`, activates `ProgressRecoveryGate`, and never runs handlers on the watchdog scheduler. It first scans the full normalized frame for `CONFIRM_SEND_LIFE_1` up to three times: a match is tapped at its detected center with logical radius 5, then after a successful gesture callback the continuing attempts wait `2_000` ms and acquire a strictly newer frame before rescanning. A no-match ends the Confirm loop immediately, and the third matched tap ends it after obtaining a new frame. **Whether Confirm was absent, disappeared after a tap, or reached three taps, the next stage always scans `CLOSE_BUTTON_1`**. The Close stage uses the existing grayscale `detectTemplate()` path (`TM_CCOEFF_NORMED`, match score `>= 0.8`) in the top-right quarter and stops on a no-match or after at most three matched taps. Both stages require callback success and a strictly newer post-tap frame; a failed gesture or fresh-frame timeout aborts this recovery episode rather than acting on stale evidence. The last matched tap in either loop skips the retry delay but still requires a fresh frame. After Close it checks that frame and successive fresh frames for MAINMENU for up to 120 seconds, routing to PRE_GAME on a match. The alpha-mask SQDIFF helper is not the current close path. Reservations/run generations prevent recovery from surviving new progress or a restarted bot.

## 9. Runtime configuration, statistics, Telegram, and diagnostics

- `BotOptionsStore` uses `bot_options` SharedPreferences. Defaults are defined in `BotOptions`; invalid enum/value combinations fall back safely. The active controller holds the Start-time option snapshot except `shouldSaveNoMatchImages()` and `shouldSaveResultImages()`, which are queried live.
- `RoundResultCollector` uses a single analysis executor and captures the statistics session generation. Stale async work is discarded and owned Mats are always released. `RoundResultStore` keeps at most 50 recent entries separately from cumulative session totals; replacement/delta accounting prevents a late Mystery Box update from double counting.
- `StatisticsActivity` only renders the store every second; opening/closing it does not reset a session.
- Public result images: `Pictures/CookieRunBot/results/`. Prolonged no-match raw/normalized pairs: `Pictures/CookieRunBot/debug/`. These are local diagnostics only. In-memory debug log: 500 lines (RAM preview only). Separate private rolling 10h owned-app text archive (200 MiB cap), independent 72h incidents (200 MiB cap), requested `/logs` ZIP and local **Save 10h ZIP**. Saved text logs: `Download/RobotRun/` with app-specific fallback. Persistent trace: app-specific external `diagnostics/coldstart_trace.txt` with internal fallback and rotation.
- Telegram config is stored in `telegram_notifications`; token ciphertext/IV are in separate preferences and the non-exportable AES-GCM key is in Android Keystore. Never log token-bearing URLs.
- `TelegramCommandReceiver` long-polls asynchronously and accepts read-only `/help`, `/status`, `/stats`, `/last`, `/boxes`, `/all`, `/friend`, `/health`, `/screen`, `/watchdog`, `/rounds`, `/errors`, `/update`, `/logs`, `/dashboard` (plus `/start`/`/menu` aliases and `@BotUsername` forms) only from the configured Chat ID. `/all` queues a fresh normalized **PNG document** first via Telegram `sendDocument` and then the unchanged `/stats` → `/status` → `/last` text using one session snapshot. `/screen` uses the same document path. The Telegram-only `TelegramScreenshotEncoder` converts a *clone* from RGBA to BGRA before OpenCV PNG encoding; it never changes the frame used by the detector. `TelegramScreenshotMultipart` preserves exact PNG bytes and the persistent keyboard. Existing automatic diagnostic/watchdog `sendPhoto` delivery remains unchanged. A failed, stale, unavailable, or cooldown-limited photo adds a short Screen note without suppressing the entire text reply. The persistent reply keyboard is attached to command replies and images; its **📊 All** and **🔄 Refresh** buttons map to `/all`, and **📦 Update App** maps to `/update`, and **📥 Download Logs** maps to `/logs`. `/update` checks the configured HTTPS feed and, if a newer release exists, downloads its APK to a separate private cache, verifies SHA-256, package/version and installed signer, then streams it to the authorized chat using `sendDocument`. The upload cap is 49,000,000 bytes; an oversized/invalid/unavailable APK returns in-app updater guidance. It does not remotely install or stop gameplay. A dedicated `telegram-apk-delivery` executor, single-flight guard and five-minute successful same-hash cooldown prevent interference with normal notifications. Recheck the authorized token/chat before upload. Reply authorization remains tied to the original bot token/chat. `/screen` and `/all` share an async fresh normalized cloned-frame path, rejects stale/capture-stopped frames, bypasses only the automatic image-toggle (explicit user request), and enforces a 15-second on-demand cooldown. Replies/photos recheck the authorizing token/chat before sending; configuration changes cancel pending work. `/errors` is an explicitly requested allowlisted summary; never enable automatic NO_MATCH/DETECT_STALL Telegram alerts. There is no remote gameplay control.
- Telegram/network/encoding failures must never block gameplay. NO_MATCH and DETECT_STALL remain local; the game-progress overdue path is the notification path.

### In-app update constraints

The separate main-screen **Update App** activity does not depend on Telegram, the overlay, or automation. It also offers a user-selected local APK path via Android's document picker so an update can be installed even without hosted feed; the importer still checks package name, newer version and existing signing certificate, and enforces the 250 MiB cap. It starts at v1.1.9/code 10 and accepts an owner-configured, anonymous HTTPS feed; never embed a private GitHub token or signing material. A private GitHub Actions artifact alone cannot serve as a durable anonymous distribution feed. The feed requires `packageName`, `versionCode`, `versionName`, `apkUrl`, `sha256`, and optional `notes`; the example under `docs/` is illustrative, not a live release. HTTPS redirects must not downgrade. Validate SHA-256, archive package/version and certificate against the currently installed app; download into app-private cache, use the non-exported FileProvider, and let Android request explicit user permission/confirmation. Guard against Bot or Screen Capture still running; never stop them automatically or mutate watchdog/runtime. No periodic silent update, no gameplay control and no token logging. Tests under `app/src/test/java/com/example/cookierunbot/update/`.

## 10. Threads and lifetime at a glance

| Thread/executor | Responsibility |
| --- | --- |
| Android main thread | Activities, overlay widgets, permission launchers |
| Capture `HandlerThread` | `ImageReader` latest-image conversion/normalization/publication |
| `mainmenu-detector` | 750 ms idle MAINMENU diagnostics only; skips when bot runs |
| `cookierun-bot-loop` | Detection and synchronous workflow coordination |
| Accessibility callbacks/main looper | Gesture completion/cancellation callbacks |
| `bot-diagnostics` | No-match public image writes |
| `round-result-analysis` | OCR/classification and store updates |
| `game-progress-watchdog` | Periodic overdue check/reservation |
| `game-progress-recovery` | Serialized recovery workflow |
| `telegram-notifications` / receiver worker | HTTP sends/retries and long polling; requested `/all` screenshot and `/update` feed check |
| `telegram-apk-delivery` | User-requested APK check, verification and streamed Telegram attachment; independent of watchdog notification worker |
| `robotrun-update` | In-app feed/download or offline APK verification, never detector/UI work |

Do not move blocking network/image analysis to the detector or UI thread. Do not run gameplay handlers from scheduler/callback threads.

### Overnight diagnostic archive / incident video

`RobotLog` mirrors owned app Logcat calls to the bounded `OvernightLogArchive`; direct `DebugLogStore` and `PersistentTrace` events also reach it. The 500-line Debug Log remains the fast preview and Clear affects only that preview. Text archive rotates by UTC hour and exports an exact 10h timestamp window (200 MiB safety limit, explicit `[ARCHIVE_LOSS]` after drops/pruning). The Incident manager creates one labeled folder per real watchdog Recovery reservation or accepted manual Watchdog test: previous **30 minutes** of text, current state, cloned normalized screenshot, ordered `[GAME_PROGRESS_*]` action timeline and final outcome. Incidents have independent 72h/200 MiB retention, not automatic repeated alerts. Observe recovery events without changing original templates/ROI/taps/watchdog reset semantics.

`IncidentVideoRecorder` is armed only during ordinary gameplay: no pre-roll, no recurring frame/JPEG work, and no encoder work until a real or accepted manual Watchdog Incident. The existing LatestFrameProvider is sampled best-effort at 2fps / 640x360 / JPEG quality 40, H.264 250 kbps, on lowest-priority threads. Stop at `min(recoveryFinish + 10s, trigger + 3min)`, or earlier on capture stop, persistent stale capture/slow sampling (10 degraded ticks), or 20 MiB frame limit. Under resource pressure prioritize capture/detection/Recovery, skip frames/abort optional MP4 and preserve available JPEGs/status. Capture freshness, per-sample thread CPU/wall time, and a two-second process CPU budget (>1.5 single-core equivalents) are conservative pressure proxies, not a measurement of Redfinger's system-wide CPU. Encode yields between frames and aborts if the capture session changes or pressure rises; preserve JPEG fallback. A stale prior capture-stop task must never touch a newer recording. Manual tests are rejected while another Incident is active. See IncidentVideoLoadPolicyTest and the device checklist. Manual test labels Incident MANUAL_TEST and does not reset the Watchdog progress clock. Video default enabled for this version, toggle in Debug Log. Manual authorized `/logs` ZIP via independent `telegram-log-delivery` worker (49,000,000-byte upload limit) may contain up to five newest incident folders and selected MP4/JPEG fallback, plus a full 10h log when size cap permits. Local **Save 10h ZIP** bypasses Telegram's transfer cap. Recheck token+chat authorization during file transfer and never log secrets. No automatic remote upload, no remote gameplay commands, no second MediaProjection. See [docs/OVERNIGHT_DIAGNOSTICS.md](docs/OVERNIGHT_DIAGNOSTICS.md) for retention/export/limitations and required device validation. Source/static checks do not prove a device MP4 builds/plays correctly.

### Orientation-safe calibration UI

`CoordinateCalibrationOverlay` is a separate non-gameplay WindowManager overlay, **not** a source of live tap coordinates for handlers. Its capture is a frozen 1280×720 logical image and always uses its original `ContentTransform`. The panel must resize when Android display/configuration/insets change; use a display-specific Window Context where available and keep **✕ Close** in a fixed header outside the scrollable controls. `CalibrationPanelLayoutPolicy` sizes against the usable current viewport. Reflow is layout-only: never recapture automatically, dispatch gestures, mutate the detector/ROI/threshold, change saved points or watchdog state. Unregister callbacks on close and restore the parent bubble even after an updateViewLayout failure. On rotation, instruct the user to explicitly capture a new still for current physical coordinates; the old captured contentRect is not silently remapped. See [CALIBRATION.md](CALIBRATION.md) and the UI-specific Android checklist.

## 11. Build, test, signing, and CI

From repository root (Windows commands shown because this Android project documents PowerShell as the primary shell):

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

On Unix: `./gradlew test`, `./gradlew lint`, `./gradlew assembleDebug`. Focused JVM tests can use `./gradlew testDebugUnitTest --tests 'fully.qualified.TestClass'`. Gradle's internal APK output remains `app/build/outputs/apk/debug/app-debug.apk`; the CI packaging step must rename the downloaded copy, not the file verified by apksigner.

`app/build.gradle.kts` applies optional CI signing to **debug only** when all four `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` variables exist. Release is not minified and has no repository-defined signing config. The manually dispatched `.github/workflows/build-apk.yml` uses Java 17, installs API 36/build-tools 35.0.0, restores the keystore from GitHub secrets, runs `testDebugUnitTest`, assembles/verifies a signed debug APK, and retains the artifact for 14 days. After signature verification, its packaging step reads AGP `output-metadata.json` and copies the APK to a unique filename `RobotRun-v<versionName>-vc<versionCode>-g<shortSHA>-run<runNumber>-a<runAttempt>-signed-debug.apk`. Upload Artifact uses the same stem for the ZIP name. This changes filenames only, not signing, `versionCode`, app runtime or Android application ID.

Before finishing any change: run focused tests, then appropriate full test/lint/build checks; run `git diff --check`; inspect `git diff` and `git status`; report failures and unverified device behavior precisely. A manual GitHub Actions artifact does not establish a public app-update endpoint. The HTTPS update feed, SHA-256 and same signing certificate need operator setup; source changes alone do not publish an update or refresh an old installed Telegram keyboard. See `ANDROID_TEST_CHECKLIST.md` for phone checks and [DEVELOPMENT_HANDOFF.md](DEVELOPMENT_HANDOFF.md) for handoff and document-sync rules.
