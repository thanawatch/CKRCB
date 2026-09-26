# AI coding-agent guide

## 1. Source of truth and working rules

This repository is the native Android **RobotRun** automation app for Cookie Run Classic. The current files under `app/src/main/` are authoritative. Read the relevant Kotlin, manifest, resources, assets, and tests before changing behavior; documentation is an index, not a substitute for call-site inspection.

- Work only in this repository unless the task explicitly says otherwise. Inspect `git status` first and preserve unrelated edits.
- Keep changes narrow. Never commit tokens, Chat IDs, captured screens, logs containing user data, signing material, or generated `build/`, `.gradle/`, `.kotlin/`, or IDE files.
- Never infer behavior from old commits or porting history. `PORTING_STATUS.md` is the concise current capability map and `PORTING_AUDIT.md` is the current technical audit.
- Capture and gestures are device-dependent. Do not claim phone verification unless it was actually performed.
- Search every caller before changing a public/helper contract. Most policy helpers have focused JVM tests under `app/src/test/java/com/example/cookierunbot/`.

## 2. Project and entry points

- Package/application ID: `com.example.cookierunbot`; app label: RobotRun; min/target/compile SDK: 26/36/36; Kotlin/JVM 17.
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
| Telegram | `notifications/TelegramConfig*.kt`, `TelegramSecureTokenStore.kt`, `TelegramNotifier.kt`, `TelegramCommandReceiver.kt` |
| Round statistics | `statistics/RoundResultCollector.kt`, `RoundResultStore.kt`, `ResultNumberDetector.kt`, `MysteryBoxClassifier.kt` |
| Public images and diagnostics | `PublicImageStore.kt`, `ResultImageSaver.kt`, `NoMatchDebugFrameSaver.kt`, `RuntimeDiagnosticsLogger.kt`, `diagnostics/` |
| Build/release automation | `app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/build-apk.yml` |

## 4. What Start Bot actually does

`BotControlOverlay` validates/builds `BotOptions`, persists them, and calls `BotRuntime.start(options)`. `BotRuntime` forwards to its single process-level `BotController`.

`BotController.start()`:

1. Rejects a duplicate start under its lock, stores the option snapshot, increments the run generation, resets the recovery gate/state/group/round/popup/no-match fields, and starts a new `RoundResultCollector` session.
2. Starts `FriendLifeScheduler` (first scheduled run is due after 61 minutes when enabled).
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
- General taps/swipes are logical 1280×720 coordinates. Do not add device pixels or a second offset formula. The three `POST_RELAY_*_PHYSICAL` points are explicit legacy 2400×1080 exceptions; keep them isolated.
- `ScreenCaptureManager` rebuilds virtual-display resources on captured-content/display geometry changes and updates cutout geometry. Avoid UI-thread frame work and per-frame unthrottled logs.

## 6. Detection and workflows

`BotStage` enumerates detectable states plus the synthetic `IN_GAME`. `StateDetector` validates 1280×720 input, converts to grayscale, restricts matching to the per-stage ROI, and uses `Imgproc.TM_CCOEFF_NORMED` with threshold `0.8`. Most stages use scale `1.0`; `GAME_START` and `GAME_RELAY` use `0.70` through `1.30`. Template paths, exact ROIs, auxiliary templates, alpha masks, and scale lists live only in `StateDetector`.

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

Friend Life and Quick Lives are separate features. `FriendLifeScheduler` retains scheduled/manual pending work until a safe MAINMENU begin, merges simultaneous triggers, retries failed scheduled work no earlier than 15 minutes, and schedules the next successful run 61 minutes after completion. Manual tests queue while running.

The controller searches for leaderboard top, traverses downward, detects Send buttons within fixed logical ROIs, taps `SEND_BUTTON -> CONFIRM_1 -> CONFIRM_2` with interrupt scans between steps, and filters relic states according to the option. The successful Send-button gesture—not confirmations—records watchdog progress. Naturalized paths come from `FriendSwipeProfile`; `FriendScrollVerifier` must confirm visual progress. Gesture acceptance or merely obtaining a fresh frame is not scroll progress. Current safety bounds include 30 seconds without verified progress, a 30-minute absolute traversal bound, and a 10-minute top-search bound.

