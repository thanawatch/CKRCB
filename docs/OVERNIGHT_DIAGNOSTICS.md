# RobotRun Overnight Diagnostics — source baseline v1.1.5 / versionCode 7

This feature is independent of gameplay automation. It never changes template/ROI/threshold, the
15-minute watchdog timeout, the only two watchdog progress-reset events (GAME_COMPLETE and
successful FRIEND_SEND_LIFE_TAP), Accessibility gestures, Friend Life, or detector state rules.

## Lifetime and storage

- Application-owned Logcat messages are routed through \`RobotLog\` into a private, background
  text archive along with \`DebugLogStore\` and \`PersistentTrace\` events. The old on-screen
  Debug Log is still a **500-line RAM preview**; its Clear button only clears that preview.
- \`files/diagnostics/rolling/log_<UTC epoch hour>.txt\` rotates hourly, indexes each line by
  wall-clock epoch milliseconds, and exports only the last **24 hours**. Whole expired hourly
  files are pruned hourly; the oldest file may contain an extra partial hour on disk until
  its next rotation. Archive has an additional 200 MiB size safety bound. A bounded 8192-event
  queue avoids disk I/O on detector/capture threads; \`[ARCHIVE_LOSS]\` identifies dropped or
  size-pruned records. A 24-hour timestamp window cannot promise full coverage after a size cap,
  storage/permission error, power loss or abrupt process termination.
- Existing 5s \`[POWER]\`, \`[CAPTURE_HEALTH]\` and \`[DETECT_HEALTH]\` diagnostics continue
  while capture service is alive. A previous process can be identified by fresh
  \`[ARCHIVE] PROCESS_START\` markers. A missing clean shutdown alone is not proof of a crash.
- Private Incident directories in \`files/diagnostics/incidents/\` have separate 72-hour retention
  and 200 MiB size cap. This protects overnight evidence from routine 24-hour text rotation
  subject to the total incident cap. A user-requested ZIP is exported to private cache and
  deleted after delivery/local copy.

The archive contains only RobotRun-generated events, NOT all system/other-app Logcat records,
and does not retain every image frame as a text log. Token/password-looking strings and Telegram
bot URLs are redacted in exported text. Raw screen/video can contain visible account/game
information; download only from the authorized Chat ID and keep archives private.

## Watchdog Incident

Once per **real** watchdog overdue Recovery reservation (not every 30s check, not manual test):
1. Freeze the previous **30 minutes of logs**, current bot/capture/watchdog state and one still PNG
   from a cloned normalized 1280×720 frame.
2. Append \`[GAME_PROGRESS_*]\` Recovery action events to \`watchdog_action_timeline.log\`. The
   actions remain owned by the normal Recovery worker; the logger observes them and never taps.
   This includes template/score/attempt, logical and mapped physical target, dispatchAccepted,
   callback completion, newer frame and MAINMENU routing.
3. On any Recovery exit, append an outcome file, notify the video recorder of the finish
   timestamp, and retain the Incident separately.

## Incident video: EXACT end condition

Video recording is enabled by default in this explicitly opted-in source version and can be
disabled/enabled in Debug Log via **Video ON / Video OFF**. It uses the EXISTING
\`LatestFrameProvider\` snapshot (no second MediaProjection/VirtualDisplay). A low-priority
sampler prepares 960×540 JPEGs at a target of 3 FPS, keeps up to the previous 30 seconds in a
bounded ~12 MiB RAM ring, and writes the relevant frames to the active Incident folder.
Recording stops at the EARLIEST of:

- **Recovery finished + 10 seconds**, success, failure or cancellation;
- **3 minutes after the Watchdog trigger**, hard incident-video cap;
- **Screen Capture / recorder stops**, preserving available frames.

Only after recording stops does a separate low-priority worker encode H.264 MP4 with
\`MediaCodec\` / \`MediaMuxer\`. If the encoder is unsupported/fails or Android kills the process,
\`incident_video_status.txt\` and any already persisted JPEG frames can still be examined as a
fallback. A trigger less than 30s after recorder start has only the available pre-roll.
Never assume the MP4 is ready immediately when the watchdog alert appears; retrieve /logs after
Recovery and encoding completes. Source/static review is not evidence of successful MP4 playback
on a real Android/Redfinger device.

## Retrieve files

Send **/logs** or press **📥 Download Logs** in the persistent Telegram Dashboard. Only the
configured authorized token + Chat ID may request or receive the ZIP. One export/upload at a time
on \`telegram-log-delivery\` worker; successful file content is sent as original \`sendDocument\`.
No automatic log upload. The ZIP includes \`robotrun_24h.log\`, an export manifest and at most the
five most recent retained Incident folders. Incident videos share a 35 MiB uncompressed video
budget; the manifest notes omissions. A finished MP4 is preferred; if absent, original JPEG
frames are included within that budget. Telegram's public Bot API caps this upload at
49,000,000 bytes. On failure or oversize, open RobotRun **Debug Log → Save 24h ZIP** to export
locally under Downloads/RobotRun (Android 10+; legacy app Documents path otherwise).

When the entire Android process is stopped Telegram cannot answer until RobotRun is opened again.
Already written private log/incident files survive an ordinary process restart; app uninstall or
Clear Data removes private files. A source commit alone does not update an installed APK.
