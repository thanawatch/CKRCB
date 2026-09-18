# CookieRun Bot Android — Python source audit

วันที่ตรวจ: 2026-09-14

แหล่งอ้างอิงที่ตรวจ: `C:\bot\cookierun-classic-bot-main` (อ่านอย่างเดียว)  
โปรเจกต์เป้าหมาย: `C:\bot\CookieRunBotAndroid`

เอกสารนี้เป็น inventory จาก source ปัจจุบัน ไม่ใช่รายการที่สร้างจากความจำ และจะใช้เป็น baseline สำหรับ final parity audit

## Architecture / runtime

| Feature | Python source/function | Trigger / behavior | Android target | Status |
|---|---|---|---|---|
| USB/ADB connection | `adb.py:device_connect`, `_get_usb_serial` | ตรวจ USB serial และเชื่อมต่อก่อนเริ่ม | ไม่ใช้ ADB เป็น runtime; ใช้ MediaProjection + Accessibility | NOT APPLICABLE ON ANDROID |
| ADB capture fallback | `adb.py:device_capture_screen`, `_normalize_phone_screen_with_perf` | `adb -s SERIAL exec-out screencap -p`, crop/resize 1280x720 | MediaProjection latest-frame | NOT APPLICABLE ON ANDROID |
| scrcpy capture | `capture.py:ScrcpyCaptureBackend`, `capture_screen` | H.264 stream, PyAV decode, เก็บ frame ล่าสุด, fallback ADB | MediaProjection latest-frame | PARTIAL (Android equivalent exists; no Android full parity audit yet) |
| logical frame | `adb.py:normalize_phone_screen` | crop phone content แล้ว resize 1280x720 | `FrameNormalizer` | DONE (Phase 1) |
| template matching | `detection.py:get_template_match_score`, `detect_templates` | grayscale + `TM_CCOEFF_NORMED`, threshold 0.8 | `StateDetector` | PARTIAL |
| console/file logging | `main.py:_install_console_file_logging` | timestamp ทุกข้อความ, UTF-8, flush | Android Logcat/UI status | PARTIAL |
| stop/cleanup | `main.py` finally, `capture.shutdown_capture` | stop tapper, close capture backend, close log | service/controller lifecycle | PARTIAL |

## Detection templates, ROIs and groups

Threshold: `MATCH_THRESHOLD = 0.8`.  
All coordinates below are logical 1280x720 coordinates. Regions use `(x1,y1,x2,y2)`.

| Stage/template | ROI | Group / ordering | Android asset/detector |
|---|---:|---|---|
| MAINMENU_1 | (154,150,344,225) | PRE_GAME, POST_GAME | asset + detector (Phase 3) |
| PURCHASE_ITEM_1 | (474,84,602,138) | PRE_GAME | asset + detector (Phase 3) |
| GAME_START_1 | (500,200,800,500) | IN_GAME | asset + multi-scale detector (Phase 3) |
| GAME_RELAY_1 | (500,200,800,500) | IN_GAME, priority before other in-game stages | implemented |
| GAME_COMPLETE_1 | (523,27,757,116) | IN_GAME | implemented |
| MYSTERY_BOX_1 | (503,46,782,107) | POST_GAME | implemented |
| CONGRATULATIONS_1/2 | (372,85,909,200) | PRE_GAME | implemented |
| LEVEL_UP_1 | (481,34,798,130) | PRE_GAME | implemented |
| RELIC_COMPLETE_1 | (480,63,578,106) | PRE_GAME | implemented |
| RELIC_CLAIM_1 | (412,72,865,191) | PRE_GAME | implemented |
| DAILY_CHECKIN_1 | (491,21,792,75) | PRE_GAME | implemented |
| DAILY_CHECKIN_BOOST_SET_1 | (523,88,754,182) | PRE_GAME | implemented |
| DAILY_TREASURE_1 | (452,117,824,161) | PRE_GAME | implemented |
| DAILY_NEW_1 | (432,12,849,78) | PRE_GAME | implemented |
| ENTER_LEAGUE_1 | (318,235,962,384) | PRE_GAME | implemented |
| LEAGUE_RESULTS_1 | (512,52,779,105) | PRE_GAME | implemented |
| ANTI_BOT_1 | (309,10,968,111) | always/recovery | implemented |
| CONNECTION_LOST_1/2 | (351,203,936,405) | always/recovery | implemented |
| INACTIVE_1 | (358,247,934,372) | always/recovery | implemented |
| PREVIOUS_RANK_RESULTS_1 | (454,42,832,115) | PRE_GAME | implemented |
| TOO_MANY_TREASURES_1 | (353,154,931,476) | PRE_GAME | implemented |
| OVERTAKE_BREAK_SCORE_1 | (479,64,799,132) | POST_GAME | implemented |
| PARTY_RUN_1 | (359,44,922,138) | PRE_GAME | implemented |
| GAME_SETTINGS_1 | (484,62,769,122) | PRE_GAME | implemented |

