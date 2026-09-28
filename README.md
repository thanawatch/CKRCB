# RobotRun Android

RobotRun is a native Android automation app for Cookie Run Classic. With explicit user permission it captures the screen through MediaProjection, normalizes the game viewport to 1280×720, identifies current screens with OpenCV template matching, and dispatches configured taps/swipes through an AccessibilityService.

The current Android source is authoritative. **Coding agents:** start with [AGENTS.md](AGENTS.md) and the dated [DEVELOPMENT_HANDOFF.md](DEVELOPMENT_HANDOFF.md); use [PORTING_STATUS.md](PORTING_STATUS.md) for the current capability map, [PORTING_AUDIT.md](PORTING_AUDIT.md) for call flows, [REDFINGER_PERFORMANCE_PROFILE.md](REDFINGER_PERFORMANCE_PROFILE.md) for the opt-in profile, and [ANDROID_TEST_CHECKLIST.md](ANDROID_TEST_CHECKLIST.md) for unverified phone checks. When changing a feature, update the affected guides in the same change rather than leaving incompatible command lists or version labels.

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

Windows PowerShell equivalents use `gradlew.bat`. Gradle initially writes `app/build/outputs/apk/debug/app-debug.apk`. **GitHub Actions downloads are renamed automatically after signature verification**: both the Artifact ZIP and the APK inside include `RobotRun-v<versionName>-vc<versionCode>-g<shortCommit>-run<runNumber>-a<attempt>-signed-debug`. For example (illustrative): `RobotRun-v1.1.3-vc5-gca0ea4f-run123-a1-signed-debug.apk`. This allows multiple builds of the same app version to coexist without confusing `app-debug.apk` files; it does not change the Android app identity, release version, or signing certificate. A local Android Studio/Gradle build still produces the default `app-debug.apk` unless separately renamed.

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

The overlay persists Fast Start, Cookie Relay/post-relay sequence, desired boost, relic detection, scheduled Friend Life and its interval in whole minutes (1–1440; default 61), per-round receive/send lives, debug/result image saving, jump mode/count/interval, and an **opt-in Experimental Redfinger Performance Profile**. Most options are snapshotted at Start Bot; image-save switches are read live. Legacy is the default, while Redfinger restricts GAME_START/GAME_RELAY to scale 1.0, drops GAME_START after it is handled within a round and uses dedicated calibrated logical post-relay points. See [REDFINGER_PERFORMANCE_PROFILE.md](REDFINGER_PERFORMANCE_PROFILE.md), including its device-test requirements.

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

## Overnight Diagnostics and event-triggered Eco incident video

The in-app Debug Log still shows its latest 500 RAM lines; a separate owned-app rolling text
archive now records up to the previous **10 hours** (nominal 200 MiB size cap, loss markers) in
private files across ordinary Process restarts. The **Save 10h ZIP** button exports a local ZIP.
It does not include system/other-app Logcat or an unlimited per-frame screenshot archive.

When a genuine 15-minute Watchdog or explicit in-app manual Watchdog test requests Recovery, a labeled Incident freezes the previous
**30 minutes of logs**, current Bot/Capture state and normalized PNG; the full `[GAME_PROGRESS_*]`
action timeline (template, score, logical/physical target, dispatch, completion, fresh frame,
MAINMENU result) and final recovery result are appended. Independent retention: 72h/200 MiB.

Video is armed but does **no recurring JPEG sampling during normal gameplay**. On a real or
manual Watchdog Incident only, the existing capture is sampled at target **2 FPS / 640×360**
(JPEG quality 40; H.264 250 kbps); there is **no pre-roll**. It ends at the earliest of
Recovery +10 seconds, **3 minutes from trigger**, capture stop or resource-pressure/20MiB
frame limit. Workers run at lowest priority and can skip frames or abort MP4 when capture
falls behind. A finished MP4 is optional; preserved JPEGs and a status explain failures/skips.
Manual tests include a labeled status, PNG, action timeline and outcome, and are retrievable via
/logs or local Save 10h ZIP. The manual test does not reset the real 15-minute timer. Use Debug Log's **Video ON / Video OFF** toggle (enabled by default in this
source version). No second MediaProjection, no new game gestures, and no watchdog rule changes.

