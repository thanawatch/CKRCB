# RobotRun Android

RobotRun is a native Android Cookie Run Classic automation app. It captures the device display with MediaProjection, normalizes the game image to a logical 1280×720 frame, detects known screens with OpenCV templates, and performs configured gestures through an Android AccessibilityService.

The Android source under `app/src/main` is the source of truth for current behavior. Historical porting notes such as `PORTING_STATUS.md` describe an audit at a particular date and may not reflect later edits. See [AGENTS.md](AGENTS.md) before changing code.

## Project facts

- Application ID / namespace: `com.example.cookierunbot`
- Display name: RobotRun
- Language: Kotlin
- Minimum Android API: 26
- Compile and target SDK: 36
- Java/Kotlin target: 17
- OpenCV Android dependency: `org.opencv:opencv:4.13.0`
- Gradle wrapper: 8.11.1

## Build

Open `C:\bot\CookieRunBotAndroid` in Android Studio and allow Gradle sync. Install a JDK 17 runtime and Android SDK platform/build tools for API 36.

From PowerShell at the project root:

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install from Android Studio or with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## First run on a phone

1. Open RobotRun and enable its service from **Open Accessibility Settings**. Android requires the user to grant this permission manually.
2. Grant **Display over other apps** so the floating controls can appear.
3. Grant unrestricted battery access if the device's power manager would otherwise restrict background operation.
4. Tap **Start Screen Capture** and accept Android's screen recording prompt. On Android 13 and later, also allow the capture notification permission when requested.
5. Open Cookie Run Classic. Use the floating **BOT** bubble to review options and press **Start Bot**. Press **Stop Bot** to stop automation; **Stop Screen Capture** separately stops capture.

The main screen shows capture state, source and normalized frame dimensions, orientation, frame age, Accessibility permission/connection state, and detection diagnostics. The overlay exposes options, session summary, and bot Start/Stop controls while capture is active. A manual **Test Tap** is available from the main screen.

Screen capture permission is granted for a capture session and must be requested through Android's MediaProjection flow. The manifest does not lock the activity to portrait. The capture pipeline responds to display geometry changes; the output detection frame remains 1280×720.

## Runtime architecture

```text
MainActivity / BotControlOverlay
          │ user starts capture or bot
          ▼
ScreenCaptureService (foreground MediaProjection service)
  ├─ ScreenCaptureManager → latest raw screen frame
  ├─ FrameNormalizer → logical 1280×720 frame
  └─ detection worker → BotRuntime / BotController
                              ├─ StateDetector / OpenCV templates
                              ├─ ActionExecutor
                              ├─ AccessibilityService gestures
                              └─ round results and diagnostics
```

`BotRuntime` owns the controller at process scope so an Activity recreation does not own or cancel the bot loop. `LatestFrameProvider` keeps the newest normalized frame and publishes a generation number. Consumers request a snapshot or wait for a generation newer than a baseline; intermediate frames are discarded rather than queued for later detection.

`FrameNormalizer` derives a 16:9 content rectangle from source dimensions and display cutout insets, then resizes the crop to 1280×720. The same `ContentTransform` is shared with `CoordinateMapper` for logical actions. The detector uses grayscale OpenCV template matching (`TM_CCOEFF_NORMED`) and the configured threshold/ROI for each stage. Do not infer physical tap locations from the logical frame dimensions alone.

## Bot behavior and options

The controller scans three groups: `PRE_GAME`, `IN_GAME`, and `POST_GAME`. `CONNECTION_LOST` and `INACTIVE` are checked as always-priority states. In-game ordering prioritizes `GAME_RELAY`, then `GAME_COMPLETE`, then `GAME_START`; the controller may use a short-lived `GAME_START` priority window for Fast Start. Exact stage lists and policies live in `BotController` and `StateDetector`.

The floating controls currently persist options through `BotOptionsStore`, including:

- use owned Fast Start and Cookie Relay;
- run the optional action sequence after Cookie Relay;
- use a selected Desired Random Boost;
- detect Relic screens;
- scheduled Friend Life and receive/send lives after each game;
- save-debug-image option;
- jump mode (`NONE`, fixed count, or continuous) and tap interval.

The controller also handles result/reward popups, Mystery Box classification, result screenshots, MAINMENU revalidation, and automatic progression through its current grouped state machine. Desired Boost waits for the selected boost template or the existing timeout behavior. Reward popups return to generic `PRE_GAME` detection after a fresh frame, with an instance guard against repeated taps on an unchanged dialog. Connection Lost handling taps **Reload** and returns to detection; it does not restart the game process.

