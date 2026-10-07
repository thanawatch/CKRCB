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

/** Preserve the first confirmed GAME_COMPLETE time so later re-detections cannot extend round duration. */
internal fun firstGameCompleteElapsedRealtime(existing: Long, detectedAt: Long): Long =
    if (existing > 0L) existing else detectedAt

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

/** CLOSE_BUTTON is a normal popup only outside gameplay. */
internal fun closePopupEnabledForGroup(group: DetectionGroup): Boolean =
    group == DetectionGroup.PRE_GAME || group == DetectionGroup.POST_GAME

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

internal fun fixedIntervalProbeDue(
    nowMs: Long,
    lastProbeAtMs: Long,
    intervalMs: Long,
): Boolean {
    require(intervalMs > 0L)
    return lastProbeAtMs == 0L || nowMs - lastProbeAtMs >= intervalMs
}

internal class GameCompleteResultStabilityTracker(
    private val requiredConsecutiveMatches: Int = 2,
) {
    init {
        require(requiredConsecutiveMatches >= 2)
    }

    private var consecutiveMatches = 0

    fun reset() {
        consecutiveMatches = 0
    }

    fun observe(matched: Boolean): Boolean {
        if (!matched) {
            consecutiveMatches = 0
            return false
        }
        consecutiveMatches += 1
        return consecutiveMatches >= requiredConsecutiveMatches
    }
}

internal class PostGameInterruptTracker(
    private val requiredConsecutiveMatches: Int = 2,
) {
    init {
        require(requiredConsecutiveMatches >= 2)
    }

    private var pendingStage: BotStage? = null
    private var consecutiveMatches = 0

    fun reset() {
        pendingStage = null
        consecutiveMatches = 0
    }

    fun observe(stage: BotStage?): BotStage? {
        if (stage == null) {
            reset()
            return null
        }
        if (stage != pendingStage) {
            pendingStage = stage
            consecutiveMatches = 1
            return null
        }
        consecutiveMatches += 1
        return stage.takeIf { consecutiveMatches >= requiredConsecutiveMatches }
    }
}

