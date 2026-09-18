# Android parity test checklist

Use a real landscape device with Cookie Run Classic. Mark each item only after observing Logcat/UI and the game screen.

## Capture

- [ ] Start Screen Capture asks for MediaProjection permission.
- [ ] Capture service remains active while MainActivity is backgrounded.
- [ ] Latest-frame age remains low; old frames are replaced, not queued.
- [ ] Rotate portrait ↔ landscape; ImageReader/VirtualDisplay resolution and orientation update.
- [ ] Stop Screen Capture releases projection, virtual display and reader.

## Detection

- [ ] Normalized frame is exactly 1280x720.
- [ ] MAINMENU score/match agrees with Python template at the same screen.
- [ ] Threshold remains 0.8; no template is silently resized or regenerated.
- [ ] Each stage logs template, score, ROI and match position without UI-thread blocking.

## Pre-game

- [ ] MAINMENU action taps the Python start coordinate after a positive detection.
- [ ] Duplicate MAINMENU detections do not tap repeatedly.
- [ ] PURCHASE_ITEM detection and all selected purchase/use options work.
- [ ] GAME_START is polled promptly and Fast Start is used only when selected.
- [ ] Desired boost polling reports score and uses the selected boost only.

## In-game

- [ ] GAME_RELAY is prioritized and Cookie Relay is used once per appearance.
- [ ] Optional post-relay sequence uses circular radius 5 and exact randomized delays.
- [ ] Jump-by-count waits 1.7s, taps validated count, then stops.
- [ ] Auto Jump runs off the detection loop with configured interval and circular 100-phone-pixel radius.
- [ ] GAME_COMPLETE stops jump worker immediately.

## Post-game / results

- [ ] GAME_COMPLETE waits 7s, saves normalized image before Finish, then continues.
- [ ] MYSTERY_BOX first detect taps without saving; second detect waits 3s, saves once, then taps.
- [ ] Per-round flags/counters reset exactly when a new game begins.
- [ ] Popup/reward sequence returns to MAINMENU.

## Optional features

- [ ] Quick Receive/Send Lives is asked/configured only when enabled, waits 5–6s at MAINMENU and runs once per round.
- [ ] Friend Life sequence searches template, confirms only when found, and handles multiple buttons without hard-coded blind taps.
- [ ] Relic, daily, league, treasure, party/settings and announcement handlers work.

## Recovery

- [ ] CONNECTION_LOST uses reload gesture without force-stopping the game.
- [ ] INACTIVE uses its configured reload/recovery gesture.
- [ ] Anti-bot handling is safe and observable; failure stops or requests user help.
- [ ] Accessibility disabled / capture stopped / stale frame never causes a tap.

## Result storage and stop behavior

- [ ] Result directory is created automatically and filenames include round, stage and timestamp.
- [ ] Stop Bot cancels state loop and tap workers; no tap occurs after stop.
- [ ] Stop Capture does not leave ImageReader/VirtualDisplay resources or background callbacks.
- [ ] Logcat filters `CookieRunBot`, `[BOT]`, `[DETECT]`, `[ACTION]`, `[CAPTURE]`, `[RESULT]`, `[RECOVERY]`, `[ACCESSIBILITY]` show enough evidence to compare with Python `bot_log.txt`.
