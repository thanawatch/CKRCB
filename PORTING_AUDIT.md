# Current Android implementation audit

This document is a technical walkthrough of the code at HEAD. It intentionally contains no historical comparison, resolved-bug narrative, or planned feature claims. [AGENTS.md](AGENTS.md) is the operational guide; this file explains the current execution paths.

## Startup and ownership graph

1. Android creates `CookieRunBotApplication`; `onCreate()` initializes `PersistentTrace`, OpenCV, `BotRuntime`, `TelegramCommandReceiver`, and runtime diagnostics.
2. `MainActivity` attaches a `BotController.Listener`, renders runtime/capture diagnostics, and launches the system MediaProjection consent intent.
3. Successful consent calls `ScreenCaptureService.start()`. The foreground service builds `StateDetector`, starts `ScreenCaptureManager`, creates `BotControlOverlay`, and schedules idle MAINMENU detection every 750 ms. That idle detector explicitly exits while `BotRuntime.isRunning()` to avoid a competing OpenCV pass.
4. `BotRuntime` owns exactly one `BotController` using application context. Activity detach only removes the UI listener. `ScreenCaptureService.shutdownResources()` dismisses the bubble, stops the bot, closes detector/capture resources, and marks capture stopped.
5. The overlay builds/persists `BotOptions`; Start invokes `BotRuntime.start(options)`, while Stop invokes `BotRuntime.stop()`.

## Capture-to-action trace

`ScreenCaptureManager.start()` obtains MediaProjection and registers callbacks. A dedicated capture thread owns ImageReader/VirtualDisplay resources. `acquireLatestImage()` prevents backlog; the plane's row/pixel strides are copied into a tight reusable RGBA Mat. Current display size and cutout insets produce a `ContentTransform`; `FrameNormalizer.normalize()` crops its 16:9 `contentRect` and resizes to 1280×720. Both raw and normalized Mats are supplied to `LatestFrameProvider.updateFrame()`, which copies only the latest normalized frame, publishes dimensions/timestamps/FPS/transform, increments generation, and wakes waiters.

Consumers receive cloned ownership through `snapshot()` or `awaitNextSnapshot(afterGeneration, timeout)`. `StateDetector.detectFirst()` converts the screen to reusable grayscale storage, creates each ROI submat, and evaluates loaded template variants using normalized correlation. A match is `score >= 0.8`. Detection results contain stage, score, ROI-relative location, and selected template size.

A handler requests a logical gesture through `ActionExecutor`. `CoordinateMapper` uses the current `ContentTransform` to map normalized detector/action coordinates into the physical display. `GestureArbiter` grants a single lease; `BotAccessibilityService.dispatchGesture()` reports initial acceptance and later invokes completion/cancellation. `GestureCompletionContext` releases the lease on callback, cancellation, or timeout. Workflows that need visual proof establish their baseline after successful completion and wait for `generation > baseline`.

## Controller loop and groups

`BotController.start()` creates a new statistics session and run generation, initializes PRE_GAME state, scheduler/watchdog/popup/no-match fields, and submits `runLoop()` to a single executor. `waitForReadiness()` requires capture running, advancing/fresh frames, Accessibility setting enabled, live service instance, and service connection. The loop owns one `StateDetector` until stop.

- PRE_GAME and POST_GAME poll the current latest snapshot and sleep 120 ms between ticks.
- IN_GAME blocks on `awaitNextSnapshot(lastGeneration, 500)` and therefore processes only newly published latest frames.
- Every tick rechecks run, recovery gate, capture, Accessibility, maximum 1-second frame age, previous-results settling, and minimum fresh generation.
- `detectFirst()` uses ordered lists from `stagesFor()`. ALWAYS (`CONNECTION_LOST`, `INACTIVE`) is checked in all normal groups. Relic stages are removed when the option is off.
- No match produces throttled score diagnostics. With debug saving enabled, `NoMatchWatchdog` asks the capture publisher for the next raw/normalized pair and writes it asynchronously; it never triggers taps/restarts/Telegram.

### Current stage order

