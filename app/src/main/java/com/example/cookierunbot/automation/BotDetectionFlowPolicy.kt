package com.example.cookierunbot.automation

/** Keep post-game reward priority aligned with the existing PRE_GAME popup order. */
internal fun postGameStages(
    preGameStages: List<BotStage>,
    rewardPopups: Set<BotStage>,
): List<BotStage> = listOf(
    BotStage.ANTI_BOT,
    BotStage.OVERTAKE_BREAK_SCORE,
    BotStage.MYSTERY_BOX,
) + preGameStages.filter { it in rewardPopups } + BotStage.MAINMENU

internal fun filterRelicStages(
    stages: List<BotStage>,
    detectRelic: Boolean,
    relicStages: Set<BotStage>,
): List<BotStage> = stages.filterNot { !detectRelic && it in relicStages }

internal fun groupAfterGameComplete(): DetectionGroup = DetectionGroup.POST_GAME

/** The group is committed before result handling, including any early handler exit. */
internal inline fun runConfirmedGameComplete(
    recordProgress: () -> Unit,
    commitGroup: (DetectionGroup) -> Unit,
    handleResult: () -> Unit,
) {
    recordProgress()
    commitGroup(groupAfterGameComplete())
    handleResult()
}

/** A full-stage recovery match from IN_GAME retains the previous PRE_GAME popup behavior. */
internal fun rewardPopupResumeGroup(detectedGroup: DetectionGroup): DetectionGroup =
    if (detectedGroup == DetectionGroup.POST_GAME) DetectionGroup.POST_GAME else DetectionGroup.PRE_GAME

internal fun groupAfterRecoveryMainMenu(
    previousGroup: DetectionGroup,
    mainMenuMatched: Boolean,
): DetectionGroup = if (mainMenuMatched) DetectionGroup.PRE_GAME else previousGroup

internal const val PROGRESS_RECOVERY_MAINMENU_TIMEOUT_MS = 120_000L

internal enum class MainMenuRecoveryDecision { ROUTE_PRE_GAME, WAIT_FOR_FRESH_FRAME, KEEP_GROUP }

internal fun mainMenuRecoveryDecision(matched: Boolean, elapsedMs: Long): MainMenuRecoveryDecision = when {
    matched -> MainMenuRecoveryDecision.ROUTE_PRE_GAME
    elapsedMs >= PROGRESS_RECOVERY_MAINMENU_TIMEOUT_MS -> MainMenuRecoveryDecision.KEEP_GROUP
    else -> MainMenuRecoveryDecision.WAIT_FOR_FRESH_FRAME
}

internal fun gameCompleteFinishAllowed(freshFrameAvailable: Boolean, gameCompleteMatched: Boolean): Boolean =
    freshFrameAvailable && gameCompleteMatched
