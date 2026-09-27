# Android verification checklist

Use this after changes that touch runtime behavior. Mark only checks actually performed; emulator/unit checks do not substitute for MediaProjection and Accessibility validation on a target phone.

## Automated checks

- [ ] `./gradlew test` (or `.\gradlew.bat test`)
- [ ] `./gradlew lint` (or `.\gradlew.bat lint`)
- [ ] `./gradlew assembleDebug` (or `.\gradlew.bat assembleDebug`)
- [ ] `git diff --check`
- [ ] Review test reports under `app/build/reports/tests/` and lint output; do not commit generated artifacts.
- [ ] Confirm the APK exists at `app/build/outputs/apk/debug/app-debug.apk`.

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
- [ ] Validate representative PRE_GAME, IN_GAME, and POST_GAME templates at current threshold/ROI; include scaled GAME_START/GAME_RELAY animation frames.
- [ ] Confirm raw/normalized no-match pairs are saved only when debug images are enabled and only after watchdog delay; verify they cause no Telegram alert or automatic tap/restart.
- [ ] Inspect memory during repeated capture restarts and long detection runs for unreleased Image/Mat growth.

## Gesture safety and fresh-frame behavior

- [ ] With Accessibility disabled, enabled-but-not-connected, and connected, verify action readiness messages and no unsafe dispatch.
- [ ] Use **Test Tap** to validate logical-to-physical mapping.
- [ ] Verify concurrent actions serialize; rejected dispatch, callback cancellation, and callback timeout do not count as success.
- [ ] Press **Stop Bot** during a wait/gesture callback and confirm no delayed follow-up tap occurs.
- [ ] Confirm a state-changing action waits for a generation established after callback completion; a frame captured during the gesture must not satisfy the gate.
- [ ] Verify all fixed-device exceptions separately from logical mapping: the three post-relay absolute points on their calibrated 2400×1080 path, TIME_DEAL at physical `(2000,70)`, and PARTY_RUN_SEASON_REWARDS at physical `(1260,980)`.

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
- [ ] Confirm only successful Send-button gesture resets game progress, before confirmations.
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
- [ ] Send `/help`, `/status`, `/stats`, `/last`, `/boxes`, and `@BotUsername` forms from the configured Chat ID; verify other chats and unsupported/gameplay commands cannot control the bot.

## Statistics, storage, and diagnostics

- [ ] Each Start Bot creates a new statistics session; opening/closing Statistics does not reset it.
- [ ] Verify score/coins/XP, round duration, box grades, recent-history limit (50), cumulative totals, and stale old-session analysis rejection.
- [ ] Toggle debug/result image switches during a run and confirm live save behavior.
- [ ] Verify result images in `Pictures/CookieRunBot/results/` and no-match pairs in `Pictures/CookieRunBot/debug/` (including legacy storage behavior where supported).
- [ ] Verify Debug Log refresh/copy/clear/save; saved logs use `Download/RobotRun/` or documented app-specific fallback.
- [ ] Verify persistent trace path/fallback/rotation and throttled health/DETECT_STALL logging without per-frame spam.
