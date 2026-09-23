# Instructions for coding agents

## Source of truth and scope

- Work only in this Android repository unless the task explicitly authorizes another project.
- Inspect current Kotlin, manifest, resources, and tests before relying on this guide. Source code is authoritative; this file is a map of invariants, not a replacement for source inspection.
- Keep changes narrowly scoped. Preserve user changes and inspect `git status` before editing.
- Do not copy behavior from historical porting notes unless current Android source confirms it.
- Do not store tokens, Chat IDs, user data, screenshots, or other secrets in source or docs.

## Ownership map

| Area | Primary owner(s) |
| --- | --- |
| App startup and diagnostic UI | `CookieRunBotApplication.kt`, `MainActivity.kt` |
| Floating settings and Start/Stop controls | `automation/BotControlOverlay.kt`, `BotOptions.kt`, `BotOptionsStore.kt` |
| Process-level controller access | `automation/BotRuntime.kt` |
| Bot loop, state groups, handlers and workflow coordination | `automation/BotController.kt`, `BotStateMachine.kt` |
| Templates, ROIs, matching and stage results | `detection/StateDetector.kt`, `app/src/main/assets/templates/` |
| MediaProjection service and frame acquisition | `capture/ScreenCaptureService.kt`, `ScreenCaptureManager.kt` |
| Latest-frame storage and synchronization | `capture/LatestFrameProvider.kt` |
| Content crop and normalized image | `capture/FrameNormalizer.kt`, `ContentGeometryRegistry.kt` |
| Logical-to-device coordinate transform | `automation/CoordinateMapper.kt`, `AutomationConfig.kt` |
| Accessibility gestures and gesture arbitration | `automation/BotAccessibilityService.kt`, `ActionExecutor.kt` |
| Friend Life policy/helpers | `FriendLifeScheduler.kt`, `FriendSwipeProfile.kt`, `FriendScrollVerifier.kt`, `BotController.kt` |
| No-match, lifecycle and runtime diagnostics | `NoMatchWatchdog.kt`, `NoMatchDebugFrameSaver.kt`, `RuntimeDiagnosticsLogger.kt`, `diagnostics/` |
| Progress notification and Telegram | `GameProgressWatchdog.kt`, `GameProgressWatchdogManager.kt`, `notifications/` |
| Round analysis and cumulative session totals | `statistics/RoundResultCollector.kt`, `RoundResultStore.kt`, related detectors/models |
| Public image storage and round screenshots | `PublicImageStore.kt`, `ResultImageSaver.kt`, `NoMatchDebugFrameSaver.kt` |

Tests are under `app/src/test/java/com/example/cookierunbot/`, grouped by automation, notifications, and statistics.

## Architecture invariants

### Capture and frame ownership

- The app uses a foreground `ScreenCaptureService` and user-approved MediaProjection. Do not tie the controller loop to `MainActivity` lifecycle.
- `LatestFrameProvider` is latest-frame-only. Do not add a frame queue or process stale backlog. Use its generation and `awaitNextSnapshot()` when a genuinely newer frame is required.
- Detection consumes normalized logical frames at 1280×720. `FrameNormalizer` derives the content viewport from the current source geometry and cutout insets, then resizes the crop.
- Use the same `ContentTransform` for normalization and logical tap mapping. Do not add a second crop/offset formula in detector or action code.
- OpenCV `Mat` values returned as snapshots are clones owned by the caller. Release them in `finally`; release temporary `submat`, gray, resized, and encoded-image intermediates according to their owner. Do not release provider-owned Mats.
- Do not create per-frame allocations on the UI thread or log every frame. Preserve throttled diagnostics.

### Coordinates and gestures

- Standard automation points and Friend Life swipe paths are expressed in logical 1280×720 space and mapped through the current geometry by `CoordinateMapper`/`ActionExecutor`.
- Never add fixed physical device coordinates or ADB-era scale/offset formulas to general actions. `AutomationConfig` currently contains explicit legacy `POST_RELAY_*_PHYSICAL` targets; keep that special path isolated and do not extend it to other actions without an explicit calibration task.
- Accessibility enabled and Accessibility service connected are separate states. Before dispatch, preserve readiness checks and distinguish gesture acceptance from its completion callback. A callback timeout/failure must not be treated as success.
- Preserve `GestureArbiter`/executor serialization and action-in-progress safeguards. Do not dispatch follow-up taps after Stop Bot.
- For state-changing actions, wait for a fresh generation established after the gesture callback where the current workflow requires it. A frame captured during a gesture must not satisfy a post-gesture requirement.

