# Android verification checklist

Use this after changes that touch runtime behavior. Mark only checks actually performed; emulator/unit/static checks do not substitute for MediaProjection, Accessibility and installer validation on a target phone. For new Agent sessions, read [AGENTS.md](AGENTS.md) and [DEVELOPMENT_HANDOFF.md](DEVELOPMENT_HANDOFF.md) before acting.

## Automated checks

- [ ] `./gradlew test` (or `.\gradlew.bat test`)
- [ ] `./gradlew lint` (or `.\gradlew.bat lint`)
- [ ] `./gradlew assembleDebug` (or `.\gradlew.bat assembleDebug`)
- [ ] `git diff --check`
- [ ] Review test reports under `app/build/reports/tests/` and lint output; do not commit generated artifacts.
- [ ] Confirm the APK exists at `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] Run the manual GitHub Actions workflow; verify `apksigner` checks the original signed APK first. The downloaded Artifact ZIP name and the single APK extracted from it must both identify versionName, versionCode, short commit, run number, and run attempt; a rerun must produce a distinguishable filename. Check the copied APK matches the original hash and signing certificate, and that no app `versionCode` or runtime logic was changed just to rename a build.
- [ ] For a changed feature or release baseline, verify affected `AGENTS.md`, `DEVELOPMENT_HANDOFF.md`, `PORTING_STATUS.md`, `PORTING_AUDIT.md`, `README.md`, this checklist, and applicable Redfinger/calibration docs have matching command lists, version names and constraints. Do not mark a test as run solely because a test file or workflow was committed.

## Install, permissions, and lifecycle

- [ ] Clean install and upgrade install both launch RobotRun.
- [ ] Enable the RobotRun Accessibility service and verify diagnostics distinguish permission enabled from service connected.
- [ ] Grant overlay permission; start capture and confirm the floating **BOT** bubble appears.
- [ ] On Android 13+, grant notification permission. Confirm foreground capture notification remains present.
- [ ] Exercise battery-optimization prompt/status without assuming exemption was granted.
- [ ] Start/stop/restart MediaProjection. Deny/cancel consent once and verify safe stopped/error UI.
- [ ] Rotate/reverse landscape or otherwise change display geometry; verify capture resources rebuild and normalized output remains 1280×720.
- [ ] Recreate/close `MainActivity` while capture/bot runs; verify process-owned controller continues. Stop capture and verify overlay plus bot stop.
- [ ] Power connect/disconnect while capturing; verify no crash/resource leak.

## Capture, geometry, and detection

- [ ] Diagnostics show correct source dimensions, orientation, content rectangle, advancing generation/FPS, and frame age below 1 second during normal operation.
- [ ] Test a device with a cutout/inset. Confirm the visible game viewport and physical gesture mapping share the same crop.
- [ ] Validate MAINMENU detection while bot is stopped; verify the service's idle detector yields while bot runs.
- [ ] Validate representative PRE_GAME, IN_GAME, and POST_GAME templates at current threshold/ROI; include scaled GAME_START/GAME_RELAY animation frames in Legacy. In explicitly enabled Redfinger, verify only scale 1.0 for both and no repeated GAME_START matching after it has been handled in that round.
- [ ] Validate LOGIN on the supplied 1280×720 screen: `LOGIN_1.png` should match within ROI `(390,570)-(890,700)` at scale 1.0, the first action should tap the matched rectangle center (reference sample: match `(415,593)-(862,677)`, center `(638,635)`), wait exactly 5 seconds, then tap the supplied Google account screen at logical `(657,294)` with jitter 0. Only after the Google tap should it require a fresh frame and resume PRE_GAME. Confirm a lingering LOGIN screen is not accepted more often than once per 3 seconds and that LOGIN remains detectable from PRE_GAME, IN_GAME, and POST_GAME.
- [ ] Exercise Anti-bot Test 1→2→3 with the same artwork set shuffled into different card slots. Verify the shuffled layout is recognized from cross-slot artwork matches/permutation rather than generic two-card change, Test 2/3 advance only after two stable fresh frames, Test 1 highlight/animation is not double-tapped, and a delay beyond two minutes during an active follow-up does not split challenge statistics; changing rounds must still start a new challenge.
- [ ] Hold an Anti-bot follow-up on an unchanged/unknown layout for 60 seconds with no successful Anti-bot tap. Verify emergency recovery waits for three newer ANTI_BOT matches, aborts if Connection Lost/Inactive appears, bypasses only the layout guard once, uses the normal odd-card detector/tap coordinates, never force-taps while awaiting exit after Test 3, and waits another full 60 seconds after an emergency attempt that produces no successful tap.
- [ ] Confirm raw/normalized no-match pairs are saved only when debug images are enabled and only after watchdog delay; verify they cause no Telegram alert or automatic tap/restart.
- [ ] Inspect memory during repeated capture restarts and long detection runs for unreleased Image/Mat growth.

## Gesture safety and fresh-frame behavior

- [ ] With Accessibility disabled, enabled-but-not-connected, and connected, verify action readiness messages and no unsafe dispatch.
- [ ] Use **Test Tap** to validate logical-to-physical mapping.
- [ ] Verify concurrent actions serialize; rejected dispatch, callback cancellation, and callback timeout do not count as success.
- [ ] Press **Stop Bot** during a wait/gesture callback and confirm no delayed follow-up tap occurs.
- [ ] Confirm a state-changing action waits for a generation established after callback completion; a frame captured during the gesture must not satisfy the gate.
- [ ] Verify the Legacy fixed-device exceptions separately from logical mapping: three post-relay absolute points on the calibrated 2400×1080 path, TIME_DEAL at `(2000,70)`, and PARTY_RUN_SEASON_REWARDS at `(1260,980)`. In Redfinger, verify optional calibrated post-relay logical `(1198,35)`, `(647,425)`, `(637,389)` map through current `contentRect`, and uncalibrated TIME_DEAL/PARTY_RUN_SEASON_REWARDS wait for manual popup clearance without guessed taps.

## Overnight Diagnostics: 10h archive / 30-minute Incident / optional JPEG screenshots / Telegram

- [ ] Run JVM tests: OvernightLogPolicy, IncidentVideoTiming, IncidentVideoLoadPolicy, TelegramLogDelivery, TelegramDashboard; verify Gradle testDebugUnitTest, lint and assembleDebug before claiming APK success.
- [ ] Long run on Redfinger, collecting app-owned Logcat: compare timeline to private rolling hourly text archive. Include Bot Start/Stop, State/Detection/Action, Fast Start, Friend Life, Telegram, Power/Capture/Detect Health and errors. Confirm 500-line Debug Log's Clear button does not clear archived data.
- [ ] Confirm 10-hour exact timestamp window in exported ZIP across Process restart, hourly UTC rotation, age deletion, 200 MiB cap with explicit `[ARCHIVE_LOSS]` after pruning/queue overflow; no bot token/URL or password appears in exported text. No screenshot frame stream accidentally stored as text.
- [ ] Simulate an overdue **real** watchdog incident. Verify one Incident per episode, no duplication at 30s check, 30 minutes of pre-trigger archive, screenshot, Bot/Capture status, ordered `[GAME_PROGRESS_*]` timeline showing template/score/attempt, logical and mapped physical point, dispatch accepted, completed callback and fresh-frame result, then outcome. Manual watchdog test must create a separately labeled MANUAL_TEST Incident with screenshot, 30m log, timeline and outcome, without resetting/reserving the real Watchdog progress clock; retrieve via /logs after finish.
- [ ] Confirm 15m watchdog timer, its only original two reset events, retry behavior, recovery gesture counts/template/ROI/threshold/state routing, Fast Start and detection worker latency are unchanged by logging/recorder threads.
- [ ] Run Bot/Capture without Incident: no periodic optional sampling, resize, JPEG or MP4 encoding. Press Test Watchdog Recovery and separately trigger genuine overdue; verify **new** Incidents contain optional `screenshots/000001.jpg` etc. at 480×270, JPEG quality 35, nominally once per 2 seconds (0.5 FPS), without pre-roll or second capture/VirtualDisplay. Confirm essential PNG/30m archive are queued first and screenshot/timeline/outcome survive when Images OFF or optional sampling is skipped.
- [ ] Verify image-sequence stop at Recovery finished +10s or 3min FROM trigger (earlier wins), capture stop, max 90 JPEGs / 20 MiB, 10 consecutive degraded ticks or error. Stale/repeated/unhealthy capture frames, unknown or under-2 capture FPS, and >1 single-core-equivalent RobotRun process CPU in a 2s window must skip optional sampling before frame clone. A sample >250ms wall or >80ms sampler-thread CPU skips the NEXT 2-second tick instead of immediately aborting after two expensive samples. Verify lowest-priority thread and no gameplay worker wait; the process CPU metric does not measure Redfinger-wide CPU. Rapidly stop/restart Screen Capture and toggle Images OFF/ON; old session callbacks must not touch the new recording. Test rotation and background behavior.
- [ ] Force unhealthy capture, sampler exception, disk pressure and abrupt Process kill. Verify `incident_image_status.txt`, `images_timing.txt` and already atomically saved JPEGs remain useful when optional sampling is skipped/interrupted; no `.part` file is exported, no infinite sampling, no native Mat leak. New Incidents must **not** invoke MediaCodec/MediaMuxer or produce MP4; legacy MP4s remain eligible for export.
- [ ] Authorized /logs and persistent 📥 Download Logs sends a ZIP as original sendDocument off the watchdog executor. Verify `robotrun_10h.txt`, up to five latest Incidents, new `screenshots/*.jpg` and legacy MP4/JPEG evidence within the shared 35 MiB optional-media budget, manifest omissions and 49,000,000-byte cap. Reject unauthorized chat/config change midway, simultaneous /logs, and oversize ZIP. Confirm no auto upload and normal Watchdog Telegram alerts continue.
- [ ] Debug Log → Save 10h ZIP exports locally even when Telegram transfer cap is exceeded. Confirm readable `watchdog_screenshot.png`, `watchdog_action_timeline.txt`, `recovery_result.txt`, optional `screenshots/*.jpg` plus `images_timing.txt`/status, and cleanup of temporary export files. Do not require an MP4 from new Incidents.
- [ ] Real Android process killed → no Telegram response until RobotRun is relaunched, but previously flushed private archive and Incident data survives; uninstall or Clear Data is destructive. Check disk pressure 72h separate Incident retention and 200 MiB Incident cap.

## Calibration overlay and rotation recovery

- [ ] Open Capture Calibration while Bot stopped and Screen Capture running. Capture a genuine newer, clean 1280×720 frame; assert no game tap and no auto-recapture after opening/rotation.
- [ ] In landscape, scroll the calibration form to the bottom: the fixed **✕ Close** header must remain visible and tappable.
- [ ] Leave the landscape game for the portrait Redfinger/Android home screen **while calibration is still open**. The panel must resize fully inside available portrait width/height (excluding system bars/cutouts); all buttons must be accessible by scrolling, especially Close. Rotate back and repeat multiple times.
- [ ] Verify the still image and saved logical coordinates survive layout reflows. After rotation, show the original-contentRect warning; only an explicit Capture new clean frame can update physical X/Y to the new geometry.
- [ ] Close calibration after rotation. Verify parent RobotRun bubble reappears, overlay event listeners are removed and repeated open/close leaves no invisible or duplicate windows. Verify safe exit if a window layout update fails or overlay permission changes.
- [ ] Verify legacy minSdk 26-29 metrics fallback and API 30+/Redfinger Window Context and display listeners where devices are available. No changes to template, ROI, capture normalization, watchdog, game gestures, or Friend Life are expected.

## Normal automation workflow

- [ ] Start Bot waits for fresh capture and connected Accessibility, then begins in PRE_GAME.
- [ ] MAINMENU is revalidated before Friend Life, Quick Lives, or Start Game. Present a blocking popup during revalidation and confirm stale menu work aborts.
- [ ] PURCHASE_ITEM: validate Play, selected boost found path, 30-second boost timeout path, and retry behavior.
- [ ] Validate Fast Start priority window, Cookie Relay, optional post-relay sequence, fixed jump count, continuous jumping, and Stop cancellation.
- [ ] GAME_COMPLETE immediately changes to POST_GAME, resets progress, analyzes results off-loop, requires a fresh re-match before Finish, and honors result-image preference.
- [ ] Validate Mystery Box late update changes the existing round without double-counting aggregates.
- [ ] Validate reward popup redetection and unchanged-popup attempt guard; do not expect a hard-coded next-popup chain.
- [ ] Validate Connection Lost and Inactive only tap Reload and never restart/kill either application.

## Friend Life and Quick Lives

- [ ] Set Friend Life interval to 5 minutes in the bubble, start the bot, and verify first due is 5 minutes after Start (not immediately), then next scheduled due is 5 minutes after successful completion; the transaction begins only at safe MAINMENU. Verify the 61-minute default is preserved on existing installs, invalid/blank minutes are rejected when starting, and restarting with a different selected interval changes the schedule without affecting manual-test timing.
- [ ] Manual Friend Life test returns correct stopped/queued/already queued/in-progress state and coalesces safely with scheduled work.
- [ ] Verify top search, send-region scanning, Send/confirmation interrupt checks, relic filtering, and bottom completion.
- [ ] Verify swipe acceptance alone is not progress: unchanged verification ROI drives the 30-second no-progress abort; also exercise absolute/top-search bounds where practical.
- [ ] Confirm only the **successful Send-button gesture callback** resets game progress, before confirmations. Ensure no new post-CONFIRM_2 button disappearance/extra verification or changed Friend Life swipe/scroll thresholds were introduced.
- [ ] Force a scheduled failure and verify pending work respects the 15-minute retry-not-before.
- [ ] Test Quick Receive/Send Lives independently: all-received, none-to-receive, confirmation, interrupt, timeout, and next-round reset paths.

## Watchdog, recovery, and Telegram

- [ ] Confirm progress timestamps for bot start, detected GAME_COMPLETE, and successful Friend Life Send-button gesture only.
- [ ] Use the overlay recovery test to verify the full-frame CONFIRM_SEND_LIFE loop. Test no match on the first scan, a match then disappearance, and three consecutive matches: each branch must continue into CLOSE_BUTTON. Confirm requires a successful gesture and fresh frame before each rescan, allows at most three taps, and preserves the logical radius-5 point selection.
- [ ] Verify CONFIRM and CLOSE continuing attempts each wait 2 seconds after a successful callback before taking a fresh-frame baseline. The third matched tap skips retry delay but still requires a newer frame. Failed gesture/timeout must abort without tapping from an old frame.
- [ ] Verify the top-right-quarter CLOSE_BUTTON stage still uses grayscale `TM_CCOEFF_NORMED` at threshold 0.8 (not alpha-mask SQDIFF); its first no-match ends only the Close loop, and a maximum of three matched taps is preserved.
- [ ] During recovery confirm normal detection/actions are gated; after fallback, the resulting frame and successive fresh frames are checked for MAINMENU for at most 120 seconds, and a match routes to PRE_GAME.
- [ ] Exercise new progress/Stop/restart during recovery and confirm stale reservation/callback work is discarded.
- [ ] Configure Telegram without exposing credentials. Test disabled, missing token, missing Chat ID, valid text-only, valid image, network failure, HTTP failure/rate limit, and changed-config cancellation.
- [ ] At an overdue episode, verify at most one **successfully delivered** alert; failed delivery becomes retry-eligible after 5 minutes. Verify a new progress event permits a later episode.
- [ ] Install an APK carrying the latest commands (a GitHub commit does not update an old installed app), send `/dashboard`, and verify Telegram's persistent keyboard. Send `/help`, `/status`, `/stats`, `/last`, `/boxes`, `/all`, `/friend`, `/health`, `/screen`, `/watchdog`, `/rounds`, `/errors`, `/update`, `/logs`, `/dashboard` and `@BotUsername` parser forms from the configured Chat ID; verify other chats and unsupported/gameplay commands cannot control the bot. BotFather's slash-command menu is separate.
- [ ] Test the persistent dashboard buttons. **📊 All** and **🔄 Refresh** must each send a fresh 1280×720 original PNG file (`sendDocument`), followed by the full `/all` reply in `/stats` → `/status` → `/last` order with one tap; test the Screen/Friend/Health/Watchdog/Rounds/Errors/Update App/Download Logs buttons too. Test `/update` with absent/equal/newer/unreachable feed, changed Telegram settings, and no hosted APK. For a valid newer APK under 49,000,000 bytes, verify it is sent as an exact `sendDocument` attachment only to the authorized Chat ID after checksum/package/version/signing-certificate checks. Test invalid signer/hash, oversized and unknown-length downloads, simultaneous requests, successful duplicate five-minute cooldown, failed upload, cancelled transfer/cleanup, and normal watchdog notifications during transfer. It must never remotely install or stop the bot.
- [ ] Test `/friend` while scheduled/pending/running/retrying and after failure/completion; `/health` with stopped capture/disconnected Accessibility; `/watchdog` before Start and during recovery; `/rounds` with more than five results; `/errors` with no recent events and with allowlisted failures (do not leak credentials).
- [ ] Test `/screen` and `/all` with fresh 1280×720 capture, capture stopped, stale/no frame, repeated requests inside the shared 15-second cooldown, disabled automatic debug-image toggle, failed upload, and config changes before sending. An uploaded `/all` document must arrive before the full text; when PNG encoding/document upload fails, `/all` must still deliver the same stats/status/last text with a short Screen note. Compare exact downloaded PNG bytes/pixels, especially red/blue channel ordering, against a locally saved reference frame. Verify the 1280×720 dimensions and readability of small text; the screenshot clone and BGRA/encoded intermediates must be released. Ensure automatic watchdog/diagnostic `sendPhoto` behavior and capture/detection/gameplay are unchanged. Ensure the Mat clone is released and no gameplay/detection thread is blocked.

## In-app updater (independent of Telegram)

- [ ] On the main screen, open **Update App** with Telegram disabled. Confirm v1.1.10/code 12 is displayed and no check occurs before user action.
- [ ] Configure a trusted HTTPS feed containing a genuinely signed newer universal APK, correct package/code/name and 64-hex SHA-256. Verify newer-version comparison and up-to-date cases.
- [ ] With no feed configured, select a downloaded APK using Android's document picker. Verify that only a newer RobotRun APK signed with the existing certificate is accepted, and a wrong package/old APK/oversized file is rejected.
- [ ] Reject HTTP, malformed URL, embedded credentials, non-HTTPS redirects, invalid/oversized feed, zero-length/oversized APK, wrong checksum, wrong package/version, different signing certificate, and older/equal APK.
- [ ] Download a valid APK and confirm that the user—not the app—must stop Bot and Screen Capture, authorize the installer source, and approve Android's install dialog.
- [ ] Confirm the app never silently stops the bot, does not call gameplay actions or touch Telegram settings, and grants the installer read-only access only to the private updates cache.
- [ ] Test installation over an APK signed with the **same** key and verify app settings survive; test different-key rejection. Check cancellation/rotation/network-loss and no leaked cached incomplete APK.
- [ ] Keep the private GitHub source private; publish signed APK and `update.json` separately over HTTPS without embedding a GitHub personal-access token. The sample JSON and 14-day signed debug CI artifact alone are not a configured, permanent anonymous release/update source. Prove same signing certificate before attempting an in-place upgrade.

## Statistics, storage, and diagnostics

- [ ] Each Start Bot creates a new statistics session; opening/closing Statistics does not reset it.
- [ ] Verify score/coins/XP, round duration, box grades, recent-history limit (50), cumulative totals, and stale old-session analysis rejection.
- [ ] Toggle debug/result image switches during a run and confirm live save behavior.
- [ ] Verify result images in `Pictures/CookieRunBot/results/` and no-match pairs in `Pictures/CookieRunBot/debug/` (including legacy storage behavior where supported).
- [ ] Verify Debug Log refresh/copy/clear/save; saved logs use `Download/RobotRun/` or documented app-specific fallback.
- [ ] Verify persistent trace path/fallback/rotation and throttled health/DETECT_STALL logging without per-frame spam.
