package com.example.cookierunbot.automation

object AutomationConfig {
    // Logical 1280x720 coordinates copied from the Python start_game() action.
    const val MAINMENU_START_GAME_X = 955
    const val MAINMENU_START_GAME_Y = 650
    const val MAINMENU_START_GAME_JITTER = 15
    const val PURCHASE_ITEM_PLAY_X = 895
    const val PURCHASE_ITEM_PLAY_Y = 620
    const val PURCHASE_ITEM_PLAY_JITTER = 15

    // Logical detector coordinate space for the Phase 1 manual test.

    const val DEFAULT_SAFE_JITTER = 15
    const val LIVES_JITTER = 10
    const val MATCH_THRESHOLD = 0.8
    const val POLL_INTERVAL_MS = 120L
    const val MAX_FRAME_AGE_MS = 1000.0

    val FAST_START_USE = LogicalPoint(655, 340)
    val COOKIE_RELAY_USE = LogicalPoint(655, 340)
    val RANDOM_BOOST_ITEM = LogicalPoint(535, 600)
    val MULTI_PURCHASE = LogicalPoint(1100, 195)
    val MULTI_BUY = LogicalPoint(640, 587)
    val COMPLETE_FINISH = LogicalPoint(460, 625)
    val MYSTERY_BOX_ACCEPT = LogicalPoint(650, 645)
    val CONGRATULATIONS_ACCEPT = LogicalPoint(640, 565)
    val LEVEL_UP_ACCEPT = LogicalPoint(640, 640)
    val DAILY_CHECKIN_ACCEPT = LogicalPoint(640, 660)
    val DAILY_BOOST_ACCEPT = LogicalPoint(640, 568)
    val DAILY_TREASURE_ACCEPT = LogicalPoint(650, 570)
    val DAILY_NEW_ACCEPT = LogicalPoint(1128, 74)
    val ENTER_LEAGUE_ACCEPT = LogicalPoint(640, 460)
    val LEAGUE_RESULTS_ACCEPT = LogicalPoint(640, 610)
    val PREVIOUS_RANK_ACCEPT = LogicalPoint(640, 615)
    val TOO_MANY_TREASURES_ACCEPT = LogicalPoint(640, 545)
    val OVERTAKE_ACCEPT = LogicalPoint(465, 640)
    val RELIC_COMPLETE = LogicalPoint(530, 110)
    val RELIC_CLAIM = LogicalPoint(640, 580)
    val RELIC_CLOSE = LogicalPoint(1076, 154)
    val RELOAD = LogicalPoint(640, 460)
    val POST_RELAY_1 = LogicalPoint(1335, 37)
    val POST_RELAY_2 = LogicalPoint(648, 427)
    val POST_RELAY_3 = LogicalPoint(648, 380)
    // These three targets were calibrated as absolute 2400x1080 phone pixels.
    // Their logical values above came from the old ADB offset and must not be
    // remapped through the orientation-aware Android content rect.
    val POST_RELAY_1_PHYSICAL = PhysicalPoint(2180, 55)
    val POST_RELAY_2_PHYSICAL = PhysicalPoint(1150, 640)
    val POST_RELAY_3_PHYSICAL = PhysicalPoint(1150, 570)
    const val POST_RELAY_RADIUS = 5.0
    val AUTO_JUMP = LogicalPoint(108, 600)
    const val AUTO_JUMP_PHONE_RADIUS = 100.0
    const val FIXED_JUMP_DELAY_MS = 1700L
    val MAIL_BOX = LogicalPoint(686, 677)
    val MAIL_LIVES_TAB = LogicalPoint(630, 148)
    val MAIL_CLOSE = LogicalPoint(1130, 87)
    val QUICK_LIVES = LogicalPoint(652, 612)
    val ALL_LIVES_CONFIRM = LogicalPoint(640, 462)
    val SEND_LIFE_CONFIRM = LogicalPoint(797, 460)
    val SEND_LIFE_CLOSE = LogicalPoint(645, 463)
    val MAINMENU_FRIEND_CONFIRM_1 = LogicalPoint(801, 453)
    val MAINMENU_FRIEND_CONFIRM_2 = LogicalPoint(648, 460)
    const val FRIEND_PHYSICAL_RADIUS = 5.0
    val LEADERBOARD_TOP = LogicalPoint(435, 260)
    val LEADERBOARD_BOTTOM = LogicalPoint(435, 620)
    val PARTY_RUN_CLOSE = LogicalPoint(1214, 89)
    // Already calibrated phone pixels; never pass this target through CoordinateMapper.
    val PARTY_RUN_SEASON_REWARDS_CONFIRM_PHYSICAL = PhysicalPoint(1260, 980)
    val GAME_SETTINGS_CLOSE = LogicalPoint(1116, 98)
    val ANNOUNCEMENT_CLOSE = LogicalPoint(1127, 66)
}

data class LogicalPoint(val x: Int, val y: Int)