- PRE_GAME after ALWAYS: `CONGRATULATIONS`, `LEVEL_UP`, `RELIC_COMPLETE`, `RELIC_CLAIM`, `DAILY_CHECKIN`, `DAILY_CHECKIN_BOOST_SET`, `DAILY_TREASURE`, `DAILY_NEW`, `ENTER_LEAGUE`, `LEAGUE_RESULTS`, `PREVIOUS_RANK_RESULTS`, `TOO_MANY_TREASURES`, `PARTY_RUN`, `PARTY_RUN_SEASON_REWARDS`, `TIME_DEAL`, `PREVIOUS_RESULTS`, `GAME_SETTINGS`, `MAINMENU`, `PURCHASE_ITEM`.
- IN_GAME: `GAME_RELAY`, `GAME_COMPLETE`, `GAME_START`, then ALWAYS. The temporary Fast Start path checks `GAME_START`, then ALWAYS.
- POST_GAME after ALWAYS: `ANTI_BOT`, `OVERTAKE_BREAK_SCORE`, `MYSTERY_BOX`, PRE_GAME reward-popup stages in their existing order, and `MAINMENU`.

## Main gameplay trace

- **MAINMENU:** `revalidateMainMenu()` waits for a newer frame, scans ALWAYS and blocking PRE_GAME states, and confirms MAINMENU again. It aborts stale work if another screen wins. Pending scheduled/test Friend Life begins here. Quick Lives may run for a completed round. Start Game is tapped only after applicable workflows and another safe revalidation.
- **PURCHASE_ITEM:** optional desired boost taps the random item and scans the fixed boost region on fresh frames for up to 30 seconds. The controller then taps Play, increments/initializes the round, marks post-game life work, enters IN_GAME, opens the Fast Start window, and starts jump automation.
- **GAME_START / GAME_RELAY:** configured owned actions are tapped once per relevant state. Relay may run the explicitly isolated three-point absolute physical sequence. Fixed-count/continuous jump taps are managed by `GameAutoTapper` and stop on lifecycle/state exits.
- **GAME_COMPLETE:** detection resets progress and commits POST_GAME before result handling. After its current settle time, the collector receives a clone for asynchronous score/coin/XP analysis. A newer frame must still match GAME_COMPLETE before Finish. Optional result saving reads its preference live.
- **POST_GAME:** Mystery Box saves/classifies and updates the existing round; reward screens are dismissed by specialized or generic handlers. Popup actions use callback/fresh-frame gates where required, return to generic group scanning rather than a predicted chain, and guard unchanged fingerprints against repeated taps.
- **Recovery states:** Connection Lost and Inactive tap Reload; Anti-bot chooses detected odd cards. No handler force-stops/restarts an app.

## Friend Life and Quick Lives audit

`FriendLifeScheduler` separates schedule state, manual test state, in-progress state, and retry-not-before. Scheduled work becomes pending after 61 minutes but starts only from revalidated MAINMENU. Failed scheduled work remains pending with a 15-minute delay; completed work schedules from completion time. A manual test is queued, coalesced with a scheduled trigger when appropriate, rejected if stopped, and reported if already queued/in progress.

The controller obtains fresh scans of top/bottom/send ROIs. Top search uses bounded upward naturalized paths; traversal uses bounded downward paths. Every accepted swipe waits for callback success, establishes a post-callback generation baseline, obtains a fresh frame, and asks `FriendScrollVerifier` whether the verification ROI actually changed. Send transactions scan interrupts between Send and both confirmations. A successful Send gesture records game progress immediately. Safety decisions are based on visual progress, 30-second no-progress time, 10-minute top search, and 30-minute absolute traversal time.

Quick Lives is a separate mailbox transaction enabled per-round. It uses `ALL_LIVES_RECEIVED_AND_SENT_1`, `NO_LIVES_TO_RECEIVE_1`, and confirmation templates/policies, its own callback/fresh-frame checks and 120-second bound. Its flags and scheduler state are not shared with Friend Life.

## Watchdog and recovery audit

`GameProgressWatchdog` is generation/reservation based. `GameProgressWatchdogManager.start()` creates a scheduled single-thread checker, records BOT_START, and checks every 30 seconds. The only subsequent progress reasons are confirmed GAME_COMPLETE and successful Friend Life SEND_BUTTON gesture. At 15 minutes overdue, it independently reserves recovery and alert work.