### Detection and state machine

- `StateDetector` owns template paths, stage ROIs, match method, scale variants, and match threshold. Do not change templates, ROIs, threshold, or stage ordering to hide a capture/normalization issue.
- `BotController` owns the current grouped ordering: `PRE_GAME`, `IN_GAME`, and `POST_GAME`; `CONNECTION_LOST` and `INACTIVE` are always-priority candidates. Read `stagesFor()` and detector tables before adding a stage.
- Keep existing `GAME_START`, `GAME_RELAY`, `GAME_COMPLETE`, purchase/boost, post-game, and option-specific workflow boundaries intact unless the task specifically changes them.
- Reward popups return to generic PRE_GAME redetection after the required fresh frame. Do not introduce a fixed expected-next-popup chain. Preserve ALWAYS-state priority and popup-instance retry limits.
- Keep MAINMENU revalidation before MAINMENU-specific workflows and taps. If a fresh frame shows another state, abort the stale MAINMENU workflow so the normal detector can handle that state.
- Connection Lost behavior is Reload-only. Do not force-stop, kill, or restart Cookie Run or RobotRun as an automatic recovery mechanism.

### Friend Life and Quick Lives

- Friend Life and Quick Receive/Send Lives are separate workflows; do not merge their scheduling, detection, taps, or completion state.
- Preserve Friend Life scheduler semantics, pending scheduled/manual test requests, bounded retries, safe MAINMENU transaction start, interrupt checks between SEND/confirm steps, and relic filtering policy.
- Preserve Friend Life regions, templates, thresholds, current swipe center paths and bounded naturalized gesture behavior unless the task explicitly requests a calibration/change. `FriendScrollVerifier` is the authority for whether a swipe counts as actual scroll progress; gesture acceptance or a fresh frame alone is not progress.
- Friend Life has both no-progress and absolute safety limits. Read current constants rather than copying values from old requests/docs.
- The progress watchdog resets on Bot Start, detected `GAME_COMPLETE`, and successful gesture of the detected `FRIEND_SEND_LIFE_1` SEND button. Do not move that Friend Life reset after confirmations or add post-send verification, extra scans, or waits.

### Statistics and async work

- A new `BotController.start()` begins a new statistics session through `RoundResultCollector`/`RoundResultStore`.
- `RoundResultStore` keeps bounded recent history separately from cumulative session aggregates. Preserve contribution replacement/delta behavior so a late Mystery Box update does not double-count a round.
- Async result analysis captures a session generation. Stale work from an earlier run must not write to a new session. Keep image analysis off the detector thread and release owned Mats even on discard/failure.
- Statistics screen lifecycle must not reset a bot session.

### Telegram and diagnostics

- Reuse `TelegramConfig`, `TelegramConfigStore`, `TelegramNotifier`, and `TelegramCommandReceiver`; do not add duplicate credential or allowlist settings.
- The configured `TelegramConfig.chatId` is the sole authorized incoming-command destination. Command replies must stay bound to the config that authorized the update and be canceled if that target no longer matches current settings.
- Only the current read-only commands are supported: `/help`, `/status`, `/stats`, `/last`, `/boxes`. Do not add remote gameplay control unless explicitly requested.
- Keep Telegram I/O asynchronous. Network errors, bad configuration, failed photo encoding, or Telegram API failures must not stop or block gameplay. Never log tokens or token-bearing API URLs.
- The Game Progress Watchdog runs independently from the detector loop; preserve its one-successful-alert-per-overdue-episode and stale-reservation semantics. Do not add another NO_MATCH/DETECT_STALL Telegram alert path: those conditions currently produce local diagnostics only.
- `NO_MATCH` debug saving and `[DETECT_STALL]`/health logs are diagnostic signals, not permission to restart or tap the game.

## Safe change workflow

1. Read the relevant current source and focused tests. Search for every call site of any behavior being changed.
2. Check `git status`; avoid overwriting unrelated user edits.
3. Make the smallest change that preserves the invariants above. Do not edit binary templates unless explicitly requested.
4. Add or update focused tests for policy/state helpers when the requested task changes those behaviors.
5. Run the requested checks. Standard commands from the project root are:

   ```powershell
   .\gradlew.bat test
   .\gradlew.bat lint
   .\gradlew.bat assembleDebug
   ```

6. Review the final diff and report files changed, behavior changed, checks actually run and any failures or unverified device-specific behavior.

Avoid generated artifacts (`build/`, `.gradle/`, `.kotlin/`, IDE state) in source reviews and documentation. Do not claim a phone-level test was run unless it actually was.
