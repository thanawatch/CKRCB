# RobotRun Android

RobotRun is a native Android automation app for Cookie Run Classic. With explicit user permission it captures the screen through MediaProjection, normalizes the game viewport to 1280×720, identifies current screens with OpenCV template matching, and dispatches configured taps/swipes through an AccessibilityService.

The current Android source is authoritative. Coding agents should read [AGENTS.md](AGENTS.md); deeper current-code flow is in [PORTING_AUDIT.md](PORTING_AUDIT.md), the concise capability map is [PORTING_STATUS.md](PORTING_STATUS.md), and phone verification is in [ANDROID_TEST_CHECKLIST.md](ANDROID_TEST_CHECKLIST.md).

## Requirements and build

- Android API 26 minimum; compile/target SDK 36
- JDK 17; Gradle wrapper 8.11.1
- Kotlin 2.1.20 / Android Gradle Plugin 8.10.1
- OpenCV Android 4.13.0

From the repository root:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Windows PowerShell equivalents use `gradlew.bat`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The app module optionally signs the debug build with a CI keystore only when all required `ANDROID_KEYSTORE_*` environment variables are set. The manual GitHub Actions workflow tests, creates and verifies that signed debug APK, then uploads it as a 14-day artifact. No release signing/publishing workflow is defined.

## First use

1. Launch RobotRun and enable its Accessibility service from **Open Accessibility Settings**.
2. Grant **Display over other apps** so the floating bubble can be shown.
3. Optionally grant battery-optimization exemption to reduce background interruption.
4. Press **Start Screen Capture** and accept Android's MediaProjection prompt. On Android 13+, allow notifications for the required foreground-service notification.
5. Open Cookie Run Classic, open the floating **BOT** bubble, configure options, and press **Start Bot**.
6. **Stop Bot** stops automation. **Stop Screen Capture** independently tears down capture and also stops the bot/dismisses the overlay.

Permissions remain user-controlled. RobotRun cannot silently enable Accessibility, overlay, MediaProjection, notification, or battery settings.

## Runtime in one view

```text
CookieRunBotApplication
  -> BotRuntime (process-owned BotController)

MainActivity --MediaProjection consent--> ScreenCaptureService + floating BotControlOverlay
                                          |
                                          v
ScreenCaptureManager (latest Image on capture thread)
  -> FrameNormalizer (cutout-safe 16:9 crop -> 1280x720)
  -> LatestFrameProvider (latest only + monotonic generation)
  -> BotController / StateDetector (OpenCV grayscale ROI matching)
  -> ActionExecutor / GestureArbiter
  -> CoordinateMapper (same content transform)
  -> BotAccessibilityService gesture + completion callback
```

The service runs an idle MAINMENU diagnostic detector every 750 ms only while the bot is stopped. Starting the bot creates one detector-loop worker. It first waits for an advancing fresh frame and for Accessibility to be both enabled and connected. PRE_GAME and POST_GAME poll the latest snapshot; IN_GAME waits for each newer generation. Snapshots are cloned OpenCV Mats owned and released by the consumer.

## Current automation

The controller scans ordered groups rather than every state indiscriminately:

- **PRE_GAME:** reward/daily/league/relic/party/settings screens, MAINMENU, PURCHASE_ITEM.
- **IN_GAME:** GAME_RELAY, GAME_COMPLETE, GAME_START, with a temporary Fast Start priority path.
- **POST_GAME:** anti-bot, score/mystery-box/result/reward screens, then MAINMENU.
- **Always priority:** CONNECTION_LOST and INACTIVE.

At MAINMENU the app obtains a newer frame and revalidates the screen before scheduled Friend Life, per-round Quick Lives, or Start Game. PURCHASE_ITEM can search up to 30 seconds for the selected boost, then Play enters IN_GAME and configured jump tapping begins. Confirmed GAME_COMPLETE records progress, commits POST_GAME, analyzes a cloned result frame asynchronously, and requires a newer frame that still matches before Finish. Reward handling returns to general redetection; it does not assume a fixed next-popup chain. Connection Lost/Inactive only tap Reload.

The overlay persists Fast Start, Cookie Relay/post-relay sequence, desired boost, relic detection, scheduled Friend Life and its interval in whole minutes (1–1440; default 61), per-round receive/send lives, debug/result image saving, and jump mode/count/interval. Most options are snapshotted at Start Bot; image-save switches are read live.

## Fresh frames and gesture safety

