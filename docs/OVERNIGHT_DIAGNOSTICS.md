# RobotRun Overnight Diagnostics — source baseline v1.1.10 / versionCode 12

This feature is independent of gameplay automation. It never changes template/ROI/threshold, the
15-minute watchdog timeout, the only two watchdog progress-reset events (GAME_COMPLETE and
successful FRIEND_SEND_LIFE_TAP), Accessibility gestures, Friend Life, or detector state rules.

## Lifetime and storage

- Application-owned Logcat messages are routed through \`RobotLog\` into a private, background
  text archive along with \`DebugLogStore\` and \`PersistentTrace\` events. The old on-screen
  Debug Log is still a **500-line RAM preview**; its Clear button only clears that preview.
- \`files/diagnostics/rolling/log_<UTC epoch hour>.txt\` rotates hourly, indexes each line by
  wall-clock epoch milliseconds, and exports only the last **10 hours**. Whole expired hourly
  files are pruned hourly; the oldest file may contain an extra partial hour on disk until
  its next rotation. Archive has an additional 200 MiB size safety bound. A bounded 8192-event
  queue avoids disk I/O on detector/capture threads; \`[ARCHIVE_LOSS]\` identifies dropped or
  size-pruned records. A 10-hour timestamp window cannot promise full coverage after a size cap,
  storage/permission error, power loss or abrupt process termination.
- Existing 5s \`[POWER]\`, \`[CAPTURE_HEALTH]\` and \`[DETECT_HEALTH]\` diagnostics continue
  while capture service is alive. A previous process can be identified by fresh
  \`[ARCHIVE] PROCESS_START\` markers. A missing clean shutdown alone is not proof of a crash.
- Private Incident directories in \`files/diagnostics/incidents/\` have separate 72-hour retention
  and 200 MiB size cap. This protects overnight evidence from routine 10-hour text rotation
  subject to the total incident cap. A user-requested ZIP is exported to private cache and
  deleted after delivery/local copy.

The archive contains only RobotRun-generated events, NOT all system/other-app Logcat records,
and does not retain every image frame as a text log. Token/password-looking strings and Telegram
bot URLs are redacted in exported text. Raw screenshots and legacy video can contain visible account/game
information; download only from the authorized Chat ID and keep archives private.

## Watchdog Incident and manual test

Each real overdue reservation creates one Incident. The explicit in-app **Test Watchdog
Recovery** action also creates a separate labeled `MANUAL_TEST` Incident when Bot/Capture
are running and there is no incident already active; it does **not** reset or reserve the
real Watchdog timer, and does not impersonate a real 15-minute timeout. If already
overdue/busy or any Incident is still active, the manual test is rejected rather than claiming a new file was created. All evidence is best effort:

1. Freeze the previous **30 minutes of text logs**, current Bot/Capture/Watchdog status
   and a still normalized **1280x720 PNG**. The status records REAL_OVERDUE or MANUAL_TEST.
2. Append `[GAME_PROGRESS_*]` Recovery messages to
   `watchdog_action_timeline.txt`, including template/score/attempt, logical and
   mapped physical target, dispatchAccepted, callback completion, newer frame and MAINMENU routing.
3. On any Recovery exit, write the outcome and signal the optional image sampler to finish.
   Screenshot/text/timeline/outcome remain independently useful when optional images are missing.

## Incident screenshot sequence: event-triggered, lowest priority

Images are enabled by default and can be disabled/enabled in Debug Log via
**Images ON / Images OFF**. The prior `incident_video_enabled` preference is deliberately
reused, preserving existing user settings. Screen Capture start only arms the sampler:
**no recurring image sampling, resizing, JPEG compression or MP4 encoding during
normal gameplay**. Only a real overdue Watchdog or explicit Manual Test Incident triggers
sampling, after the essential status/1280×720 PNG/30-minute text snapshot is queued.
Recovery never waits for optional images. The recorder uses the existing
`LatestFrameProvider` (no second MediaProjection or VirtualDisplay).

- Sample normalized frames at **480×270 JPEG quality 35** nominally **once every 2 seconds**
  (up to **90 images**; maximum **20 MiB** per Incident).
- Stop at Recovery finished + **10 seconds**, or **3 minutes from trigger** (whichever is
  earlier), or on capture stop, image/size limit, persistent resource pressure, or error.
  No pre-roll, no H.264 codec, no muxing and no MP4 for newly created Incidents.
- Stale/invalid/duplicate capture frames, capture FPS under 2, or sustained app-process CPU
  pressure skip individual samples *before cloning*. Samples exceeding **250ms wall time**
  or **80ms sampler-thread CPU** cause the next two-second tick to be skipped instead of
  immediately terminating after two costly images. **Ten consecutive degraded ticks**
  (~20s nominal) stop the sampler; previously completed JPEGs are retained. Process CPU
  is only RobotRun's CPU, not Redfinger's total cloud-host CPU.
- Separate, lowest-priority sampler thread; no synchronous waiting from capture,
  detection, gestures, Watchdog or Recovery. JPEG files are written as `.part` and
  renamed to numbered `screenshots/000001.jpg` etc. only after completion.
  `images_timing.txt` maps each filename to elapsedRealtimeNanos and frame generation;
  `incident_image_status.txt` reports outcome, saved/skipped/expensive counts.
  `[INCIDENT_IMAGES]` archive lines report sample wall time and thread CPU cost,
  permitting diagnosis of expensive sampling without falsely asserting total VM load.
- The trigger's essential `watchdog_screenshot.png` retains the original 1280×720
  resolution independently of optional screenshot sampling.

Manual Test follows the identical optional sampler policy, then `/logs` or local
**Save 10h ZIP** includes the sequence with the newest five retained Incidents (subject to
ZIP/media caps). New images are never automatically sent. Legacy Incident MP4/JPEG files
remain exportable; only newly triggered Incidents use the image sequence.

## Retrieve files

Send **/logs** or press **📥 Download Logs** in the persistent Telegram Dashboard. Only the
configured authorized token + Chat ID may request or receive the ZIP. One export/upload at a time
on \`telegram-log-delivery\` worker; successful file content is sent as original \`sendDocument\`.
No automatic log upload. The ZIP includes \`robotrun_10h.txt\`, an export manifest and at most the
five most recent retained Incident folders. Stored legacy `.log` Incident entries are named `.txt` in the exported ZIP only; the on-device source files and their contents are unchanged. Optional screenshot sequences and legacy incident videos share a 35 MiB uncompressed media
budget; the manifest notes omissions. Legacy MP4 is preferred over legacy JPEG fallback,
but new `screenshots/*.jpg` is always eligible within that budget. Telegram's public Bot API caps this upload at
49,000,000 bytes. On failure or oversize, open RobotRun **Debug Log → Save 10h ZIP** to export
locally under Downloads/RobotRun (Android 10+; legacy app Documents path otherwise).

When the entire Android process is stopped Telegram cannot answer until RobotRun is opened again.
Already written private log/incident files survive an ordinary process restart; app uninstall or
Clear Data removes private files. A source commit alone does not update an installed APK.

## Independent two-template GAME_COMPLETE / CONFIRM_SEND_LIFE popup

The separate `GAME_COMPLETE_SEND_LIFE_POPUP` auxiliary probe looks for
`CONFIRM_SEND_LIFE_1.png` AND `GAME_COMPLETE_1.png` on the **same normalized
1280×720 frame**, with each using its own **full-frame ROI** and threshold **0.80**.
To limit gameplay overhead it first probes CONFIRM_SEND_LIFE; only when that matches
does it scan GAME_COMPLETE. The probe runs before routing a legacy GAME_COMPLETE
match, and is otherwise throttled to every 1.5 seconds outside Fast Start's priority
window. Thus the ordinary GAME_COMPLETE action/watchdog progress is never invoked on
a frame where the auxiliary pair matches. Neither the original stage
`GAME_COMPLETE` ROI/threshold/transitions nor Quick Lives' existing
`CONFIRM_SEND_LIFE_1.png` ROI is changed.

When both match, wait **1,600ms**, verify the capture is still fresh and display
geometry unchanged, then tap the exact logical center of the matched confirm
rectangle through the current CoordinateMapper (zero jitter). A callback timeout
is 3 seconds. Do not dispatch another detector action while that callback is
pending; on completion, require a newer frame to redetect without changing the
current detection group or recording GAME_COMPLETE watchdog progress. Limit the
same continuously visible dual popup to three attempts; rearm after a probe
actually sees the pair disappear. If only one template matches, the existing
stage/detector path continues unmodified. Logs use `[GAME_COMPLETE_SEND_LIFE]`.