Additional non-stage detection templates and ROIs:

| Template | ROI |
|---|---:|
| FRIEND_TOP_LEADERBOARD_1 | (149,272,203,574) |
| FRIEND_BOTTOM_LEADERBOARD_1 | (272,545,610,625) |
| FRIEND_SEND_LIFE_1 | (580,250,760,650) |
| NO_LIVES_TO_RECEIVE_1 | (454,223,824,575) |
| ALL_LIVES_RECEIVED_AND_SENT_1 | (396,267,882,346) |
| CONFIRM_SEND_LIFE_1 | (640,402,944,515) |
| BOOST_* (including BOOST_MAGNETIC_AURA_1) | RANDOM_BOOST_REGION (640,470,1160,620) |

Groups in `config.py`:

- `DETECTION_ALWAYS_STAGES`: `CONNECTION_LOST`, `INACTIVE`
- `PRE_GAME`: congratulations, level/relic/daily/league/party/settings popup handlers, `MAINMENU`, `PURCHASE_ITEM`
- `IN_GAME`: `GAME_RELAY`, `GAME_COMPLETE`, `GAME_START`
- `POST_GAME`: `ANTI_BOT`, `OVERTAKE_BREAK_SCORE`, `MYSTERY_BOX`, `MAINMENU`
- recovery scan intervals: PRE_GAME 60s, IN_GAME 300s, POST_GAME 30s
- transient button scales: 0.70, 0.80, 0.90, 1.00, 1.10, 1.20, 1.30

## Actions / coordinates

Python action functions in `actions.py`:

`start_game`, `play_game`, `purchase_fast_start`, `purchase_cookie_relay`, `purchase_random_boost`, `purchase_desired_random_boost`, `using_fast_start`, `using_cookie_relay`, `execute_post_cookie_relay_sequence`, `complete_finish`, `accept_mystery_box`, `accept_congratulations`, `accept_level_up`, `accept_daily_checkin`, `accept_daily_checkin_boost_set`, `accept_daily_treasure`, `accept_daily_new`, `accept_enter_league`, `accept_league_results`, `accept_previous_rank_results`, `accept_too_many_treasures`, `accept_overtake_break_score`, `open_relic_complete`, `accept_relic_claim`, `handle_anti_bot`, `handle_connection_lost`, `handle_inactive`, `handle_send_friend_life`, `handle_mainmenu_send_friend_life`, `handle_quick_receive_and_send_lives`, `close_announcement_dialog`, `close_party_run_mode`, `close_game_settings`.

Important coordinates (logical):