Normal coordinates are expressed in logical 1280×720 space. The crop transform used to normalize the image is also used to map gestures to the display. Fixed-device exceptions bypassing that mapping are the three explicitly named post-relay physical points (calibrated for 2400×1080), the TIME_DEAL tap at absolute physical `(2000,70)`, and the PARTY_RUN_SEASON_REWARDS tap at absolute physical `(1260,980)`; each requires device-specific verification.

Android accepting `dispatchGesture()` is not the same as completion. Actions are globally serialized, callback timeouts/cancellations are failures, Stop invalidates delayed callbacks, and state-changing workflows wait for a frame generation established after successful gesture completion. This prevents a frame captured during a gesture from being treated as its result.

## Friend Life, Quick Lives, and progress recovery

Scheduled Friend Life first becomes due the user-selected number of whole minutes after Start Bot (default 61), then again that interval after each successful scheduled run, and like a queued manual test starts only from a revalidated MAINMENU. It searches the leaderboard, visually verifies each naturalized scroll, scans Send buttons, and checks interrupt states between Send and two confirmations. It has independent no-progress/top-search/absolute bounds. A successful Send-button gesture immediately counts as game progress.

Quick Receive/Send Lives is a separate after-round mailbox workflow with its own templates, callback/fresh-frame rules, interrupts, and timeout.

The game-progress watchdog checks independently every 30 seconds. If no bot-start, confirmed GAME_COMPLETE, or Friend Send event occurs for 15 minutes, it may run a gated confirmation/close recovery and send one successfully delivered Telegram warning for that overdue episode. The current CLOSE_BUTTON fallback uses ordinary grayscale `TM_CCOEFF_NORMED` matching at threshold 0.8 in the top-right quarter, not alpha-masked SQDIFF matching. It stops on a no-match and permits at most three matched taps. Each tap must complete successfully; continuing attempts wait 2 seconds before establishing a generation baseline and acquiring a newer frame, while the final tap omits the retry delay but still requires a newer frame. Recovery then checks that frame and successive fresh frames for MAINMENU for up to 120 seconds and routes a match to PRE_GAME. Failed alerts can retry after five minutes. Local no-match/DETECT_STALL diagnostics do not send their own alerts.

## Statistics, images, and logs

A new Start Bot creates a new statistics session. Result OCR/classification runs on a separate executor and cannot write across session generations. Up to 50 recent rounds are kept, while cumulative totals use contribution replacement so late Mystery Box classification does not double-count.

- Result images: public `Pictures/CookieRunBot/results/`
- No-match raw/normalized pairs: public `Pictures/CookieRunBot/debug/`
- Saved text logs: public `Download/RobotRun/`, with app-specific fallback
- Persistent trace: app-specific `diagnostics/coldstart_trace.txt`, with internal fallback
- In-memory Debug Log: latest 500 lines

Captured images may contain personal information and can be visible to gallery/file applications. Do not share them or logs without inspection.

## Telegram

Telegram settings store the bot token encrypted with an Android Keystore-backed AES-GCM key. The configured Chat ID is both the notification destination and the only authorized incoming-command chat. Networking and encoding run asynchronously and failures do not stop gameplay.

Supported read-only commands are `/help`, `/status`, `/stats`, `/last`, and `/boxes`, including group-chat `@BotUsername` forms. There is no remote Start/Stop or gameplay control. Never commit credentials or paste token-bearing URLs into logs/issues.

## Troubleshooting

| Symptom | Verify |
| --- | --- |
| No capture/frames | Re-consent to MediaProjection, check foreground notification, source size, generation, FPS, and frame age. |
| Bubble missing | Grant overlay permission and restart capture. |
| Actions skipped | Accessibility must be enabled **and** its service connected; also inspect gesture-busy/callback/fresh-frame state. |
| Wrong detection | Establish source/content geometry and inspect scores/debug frames before changing templates, ROIs, order, or threshold. |
| Popup appears stuck | Inspect callback, minimum-fresh-generation, popup fingerprint/attempt, interrupt, and DETECT_STALL logs. |
| Telegram command ignored | Check enabled/valid configuration, exact Chat ID authorization, supported command syntax, and poll/send logs. |

Useful Logcat tag: `CookieRunBot`. Common prefixes include `[CAPTURE]`, `[NORMALIZER]`, `[DETECT]`, `[STATE_MATCH]`, `[BOT]`, `[ACTION]`, `[GESTURE]`, `[FRIEND_LIFE]`, `[ROUND_]`, `[GAME_PROGRESS_WATCHDOG]`, `[TELEGRAM]`, `[NO_MATCH]`, and `[DETECT_STALL]`.