The alert captures the latest normalized snapshot, optionally PNG-encodes it, and submits asynchronously to Telegram. Delivery is committed only on HTTP success; failure schedules eligibility 5 minutes later. A successful alert suppresses more alerts until new progress starts another episode.

Recovery is handed to `BotController` and executed on its recovery executor. A current run/reservation check activates `ProgressRecoveryGate`, blocking normal dispatch. The workflow first seeks `CONFIRM_SEND_LIFE_1`; if absent, top-right `CLOSE_BUTTON_1` fallback is bounded to three attempts with 2-second retry delays. Callback success plus a newer frame is mandatory after every tap. It then scans successive fresh frames for MAINMENU for at most 120 seconds; a match routes the normal controller to PRE_GAME. Completion clears only the matching reservation/gate.

## Configuration and UI audit

`BotOptions` validates tap interval 100–1000 ms, positive fixed jump count, and the dependency of post-relay sequence on Cookie Relay. `BotOptionsStore` persists all overlay fields in SharedPreferences. Start takes an immutable option snapshot. Debug/result image switches also write immediately and are read dynamically by save sites; other edited controls require a new Start to affect the controller.

`MainActivity` is a programmatic diagnostic screen; `BotControlOverlay`, `StatisticsActivity`, `DebugLogActivity`, and `TelegramSettingsActivity` are also programmatic UI. There are no layout XML files. Statistics refresh every second without changing session state. Debug Log supports copy/clear/save. Telegram settings support enable, token/Chat ID, image/text preferences, save, and a manual test message.

## Statistics, storage, and diagnostics audit

`RoundResultCollector` owns one analyzer executor and snapshots `RoundResultStore.sessionGeneration()`. It uses `ResultNumberDetector` and `MysteryBoxClassifier`; results from an old session generation are discarded. `RoundResultStore` synchronizes access, retains 50 recent rounds, and maintains aggregate contribution records so replacement updates contribute only a delta.

`PublicImageStore` uses MediaStore/public collections on modern Android and legacy public paths where applicable. Results go to `Pictures/CookieRunBot/results`; no-match pairs go to `Pictures/CookieRunBot/debug`. `DebugLogStore` bounds memory at 500 messages. `RuntimeDiagnosticsLogger` emits throttled health/DETECT_STALL context. `PersistentTrace` writes/rotates an app-specific cold-start trace. None of these diagnostic signals authorize recovery actions beyond the explicitly wired progress watchdog.

## Telegram audit

`TelegramConfigStore` stores nonsecret settings in SharedPreferences and delegates token encryption to `TelegramSecureTokenStore` (AES-GCM with an Android Keystore key); it migrates a legacy plaintext preference if encountered. `TelegramNotifier` uses a single scheduled executor, bounded HTTP timeouts, token-redacted logging, and retry handling for command replies. Gameplay does not wait for it.

`TelegramCommandReceiver` long-polls `getUpdates`, persists update offset, parses only `/help`, `/status`, `/stats`, `/last`, and `/boxes`, and ignores unauthorized Chat IDs. Replies capture a hash identity of the authorizing token plus Chat ID; a retry cancels if current settings differ. The receiver does not implement gameplay commands.

## Build and verification audit

The app module uses Android Gradle Plugin 8.10.1, Kotlin 2.1.20, Gradle wrapper 8.11.1, Java/Kotlin 17, compile/target SDK 36, min SDK 26, Activity KTX 1.10.1, OpenCV 4.13.0, and JUnit 4.13.2. Debug signing switches to the `ci` config only when all four signing environment variables are present. Release minification is disabled and no release signing is declared.

`.github/workflows/build-apk.yml` is manual. It checks out, prepares Java/Gradle/API 36, restores and validates a secret-backed keystore, runs `./gradlew testDebugUnitTest`, runs `assembleDebug`, verifies the signature with build-tools 35.0.0, and uploads the debug APK for 14 days.

Use `./gradlew test`, `./gradlew lint`, and `./gradlew assembleDebug`; then perform the real-device checks in [ANDROID_TEST_CHECKLIST.md](ANDROID_TEST_CHECKLIST.md).