Friend Life is a separate scheduled/test workflow from Quick Receive/Send Lives. Its scheduler interval, fresh-frame interrupt checks, leaderboard scan regions, bounded swipe generation, visual scroll verification, and progress/absolute timeouts are implemented in `FriendLifeScheduler.kt`, `FriendSwipeProfile.kt`, `FriendScrollVerifier.kt`, and `BotController.kt`. Consult those files before changing timing or gestures.

## Statistics, images, and logs

- A new bot run starts a new statistics session. `RoundResultStore` retains up to 50 recent rounds for history and separately maintains cumulative session totals. `RoundResultCollector` analyzes result frames off the detector thread and uses session generations to discard stale asynchronous results.
- Result images are saved under the public `Pictures/CookieRunBot/results/` directory. Prolonged no-match diagnostics save raw and normalized frames under `Pictures/CookieRunBot/debug/`.
- The in-app Debug Log keeps up to 500 recent event lines in memory. **Save Log** writes a text file under `Download/RobotRun/` (with app-specific storage fallback on failure).
- A persistent lifecycle trace is stored in the app-specific external files directory under `diagnostics/coldstart_trace.txt` (or internal files storage if external app storage is unavailable); it rotates the previous trace when the configured size limit is reached.

Use the **Statistics / Round History**, **Open Debug Log**, and **Telegram Notifications** screens to inspect their respective features. Filenames and storage details are defined by `PublicImageStore`, `ResultImageSaver`, `NoMatchDebugFrameSaver`, and `DebugLogActivity`.

## Telegram

Telegram settings are managed in the existing Telegram Settings screen. The bot token is stored through `TelegramSecureTokenStore`; the configured Chat ID is also the only authorized incoming-command chat. Never commit or paste credentials into source, documentation, or logs.

The current read-only commands are `/help`, `/status`, `/stats`, `/last`, and `/boxes`; group command forms with an `@BotUsername` suffix are accepted. `TelegramCommandReceiver` long-polls `getUpdates` from the app process and persists update progress. It does not expose remote bot control commands.

`GameProgressWatchdogManager` runs independently of the detector loop. It starts when the bot starts and measures time since bot start, a detected `GAME_COMPLETE`, or a successful tap of the detected Friend Life Send button. At 15 minutes without one of those events it may send one warning for that overdue episode, with the latest normalized screenshot when debug images are enabled; failed delivery can be retried. Normal text notifications and debug image preferences are controlled in Telegram Settings. A successful friend-button gesture is the progress event; the app does not perform an additional server-side delivery verification.

Prolonged `NO_MATCH` captures and `[DETECT_STALL]` are local diagnostics. They do not independently trigger Telegram alerts in the current implementation.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Capture stays stopped or no frames arrive | Start Screen Capture again, accept the system recording prompt, check its foreground notification, and verify `LatestFrameProvider` diagnostics. |
| Floating BOT bubble is missing | Allow RobotRun to display over other apps, then restart screen capture. |
| Test Tap or bot actions are skipped | Check both Accessibility permission **and** service connection status. Enabled permission alone does not mean the service is connected. |
| Detection does not match the visible screen | Inspect source resolution, content rectangle/normalized frame diagnostics, current stage score, and local debug images. Do not lower thresholds or alter templates before establishing the geometry/input frame is correct. |
| Bot pauses on a popup | Check fresh-frame, callback, popup-instance, and interrupt logs. The detector can be inside a synchronous workflow; `[DETECT_STALL]` is diagnostic and is not itself a recovery action. |
| Telegram command gets no response | Confirm notifications are enabled, token/Chat ID are valid, the message comes from the configured Chat ID, and network polling/send logs contain no failure. |

Useful Logcat filter:

```text
CookieRunBot
```

Useful prefixes include `[CAPTURE]`, `[NORMALIZER]`, `[DETECT]`, `[STATE_MATCH]`, `[BOT]`, `[ACTION]`, `[GESTURE]`, `[FRIEND_LIFE]`, `[ROUND_]`, `[GAME_PROGRESS_WATCHDOG]`, `[TELEGRAM]`, `[NO_MATCH]`, and `[DETECT_STALL]`.

## Privacy and known limits

MediaProjection captures visible device content while the user-authorized capture service is active. Result/debug images are placed in public Pictures folders and can be visible to gallery/file apps. Telegram sends only occur when configured and enabled; incoming commands are limited to the configured Chat ID. Avoid sharing debug images or logs that may contain personal game/account information.

Bot detection depends on the included templates, their ROIs, the normalized game viewport, and current game UI. Device-specific geometry or game UI changes may require source-grounded calibration. The `Save Debug Images` option exists in the overlay and is persisted, but the current controller does not read that option; do not assume it gates the prolonged no-match diagnostic captures. The project has unit tests for policies, coordinates, Friend Life helpers, watchdogs, Telegram helpers, popup guards, and statistics; device-level capture, permission, and gesture behavior still needs testing on a phone.