- start/play: `(955,650)`, `(895,620)`
- purchases: `(235,600)`, `(385,600)`, `(535,600)`, purchase `(925,295)`, multi-purchase `(1100,195)`, multi-buy `(640,587)`
- use Fast Start/Cookie Relay: `(655,340)`
- post-relay sequence: `(1335,37)`, `(648,427)`, `(648,380)`, circular radius 5
- finish/popup buttons: `(460,625)`, `(650,645)`, `(640,565)`, `(640,640)`, `(640,660)`, `(640,568)`, `(650,570)`, `(1128,74)`, `(640,460)`, `(640,610)`, `(640,615)`, `(640,545)`, `(465,640)`
- relic: `(530,110)`, `(640,580)`, close `(1076,154)`
- recovery reload: `(640,460)` for connection lost/inactive
- mail/lives: `(686,677)`, `(630,148)`, `(1130,87)`, `(652,612)`, `(640,462)`, `(797,460)`, `(645,463)`
- friend/leaderboard: `(435,260)`, `(435,620)`, and friend-send confirm `(801,453)`, `(648,460)`
- party/settings close: `(1214,89)`, `(1116,98)`; announcement `(1127,66)`

ADB's Windows mapping is intentionally not an Android requirement: `phone_x = x * 1.5 + 178`, `phone_y = y * 1.5`. Android must use its content-area `CoordinateMapper`.

## Startup options and dependencies

`bot.py:prompt_user_options` asks:

1. owned Fast Start (use only; no purchase)
2. owned Cookie Relay (use only; no purchase)
3. post-Cookie-Relay sequence (only when Cookie Relay is yes)
4. Desired Random Boost (purchase + use), then boost choice
5. Detect Relic (open + claim)
6. Send Friend Life
7. Receive/Send Lives after each game
8. Jump by Count; if no, Auto Jump
9. Number of taps (fixed mode)
10. Tap interval 100–1000ms (only when either jump mode is enabled)

Jump modes are mutually exclusive. Fixed mode waits `FIXED_TAP_START_DELAY = 1.7s`, uses `AUTO_TAP_POSITION=(108,600)` and physical radius 100px (canonical radius 100/1.5). Continuous mode runs on a cancellable worker until game complete/connection lost/exit. Both use circular randomization, not independent square jitter.

## Delays, timers, flags and counters

- random action waits generally 0.8–1.4s; use actions 0.8–1.2s; purchase confirmation 1–2s
- GAME_START immediate polling timeout 8s; no artificial fast-poll sleep beyond capture latency
- post-relay wait 2–4s, then 0.6–2s and 0.6–2s; circular radius 5
- result image waits: GAME_COMPLETE 7s before save; second MYSTERY_BOX detect 3s before save; mystery accept then 3s
- Quick Lives after MAINMENU waits random 5–6s
- next-game delay random 2–3s; initial MAINMENU refresh wait 5s
- relic close wait 10–15s; inactive 10–15s; anti-bot 10–15s
- session reset interval random 5400–10800s, app-reset legacy path in Python
- per round: `round_number`, `game_start_detected_for_round`, `post_relay_sequence_done`, `game_complete_image_saved_for_round`, `mystery_box_image_saved_for_round`, `mystery_box_detect_count_for_round`, auto tap worker state
- main-menu/session: `last_stage`, `detection_group`, `last_detected_time`, `lives_pending_after_game`, `lives_done_for_current_round`, `send_friend_life_checked_for_this_mainmenu`, relay best/debug data
- stop paths: KeyboardInterrupt, exception, bot exit; tap worker is stopped in `finally`

## Capture/debug/result behavior

- `debug.py:save_debug_screen` writes `debug_screen.png`.
- `detection.py:save_debug_match` writes debug regions for transient detections.
- `debug.py:save_result_image` creates `results/round_<n>_<stage>_<timestamp>.png` from the normalized frame.
- GAME_COMPLETE: stop jump worker → wait 7s → capture latest normalized frame → save once → finish.
- MYSTERY_BOX: first detect taps without saving; second detect waits 3s, saves once, then taps.
- `main.py` wraps `print` with timestamped UTF-8 `bot_log.txt` and flushes each line.

## Android implementation targets

The native equivalent must keep this pipeline: MediaProjection foreground service → latest normalized frame only → OpenCV detector → grouped state machine → cancellable ActionExecutor → AccessibilityService. ADB/scrcpy are not runtime dependencies of the APK. Features not yet implemented are explicitly listed in `PORTING_STATUS.md`.
