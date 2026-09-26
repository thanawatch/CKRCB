# Current implementation status

> Despite this file's legacy name, this is a **current-code capability map**, not a migration history or roadmap. Source at HEAD remains authoritative. Start with [AGENTS.md](AGENTS.md); use [PORTING_AUDIT.md](PORTING_AUDIT.md) for call flows.

## Implemented runtime

- Native Kotlin Android application (`com.example.cookierunbot`) targeting API 36, minimum API 26, with OpenCV 4.13.0.
- User-approved MediaProjection runs in a foreground service. A latest-only ImageReader pipeline accounts for display changes/cutouts, crops the 16:9 game viewport, and publishes normalized 1280×720 OpenCV frames.
- Process-owned `BotRuntime` keeps the controller independent of `MainActivity` recreation. Capture-service shutdown stops automation.
- OpenCV grayscale `TM_CCOEFF_NORMED` stage detection uses fixed logical ROIs, templates, threshold 0.8, and multi-scale variants only for `GAME_START`/`GAME_RELAY`.
- Accessibility gestures use shared content geometry, serialized dispatch, callback completion/timeout handling, run-generation invalidation, and post-gesture fresh-frame synchronization.
- Current PRE_GAME, IN_GAME, and POST_GAME workflows cover menu/start/purchase, random boost, Fast Start, Cookie Relay, jumping, game completion, result capture/OCR, Mystery Boxes, reward/daily/league/relic/party/settings popups, anti-bot, Connection Lost, and Inactive handling.
- Scheduled/manual Friend Life and per-round Quick Receive/Send Lives are independent, bounded workflows with interrupt scans and fresh-frame checks. Friend Life additionally uses naturalized swipes and visual scroll verification.
- A 15-minute progress watchdog can launch gated confirm/close recovery and send one successful Telegram warning per overdue episode. It resets only for bot start, detected game completion, or successful Friend Life Send-button gesture.
- Telegram configuration, encrypted token storage, asynchronous notifications, authorized read-only command polling (`/help`, `/status`, `/stats`, `/last`, `/boxes`), retry policy, and config-bound replies are present.
- Session statistics maintain bounded recent history plus cumulative totals with replacement/delta semantics. Result/no-match images and in-app/persistent logs are implemented.
- Bubble UI persists bot options. Active gameplay options are a start-time snapshot; the two image-save preferences are intentionally queried live.

## Deliberate boundaries and current limitations

- MediaProjection, overlay, Accessibility, notification, and battery settings still require user action; automation cannot silently grant them.
- Detection is calibrated for normalized 1280×720 content and current assets/ROIs. Device/cutout behavior and gesture calibration require real-phone validation.
- `POST_RELAY_*_PHYSICAL` are isolated absolute 2400×1080 calibration points; they are not general coordinate policy.
- Connection Lost/Inactive use Reload taps only. The app does not force-stop or restart Cookie Run or RobotRun.
- No-match and detector-stall signals save/log local diagnostics and do not create a second Telegram alert path.
- Telegram commands are read-only and restricted to the configured Chat ID; there is no remote Start/Stop/gameplay control.
- The GitHub Actions workflow is manual (`workflow_dispatch`) and builds a signed debug APK only when required repository secrets exist. It does not publish a release build.

## Verification surface

- JVM tests cover option validation, coordinate mapping, grouped detection policies, popup/run-generation/fresh-frame guards, Friend Life scheduling/confirmation/swiping/scroll verification, Quick Lives safety, progress watchdog/recovery, no-match policy, Telegram authorization/config/retries/token migration, and round accounting.
- Standard local checks: `./gradlew test`, `./gradlew lint`, `./gradlew assembleDebug` (or the `.bat` equivalents on Windows).
- Device checks remain necessary for permissions, rotation/cutouts, capture freshness, template confidence, physical gesture results, background lifetime, public storage, and Telegram networking. Follow [ANDROID_TEST_CHECKLIST.md](ANDROID_TEST_CHECKLIST.md).