### Quick Receive/Send Lives

`receiveSendLivesAfterEachGame` runs its mailbox workflow from revalidated MAINMENU when the prior round marked it pending. It uses its own templates, gesture callback waits, fresh-frame rules, interrupt handling, and 120-second deadline. Do not merge its state with Friend Life scheduling.

### Watchdog and recovery

`GameProgressWatchdogManager` checks every 30 seconds independently of detector polling. Its progress clock resets only on bot start, confirmed `GAME_COMPLETE`, and successful detected Friend Life Send-button gesture. After 15 minutes it reserves both recovery and at most one successfully delivered Telegram warning per overdue episode; failed warning delivery may retry after 5 minutes, while stale reservations are rejected.

Recovery runs on `game-progress-recovery`, activates `ProgressRecoveryGate`, and never runs handlers on the watchdog scheduler. It looks for `CONFIRM_SEND_LIFE_1`; otherwise it may try `CLOSE_BUTTON_1` in the top-right ROI up to three times. Each tap requires callback success and a newer frame. It then watches fresh frames for MAINMENU for up to 120 seconds and routes to PRE_GAME if found. Reservations/run generations prevent recovery from surviving new progress or a restarted bot.

## 9. Runtime configuration, statistics, Telegram, and diagnostics

- `BotOptionsStore` uses `bot_options` SharedPreferences. Defaults are defined in `BotOptions`; invalid enum/value combinations fall back safely. The active controller holds the Start-time option snapshot except `shouldSaveNoMatchImages()` and `shouldSaveResultImages()`, which are queried live.
- `RoundResultCollector` uses a single analysis executor and captures the statistics session generation. Stale async work is discarded and owned Mats are always released. `RoundResultStore` keeps at most 50 recent entries separately from cumulative session totals; replacement/delta accounting prevents a late Mystery Box update from double counting.
- `StatisticsActivity` only renders the store every second; opening/closing it does not reset a session.
- Public result images: `Pictures/CookieRunBot/results/`. Prolonged no-match raw/normalized pairs: `Pictures/CookieRunBot/debug/`. These are local diagnostics only. In-memory debug log: 500 lines. Saved text logs: `Download/RobotRun/` with app-specific fallback. Persistent trace: app-specific external `diagnostics/coldstart_trace.txt` with internal fallback and rotation.
- Telegram config is stored in `telegram_notifications`; token ciphertext/IV are in separate preferences and the non-exportable AES-GCM key is in Android Keystore. Never log token-bearing URLs.
- `TelegramCommandReceiver` long-polls asynchronously and accepts read-only `/help`, `/status`, `/stats`, `/last`, `/boxes` (including `@BotUsername` forms) only from the configured Chat ID. Reply retries remain bound to the authorizing token identity/chat and cancel if config changes. There is no remote gameplay control.
- Telegram/network/encoding failures must never block gameplay. NO_MATCH and DETECT_STALL remain local; the game-progress overdue path is the notification path.

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
| `telegram-notifications` / receiver worker | HTTP sends/retries and long polling |

Do not move blocking network/image analysis to the detector or UI thread. Do not run gameplay handlers from scheduler/callback threads.

## 11. Build, test, signing, and CI

From repository root (Windows commands shown because this Android project documents PowerShell as the primary shell):

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

On Unix: `./gradlew test`, `./gradlew lint`, `./gradlew assembleDebug`. Focused JVM tests can use `./gradlew testDebugUnitTest --tests 'fully.qualified.TestClass'`. APK output is `app/build/outputs/apk/debug/app-debug.apk`.

`app/build.gradle.kts` applies optional CI signing to **debug only** when all four `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` variables exist. Release is not minified and has no repository-defined signing config. The manually dispatched `.github/workflows/build-apk.yml` uses Java 17, installs API 36/build-tools 35.0.0, restores the keystore from GitHub secrets, runs `testDebugUnitTest`, assembles/verifies a signed debug APK, and retains the artifact for 14 days.

Before finishing any change: run focused tests, then appropriate full test/lint/build checks; run `git diff --check`; inspect `git diff` and `git status`; report failures and unverified device behavior precisely. See `ANDROID_TEST_CHECKLIST.md` for phone checks.
