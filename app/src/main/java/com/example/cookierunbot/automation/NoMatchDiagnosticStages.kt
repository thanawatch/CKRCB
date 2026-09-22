package com.example.cookierunbot.automation

/** Candidate scoring only. This list never participates in normal detection priority. */
internal fun noMatchDiagnosticStages(group: DetectionGroup): List<BotStage> = when (group) {
    DetectionGroup.PRE_GAME -> listOf(
        BotStage.MAINMENU,
        BotStage.PURCHASE_ITEM,
        BotStage.PARTY_RUN,
        BotStage.PARTY_RUN_SEASON_REWARDS,
        BotStage.TIME_DEAL,
        BotStage.PREVIOUS_RESULTS,
        BotStage.GAME_SETTINGS,
        BotStage.DAILY_CHECKIN,
        BotStage.DAILY_CHECKIN_BOOST_SET,
        BotStage.DAILY_TREASURE,
        BotStage.DAILY_NEW,
        BotStage.CONGRATULATIONS,
        BotStage.LEVEL_UP,
        BotStage.RELIC_COMPLETE,
        BotStage.RELIC_CLAIM,
        BotStage.CONNECTION_LOST,
        BotStage.INACTIVE,
    )
    DetectionGroup.POST_GAME -> listOf(
        BotStage.ANTI_BOT,
        BotStage.OVERTAKE_BREAK_SCORE,
        BotStage.MYSTERY_BOX,
        BotStage.MAINMENU,
        BotStage.CONNECTION_LOST,
        BotStage.INACTIVE,
    )
    DetectionGroup.IN_GAME -> listOf(
        BotStage.GAME_START,
        BotStage.GAME_RELAY,
        BotStage.GAME_COMPLETE,
        BotStage.CONNECTION_LOST,
        BotStage.INACTIVE,
    )
}