Use authorized Telegram **/logs** or **📥 Download Logs** to request the 10h diagnostic ZIP
(`sendDocument`, separate worker, 49 MB public Bot API transfer limit). It can include the
five most recent incidents and MP4/JPEG fallback within a 35 MiB video budget; the manifest
lists omissions. It never auto-uploads logs. If Telegram file size/network prevents delivery,
use **Debug Log → Save 10h ZIP** on-device. If the entire Process was killed, launch RobotRun
again before requesting /logs; already written private logs are recoverable unless app data
was cleared/uninstalled. See [docs/OVERNIGHT_DIAGNOSTICS.md](docs/OVERNIGHT_DIAGNOSTICS.md).
MediaCodec MP4 quality and performance on Redfinger require an actual APK/device test.

## Calibration panel across orientation changes

Capture Calibration is a separate overlay containing a **frozen 1280×720 frame**, not a live game view. The panel now refits its width and height to the currently usable screen area after switching landscape ↔ portrait, avoiding status/navigation bars and cutouts. Its **✕ Close** control is in a fixed header that does not scroll away. Rotation never triggers a tap or automatic recapture; a displayed physical X/Y always refers to the still image's original `contentRect`. Use **Capture new clean frame** after rotation to calibrate the new display geometry.

On an older installed build with an off-screen Close button, rotate back to landscape or temporarily disable RobotRun's **Display over other apps** permission in Android settings. Do not clear app data or uninstall it to dismiss the overlay. See [CALIBRATION.md](CALIBRATION.md).

## In-app updates (independent of Telegram)

The main screen includes **Update App**, implemented in `UpdateActivity`. The source baseline is **v1.1.8 (versionCode 10)**; an installed APK can still be older until rebuilt and installed. The owner must configure a trusted anonymous HTTPS `update.json` URL in that screen for **online** update checks; a private GitHub Actions artifact is not a permanent anonymous download link, and no public feed is provided by committing this code. See [the example feed](docs/update-feed.example.json). This updater does not embed GitHub tokens, signing keys, or credentials.

To publish a future build, increment both `versionCode` and `versionName` (e.g. `11` / `1.1.9`), build a **single signed APK with the same signing certificate as the currently installed application**, host that APK on HTTPS, calculate its SHA-256 (`Get-FileHash -Algorithm SHA256` on PowerShell or `sha256sum` on Linux), and publish the feed with its actual URL, package name, code, name, checksum, and notes. Only publish signed binaries and feed data to the distribution host; the source repository may remain private. Do not publish the sample placeholder checksum as a real feed.

Alternatively, without any hosted feed, tap **Choose downloaded APK (offline)** and select a local APK through Android's document picker. It still must be a strictly newer RobotRun APK signed with the installed signing certificate; no network checksum is available for this deliberate local-file path.

Checking and downloading are user initiated. The network work runs off the UI, capture, and detection threads. HTTPS redirects must stay HTTPS; the downloader caps feed and APK sizes, validates the checksum, Android package name/version, and installed signing certificate, then stores the verified APK in private cache. The FileProvider permits temporary read-only installer access to that specific cache directory. Android prompts the user to allow this installation source and confirm the APK installation. The app **does not** silently install, stop a running bot/capture, restart the game, or change the game-progress watchdog. Installation is blocked until the user manually stops Bot and Screen Capture. If the old app was locally debug-signed with a different key than CI, an in-place update cannot be installed with data preservation; use the same signing key, not an uninstall workaround.

## Telegram

