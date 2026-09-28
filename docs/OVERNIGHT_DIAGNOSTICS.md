# RobotRun Overnight Diagnostics — source baseline v1.1.7 / versionCode 9

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
bot URLs are redacted in exported text. Raw screen/video can contain visible account/game
information; download only from the authorized Chat ID and keep archives private.

## Watchdog Incident and manual test

Each real overdue reservation creates one Incident. The explicit in-app **Test Watchdog
Recovery** action also creates a separate labeled `MANUAL_TEST` Incident when Bot/Capture
are running and there is no incident already active; it does **not** reset or reserve the
real Watchdog timer, and does not impersonate a real 15-minute timeout. If already
overdue/busy, the existing manual-test guard still applies. All evidence is best effort:

1. Freeze the previous **30 minutes of text logs**, current Bot/Capture/Watchdog status
   and a still normalized **1280x720 PNG**. The status records REAL_OVERDUE or MANUAL_TEST.
2. Append `[GAME_PROGRESS_*]` Recovery messages to
   `watchdog_action_timeline.log`, including template/score/attempt, logical and
   mapped physical target, dispatchAccepted, callback completion, newer frame and MAINMENU routing.
3. On any Recovery exit, write the outcome and signal the optional video worker to finish.
   Screenshot/text/timeline/outcome remain independently useful when video is missing.

## Incident video: event-triggered, lowest priority

Video is enabled by default, and can be disabled/enabled in Debug Log via
**Video ON / Video OFF**. Screen Capture start only **arms** the recorder: there is
**no recurring video sampler, pre-roll RAM ring, JPEG resizing/compression, or MP4
encoding during normal gameplay**. The video work starts only when a real or explicit
manual Watchdog Incident triggers, before the Recovery worker is requested.
No second MediaProjection/VirtualDisplay. It clones from the existing
`LatestFrameProvider`, with target **640x360 / 2 FPS**, JPEG quality **40**, and
H.264 bitrate **250 kbps** (no audio).

Recording stops at the earliest of:

- **Recovery finished + 10 seconds**, regardless of outcome;
- **3 minutes after the Watchdog/manual-test trigger**, with **no pre-roll**;
- **Screen Capture / recorder stops**, preserving available frames;
- **Resource pressure or per-Incident size cap**: stale/unavailable/latest repeated
  frames are skipped; after 10 consecutive degraded ticks (~5s nominal), or when
  frame save exceeds 20 MiB, stop. A sample taking over 250ms is treated as degraded.

Sampling and MP4 encoding each use separate lowest-priority worker threads.
No video operation blocks the detector, tap, watchdog scheduler or Recovery worker.
The encoder starts only **after** sampling stops. If capture is unhealthy before or
during encoding, skip/abort MP4; retain any already persisted JPEG files and
`incident_video_status.txt`. In a normal completion, successful MP4 replaces the
temporary JPEGs; if codec fails, keep JPEG fallback. Resource pressure is inferred
from capture freshness and recorder timing, **not a direct measurement of total
Redfinger CPU**. A video is never guaranteed; gameplay and diagnostic text come first.

Manual Test produces the same status/screenshot/timeline/outcome package and requests
video under the same optional/pressure rules. After the test Recovery completes and
the tail/optional encode finishes, **/logs** or local **Save 10h ZIP** returns the
Incident among the five most recent. A skipped video is visible via
`incident_video_status.txt`. The app does not auto-send the ZIP.

## Retrieve files

Send **/logs** or press **📥 Download Logs** in the persistent Telegram Dashboard. Only the
configured authorized token + Chat ID may request or receive the ZIP. One export/upload at a time
on \`telegram-log-delivery\` worker; successful file content is sent as original \`sendDocument\`.
No automatic log upload. The ZIP includes \`robotrun_10h.log\`, an export manifest and at most the
five most recent retained Incident folders. Incident videos share a 35 MiB uncompressed video
budget; the manifest notes omissions. A finished MP4 is preferred; if absent, original JPEG
frames are included within that budget. Telegram's public Bot API caps this upload at
49,000,000 bytes. On failure or oversize, open RobotRun **Debug Log → Save 10h ZIP** to export
locally under Downloads/RobotRun (Android 10+; legacy app Documents path otherwise).

When the entire Android process is stopped Telegram cannot answer until RobotRun is opened again.
Already written private log/incident files survive an ordinary process restart; app uninstall or
Clear Data removes private files. A source commit alone does not update an installed APK.
