package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotDetectionFlowPolicyTest {
    @Test fun firstGameCompleteTimestampWinsAcrossRepeatedDetections() {
        assertEquals(12_345L, firstGameCompleteElapsedRealtime(0L, 12_345L))
        assertEquals(12_345L, firstGameCompleteElapsedRealtime(12_345L, 20_000L))
    }

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

    @Test fun closePopupIsEnabledOnlyOutsideInGame() {
        assertTrue(closePopupEnabledForGroup(DetectionGroup.PRE_GAME))
        assertTrue(closePopupEnabledForGroup(DetectionGroup.POST_GAME))
        assertFalse(closePopupEnabledForGroup(DetectionGroup.IN_GAME))
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
        assertEquals(120_000L, PROGRESS_RECOVERY_MAINMENU_TIMEOUT_MS)
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
        assertEquals(
            MainMenuRecoveryDecision.ROUTE_PRE_GAME,
            mainMenuRecoveryDecision(matched = true, elapsedMs = 119_999L),
        )
        assertEquals(DetectionGroup.PRE_GAME, groupAfterRecoveryMainMenu(DetectionGroup.IN_GAME, true))
    }

    @Test fun mainMenuRecoveryTimeoutKeepsTheOriginalGroup() {
        assertEquals(
            MainMenuRecoveryDecision.WAIT_FOR_FRESH_FRAME,
            mainMenuRecoveryDecision(matched = false, elapsedMs = 119_999L),
        )
        assertEquals(
            MainMenuRecoveryDecision.KEEP_GROUP,
            mainMenuRecoveryDecision(matched = false, elapsedMs = 120_000L),
        )
        assertEquals(
            MainMenuRecoveryDecision.ROUTE_PRE_GAME,
            mainMenuRecoveryDecision(matched = true, elapsedMs = 120_000L),
        )
        assertEquals(DetectionGroup.IN_GAME, groupAfterRecoveryMainMenu(DetectionGroup.IN_GAME, false))
        assertEquals(DetectionGroup.POST_GAME, groupAfterRecoveryMainMenu(DetectionGroup.POST_GAME, false))
    }

    @Test fun gameCompleteResultProbeUsesRealFixedInterval() {
        assertTrue(
            fixedIntervalProbeDue(
                nowMs = 1_000L,
                lastProbeAtMs = 0L,
                intervalMs = 500L,
            ),
        )
        assertFalse(
            fixedIntervalProbeDue(
                nowMs = 1_499L,
                lastProbeAtMs = 1_000L,
                intervalMs = 500L,
            ),
        )
        assertTrue(
            fixedIntervalProbeDue(
                nowMs = 1_500L,
                lastProbeAtMs = 1_000L,
                intervalMs = 500L,
            ),
        )
    }

    @Test fun gameCompleteResultRequiresTwoConsecutiveFreshMatches() {
        val tracker = GameCompleteResultStabilityTracker(requiredConsecutiveMatches = 2)

        assertFalse(tracker.observe(matched = true))
        assertTrue(tracker.observe(matched = true))
    }

    @Test fun gameCompleteResultStabilityResetsOnAnyMiss() {
        val tracker = GameCompleteResultStabilityTracker(requiredConsecutiveMatches = 2)

        assertFalse(tracker.observe(matched = true))
        assertFalse(tracker.observe(matched = false))
        assertFalse(tracker.observe(matched = true))
        assertTrue(tracker.observe(matched = true))
    }

    @Test fun postGameInterruptRequiresSameStageTwice() {
        val tracker = PostGameInterruptTracker(requiredConsecutiveMatches = 2)

        assertEquals(null, tracker.observe(BotStage.MYSTERY_BOX))
        assertEquals(BotStage.MYSTERY_BOX, tracker.observe(BotStage.MYSTERY_BOX))
    }

    @Test fun postGameInterruptDoesNotConfirmAcrossDifferentStates() {
        val tracker = PostGameInterruptTracker(requiredConsecutiveMatches = 2)

        assertEquals(null, tracker.observe(BotStage.MYSTERY_BOX))
        assertEquals(null, tracker.observe(BotStage.MAINMENU))
        assertEquals(BotStage.MAINMENU, tracker.observe(BotStage.MAINMENU))
        assertEquals(null, tracker.observe(null))
    }

    @Test fun gameCompleteFinishRequiresFreshPositiveMatch() {
        assertTrue(gameCompleteFinishAllowed(freshFrameAvailable = true, gameCompleteMatched = true))
        assertFalse(gameCompleteFinishAllowed(freshFrameAvailable = true, gameCompleteMatched = false))
        assertFalse(gameCompleteFinishAllowed(freshFrameAvailable = false, gameCompleteMatched = true))
        assertEquals(DetectionGroup.POST_GAME, groupAfterGameComplete())
    }
}