Telegram settings store the bot token encrypted with an Android Keystore-backed AES-GCM key. The configured Chat ID is both the notification destination and the only authorized incoming-command chat. Networking and encoding run asynchronously and failures do not stop gameplay.

Supported read-only commands are `/help`, `/status`, `/stats`, `/last`, `/boxes`, `/all` (a fresh 1280×720 PNG **document** followed by a separate `/stats` → `/status` → `/last` message; if the document is unavailable, the full text still arrives with a short Screen note), `/friend`, `/health`, `/screen`, `/watchdog`, `/rounds`, `/errors`, `/update`, `/logs`, and `/dashboard` (also `/start` and `/menu`). The persistent one-tap reply keyboard is included in each command reply, with **📊 All** and **🔄 Refresh** mapped to `/all`, plus **📦 Update App** mapped to `/update` and **📥 Download Logs** mapped to `/logs`; send `/dashboard` to show it on demand. `/update` reads the same configured HTTPS feed and reports installed/available versions. For a newer release, it downloads the candidate into a separate private cache, validates SHA-256, package/version and the installed signing certificate, then sends the verified APK as a Telegram file in the authorized chat (maximum 49,000,000 bytes). This runs on a separate worker with one request at a time and a five-minute same-file success cooldown. If no feed is configured, the file is invalid/too large or upload fails, use the in-app updater. **Receiving an APK is not installing it:** open Telegram on the destination Android/Redfinger, download the attachment, stop Bot and Screen Capture and approve the Android install dialog. The source repo remains private, with no embedded GitHub token. `/screen` and `/all` share a Telegram-only RGBA→BGRA PNG encoder plus an original-file `sendDocument` upload (not compressed photo), and the same fresh normalized 1280×720 snapshot path and 15-second on-demand cooldown, and does not depend on the automatic debug-image setting; stale/unavailable frames return text instead. It is processed on the Telegram notification executor, not the capture/detector thread. `/errors` only returns up to five allowlisted local failure lines on demand; it never adds an automatic NO_MATCH/DETECT_STALL alert. Supported slash commands accept the current parser's `@BotUsername` suffix form. Only the configured Chat ID is authorized. There is no remote Start/Stop, gameplay control or remote APK installation. **The Telegram buttons exist in the installed APK, not on GitHub or in BotFather:** install a build containing the new Kotlin code and send `/dashboard` to refresh the persistent keyboard. BotFather's slash-command menu is a separate configuration. Never commit credentials or paste token-bearing URLs into logs/issues.

## Troubleshooting

| Symptom | Verify |
| --- | --- |
| No capture/frames | Re-consent to MediaProjection, check foreground notification, source size, generation, FPS, and frame age. |
| Bubble missing | Grant overlay permission and restart capture. |
| Actions skipped | Accessibility must be enabled **and** its service connected; also inspect gesture-busy/callback/fresh-frame state. |
| Wrong detection | Establish source/content geometry and inspect scores/debug frames before changing templates, ROIs, order, or threshold. |
| Popup appears stuck | Inspect callback, minimum-fresh-generation, popup fingerprint/attempt, interrupt, and DETECT_STALL logs. |
| Telegram command ignored or Update button missing | Check installed APK version, enabled/valid configuration, configured Chat ID, `/dashboard` refresh, supported syntax, and poll/send logs; a GitHub commit alone cannot change a running old APK. |
| Update App shows no online update | Configure a genuine HTTPS feed and signed APK, check numeric versionCode, SHA-256 and certificate. The sample feed is not live; the offline document picker is available without a feed. |

Useful Logcat tag: `CookieRunBot`. Common prefixes include `[CAPTURE]`, `[NORMALIZER]`, `[DETECT]`, `[STATE_MATCH]`, `[BOT]`, `[ACTION]`, `[GESTURE]`, `[FRIEND_LIFE]`, `[ROUND_]`, `[GAME_PROGRESS_WATCHDOG]`, `[TELEGRAM]`, `[NO_MATCH]`, and `[DETECT_STALL]`.
