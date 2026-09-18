# CookieRun Bot Android — final source parity audit

Final Python rescan: 2026-09-14. Python reference remained read-only.

## DONE

- Native runtime pipeline: MediaProjection foreground service → latest frame only → normalized 1280x720 → OpenCV → grouped controller → Accessibility gestures.
- 43/43 PNG assets match the Python filenames and SHA-256 hashes.
- 24/24 stage template families use the Python ROI, grayscale `TM_CCOEFF_NORMED`, threshold 0.8 and source ordering.
- Multi-template variants (`CONGRATULATIONS`, `CONNECTION_LOST`) and 0.70–1.30 transient scales (`GAME_START`, `GAME_RELAY`).
- Detection groups PRE_GAME, IN_GAME, POST_GAME; CONNECTION_LOST/INACTIVE always-stage priority; recovery full scans at 60/300/30 seconds.
- MAINMENU 5-second refresh, optional post-game flows, 2–3 second next-round wait and start action.
- PURCHASE_ITEM round reset, optional Desired Random Boost purchase/detection and immediate Play → IN_GAME polling.
- GAME_START Fast Start use-only action and GAME_START-triggered Jump by Count / Auto Jump.
- GAME_RELAY priority, Cookie Relay use-only action and one-per-round post-relay sequence (2–4s, 0.6–2s, 0.6–2s; circular radius 5).
- GAME_COMPLETE stops jump worker, waits 7 seconds, saves latest normalized frame once, then taps Finish.
- MYSTERY_BOX counter: first tap without image; second detection waits 3 seconds, saves once, then taps.
- Popup/reward handlers: congratulations, level, daily check-in/boost/treasure/new, league, previous rank, overtake, treasures, relic, party-run and settings.
- CONNECTION_LOST reload-only behavior; no force-stop/restart of the game.
- INACTIVE reload behavior and anti-bot odd-card comparison/taps.
- Main-menu Send Friend Life rescan loop with 5-physical-pixel circular randomization.
- Quick Receive/Send Lives after each game, MAINMENU wait 5–6s, ±10 canonical jitter, one-per-round guards.
- Settings UI equivalents for every option used by current Python `main()`, including dependent post-relay option, 11 boost choices, mutually-exclusive jump modes, count and interval validation.
- Cancellable background bot loop and separate jump worker; Stop Bot joins/stops workers and does not stop MediaProjection.
- Logical-to-physical content mapper supports rotation/aspect ratio and intentional post-relay x=1335 without canonical clamping.
- Android app-private result storage with round/stage/timestamp filenames; no broad storage permission.
- GAME_START, GAME_RELAY and Desired Boost annotated debug-region PNG output, written after priority actions.
- Process-level `BotRuntime` owns the controller, so ordinary MainActivity recreation/backgrounding does not stop automation; capture remains service-owned.
- Logcat/UI logging includes state, group, template score, action, logical/physical coordinates, result path and recovery reason.
- Unit tests for option validation and verified 2400x1080 coordinate mapping, including post-relay x=1335.
- `assembleDebug`, `lintDebug`, and unit tests complete successfully; APK installs and launches without a startup crash.

## PARTIAL — external real-game validation

- Template/action parity is implemented but every state cannot be produced deterministically without playing real rounds. Use `ANDROID_TEST_CHECKLIST.md` before treating production automation as proven.
- Android uses Logcat/UI diagnostic logging as requested by the Android architecture; Python's terminal-only `bot_log.txt` is not applicable to the native APK runtime.

## MISSING FROM THE REACHABLE PYTHON MAIN WORKFLOW

- None identified in the final rescan. Items below are either dormant Python helpers or platform-blocked behavior.

## DORMANT PYTHON HELPERS (implemented, not exposed by current workflow)

- `purchase_fast_start`, `purchase_cookie_relay`, `purchase_random_boost`: equivalents are retained in `LegacyActionParity`; current startup workflow still uses owned Fast Start/Cookie Relay and only purchases Desired Random Boost.
- `handle_send_friend_life`: its template/scroll/tap loop is retained in `LegacyActionParity`; current `main()` behavior remains `handle_mainmenu_send_friend_life`.

These are accounted for and intentionally not exposed as Android settings because doing so would add behavior that the current Python main loop does not offer.

## BLOCKED / ANDROID-NATIVE EQUIVALENT

- Python session reset can call ADB `force-stop`/relaunch. A normal Android APK cannot force-stop another app. Android logs the condition and performs the available announcement/UI-close recovery taps instead; it never requests privileged permissions.
- Windows ADB/scrcpy/PyAV device discovery, server push, port forwarding and shell-input plumbing are NOT APPLICABLE. MediaProjection and AccessibilityService are their Android-native equivalents.

## Final counts

- Python stage template families: 24; Android configured: 24.
- Template PNG files: Python 43; Android 43; hash mismatches: 0.
- Detection groups: Python 3 + 2 always stages; Android 3 + 2 always stages.
- Python `actions.py` functions: 33; Android semantic equivalents: 33 (29 active workflow actions + 4 dormant helpers).
- Android unit tests: 5 passing.
- Remaining source-level implementable workflow gaps identified: 0. Real-device state coverage remains unverified.

Because real-game state coverage has not yet been executed end-to-end, this document does not claim the port is production-verified. Source-level reachable workflow parity is implemented; device verification remains mandatory.
