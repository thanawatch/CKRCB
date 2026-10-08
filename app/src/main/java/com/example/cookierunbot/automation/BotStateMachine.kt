package com.example.cookierunbot.automation

enum class BotStage {
    LOGIN,
    MAINMENU,
    PURCHASE_ITEM,
    GAME_START,
    GAME_RELAY,
    GAME_COMPLETE,
    MYSTERY_BOX,
    CLOSE_BUTTON,
    CONGRATULATIONS,
    LEVEL_UP,
    RELIC_COMPLETE,
    RELIC_CLAIM,
    DAILY_CHECKIN,
    DAILY_CHECKIN_BOOST_SET,
    DAILY_TREASURE,
    DAILY_NEW,
    ENTER_LEAGUE,
    LEAGUE_RESULTS,
    LEAGUE_ENDED_RESTART,
    ANTI_BOT,
    CONNECTION_LOST,
    INACTIVE,
    PREVIOUS_RANK_RESULTS,
    TOO_MANY_TREASURES,
    OVERTAKE_BREAK_SCORE,
    PARTY_RUN,
    PARTY_RUN_SEASON_REWARDS,
    TIME_DEAL,
    PREVIOUS_RESULTS,
    GAME_SETTINGS,
    IN_GAME,
}

class BotStateMachine {
    var current: BotStage = BotStage.MAINMENU
        private set

    fun reset() {
        current = BotStage.MAINMENU
    }

    fun afterMainMenuAction() {
        if (current == BotStage.MAINMENU) current = BotStage.PURCHASE_ITEM
    }

    fun afterPurchaseAction() {
        if (current == BotStage.PURCHASE_ITEM) current = BotStage.GAME_START
    }

    fun enterInGame() {
        current = BotStage.IN_GAME
    }

    fun transitionTo(stage: BotStage) {
        current = stage
    }
}
