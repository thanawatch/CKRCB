package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotDetectionFlowPolicyTest {
    @Test fun confirmedGameCompleteCommitsPostGameBeforeHandlerCanReturn() {
        var group = DetectionGroup.IN_GAME
        val events = mutableListOf<String>()
        runConfirmedGameComplete(
            recordProgress = { events += "WATCHDOG_RESET" },
            commitGroup = { group = it; events += "POST_GAME" },
            handleResult = { events += "HANDLER_RETURNED_EARLY" },
        )
        assertEquals(DetectionGroup.POST_GAME, group)
        assertEquals(listOf("WATCHDOG_RESET", "POST_GAME", "HANDLER_RETURNED_EARLY"), events)
    }

    @Test fun gameCompleteHandlerFailureDoesNotRestoreInGame() {
        var group = DetectionGroup.IN_GAME
        try {
            runConfirmedGameComplete(
                recordProgress = {},
                commitGroup = { group = it },
                handleResult = { error("finish action rejected") },
            )
        } catch (_: IllegalStateException) {
            // Result handling can fail after the confirmed state transition.
        }
        assertEquals(DetectionGroup.POST_GAME, group)
    }

    @Test fun postGameRewardPopupsAreOrderedBeforeMainMenu() {
        val preGame = listOf(
            BotStage.LEVEL_UP, BotStage.RELIC_COMPLETE, BotStage.DAILY_CHECKIN,
            BotStage.MAINMENU, BotStage.PURCHASE_ITEM,
        )
        val rewards = setOf(BotStage.LEVEL_UP, BotStage.RELIC_COMPLETE, BotStage.DAILY_CHECKIN)
        val stages = postGameStages(preGame, rewards)
        assertEquals(
            listOf(
                BotStage.ANTI_BOT, BotStage.OVERTAKE_BREAK_SCORE, BotStage.MYSTERY_BOX,
                BotStage.LEVEL_UP, BotStage.RELIC_COMPLETE, BotStage.DAILY_CHECKIN,
                BotStage.MAINMENU,
            ),
            stages,
        )
        assertFalse(stages.contains(BotStage.PURCHASE_ITEM))
        assertTrue(stages.indexOf(BotStage.LEVEL_UP) < stages.indexOf(BotStage.MAINMENU))
    }

    @Test fun rewardPopupResumesItsPreOrPostGameGroup() {
        assertEquals(DetectionGroup.PRE_GAME, rewardPopupResumeGroup(DetectionGroup.PRE_GAME))
        assertEquals(DetectionGroup.POST_GAME, rewardPopupResumeGroup(DetectionGroup.POST_GAME))
        assertEquals(DetectionGroup.PRE_GAME, rewardPopupResumeGroup(DetectionGroup.IN_GAME))
    }

    @Test fun postGameRelicCandidatesStillHonorDetectRelicOption() {
        val stages = postGameStages(
            listOf(BotStage.RELIC_COMPLETE, BotStage.RELIC_CLAIM, BotStage.LEVEL_UP),
            setOf(BotStage.RELIC_COMPLETE, BotStage.RELIC_CLAIM, BotStage.LEVEL_UP),
        )
        val relics = setOf(BotStage.RELIC_COMPLETE, BotStage.RELIC_CLAIM)
        assertFalse(filterRelicStages(stages, false, relics).contains(BotStage.RELIC_COMPLETE))
        assertFalse(filterRelicStages(stages, false, relics).contains(BotStage.RELIC_CLAIM))
        assertTrue(filterRelicStages(stages, true, relics).containsAll(relics))
    }

    @Test fun watchdogMainMenuMatchRepairsOnlyTheDetectionGroup() {
        assertEquals(
            DetectionGroup.PRE_GAME,
            groupAfterRecoveryMainMenu(DetectionGroup.IN_GAME, true),
        )
        assertEquals(
            DetectionGroup.IN_GAME,
            groupAfterRecoveryMainMenu(DetectionGroup.IN_GAME, false),
        )
        assertEquals(
            DetectionGroup.POST_GAME,
            groupAfterRecoveryMainMenu(DetectionGroup.POST_GAME, false),
        )
    }

    @Test fun mainMenuRecoveryCanMatchOnSecondFreshCheck() {
        assertEquals(5_000L, PROGRESS_RECOVERY_MAINMENU_TIMEOUT_MS)
        assertEquals(
            MainMenuRecoveryDecision.ROUTE_PRE_GAME,
            mainMenuRecoveryDecision(matched = true, elapsedMs = 0L),
        )
        assertEquals(
            MainMenuRecoveryDecision.WAIT_FOR_FRESH_FRAME,
            mainMenuRecoveryDecision(matched = false, elapsedMs = 0L),
        )
        assertEquals(
            MainMenuRecoveryDecision.ROUTE_PRE_GAME,
            mainMenuRecoveryDecision(matched = true, elapsedMs = 1_500L),
        )
        assertEquals(DetectionGroup.PRE_GAME, groupAfterRecoveryMainMenu(DetectionGroup.IN_GAME, true))
    }

    @Test fun mainMenuRecoveryTimeoutKeepsTheOriginalGroup() {
        assertEquals(
            MainMenuRecoveryDecision.WAIT_FOR_FRESH_FRAME,
            mainMenuRecoveryDecision(matched = false, elapsedMs = 4_999L),
        )
        assertEquals(
            MainMenuRecoveryDecision.KEEP_GROUP,
            mainMenuRecoveryDecision(matched = false, elapsedMs = 5_000L),
        )
        assertEquals(
            MainMenuRecoveryDecision.KEEP_GROUP,
            mainMenuRecoveryDecision(matched = true, elapsedMs = 5_000L),
        )
        assertEquals(DetectionGroup.IN_GAME, groupAfterRecoveryMainMenu(DetectionGroup.IN_GAME, false))
        assertEquals(DetectionGroup.POST_GAME, groupAfterRecoveryMainMenu(DetectionGroup.POST_GAME, false))
    }

    @Test fun gameCompleteFinishRequiresFreshPositiveMatch() {
        assertTrue(gameCompleteFinishAllowed(freshFrameAvailable = true, gameCompleteMatched = true))
        assertFalse(gameCompleteFinishAllowed(freshFrameAvailable = true, gameCompleteMatched = false))
        assertFalse(gameCompleteFinishAllowed(freshFrameAvailable = false, gameCompleteMatched = true))
        assertEquals(DetectionGroup.POST_GAME, groupAfterGameComplete())
    }
}
