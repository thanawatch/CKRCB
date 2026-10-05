package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.templateScoreMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendLifeSchedulerTest {
    @Test fun disabledOptionHasNoScheduledRun() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(false, 0L)
        assertFalse(scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS * 2))
        assertNull(scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS * 2))
    }

    @Test fun firstRunIsDueExactlyAtScheduledIntervalAfterStart() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 1_000L)
        assertFalse(scheduler.observeTime(1_000L + FRIEND_LIFE_INTERVAL_MS - 1L))
        assertTrue(scheduler.observeTime(1_000L + FRIEND_LIFE_INTERVAL_MS))
        assertEquals(FriendLifeTrigger.SCHEDULED, scheduler.beginIfReady(1_000L + FRIEND_LIFE_INTERVAL_MS))
    }

    @Test fun lateCompletionSchedulesFromCompletionWithoutCatchUp() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)
        val trigger = scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS + 4 * 60_000L)!!
        val completedAt = FRIEND_LIFE_INTERVAL_MS + 6 * 60_000L
        scheduler.complete(trigger, completedAt)
        assertEquals(completedAt + FRIEND_LIFE_INTERVAL_MS, scheduler.nextDueAt())
        assertFalse(scheduler.observeTime(completedAt + FRIEND_LIFE_INTERVAL_MS - 1L))
    }

    @Test fun manualTestDoesNotMoveNormalDueTime() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        val originalDue = scheduler.nextDueAt()
        assertEquals(FriendLifeTestRequest.QUEUED, scheduler.requestTest(true))
        assertEquals(FriendLifeTestRequest.ALREADY_QUEUED, scheduler.requestTest(true))
        val trigger = scheduler.beginIfReady(10 * 60_000L)!!
        assertEquals(FriendLifeTrigger.TEST, trigger)
        scheduler.complete(trigger, 12 * 60_000L)
        assertEquals(originalDue, scheduler.nextDueAt())
        assertFalse(scheduler.testPending())
    }

    @Test fun scheduledAndTestPendingShareOneExecution() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)
        scheduler.requestTest(true)
        val trigger = scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS)!!
        assertEquals(FriendLifeTrigger.SCHEDULED_AND_TEST, trigger)
        scheduler.complete(trigger, FRIEND_LIFE_INTERVAL_MS + 1_000L)
        assertFalse(scheduler.scheduledPending())
        assertFalse(scheduler.testPending())
        assertEquals(FRIEND_LIFE_INTERVAL_MS * 2 + 1_000L, scheduler.nextDueAt())
    }

    @Test fun stopStartCreatesFreshSchedule() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)
        scheduler.stop()
        scheduler.start(true, 10_000L)
        assertFalse(scheduler.scheduledPending())
        assertEquals(10_000L + FRIEND_LIFE_INTERVAL_MS, scheduler.nextDueAt())
    }

    @Test fun popupInterruptKeepsScheduledAndManualRequestsPending() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)
        scheduler.requestTest(true)
        assertEquals(FriendLifeTrigger.SCHEDULED_AND_TEST, scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS))
        scheduler.interrupt(FRIEND_LIFE_INTERVAL_MS + 5_000L)
        assertTrue(scheduler.scheduledPending())
        assertTrue(scheduler.testPending())
        assertEquals(
            FriendLifeTrigger.SCHEDULED_AND_TEST,
            scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS + 5_001L),
        )
    }

    @Test fun thirtyMinuteBudgetDoesNotStartBeforeFirstSuccessfulSend() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)
        val startedAt = FRIEND_LIFE_INTERVAL_MS
        assertEquals(FriendLifeTrigger.SCHEDULED, scheduler.beginIfReady(startedAt))

        assertEquals(0L, scheduler.activeElapsedMs(startedAt + 45 * 60_000L))
        assertTrue(scheduler.markFirstSend(startedAt + 45 * 60_000L))
        assertEquals(0L, scheduler.activeElapsedMs(startedAt + 45 * 60_000L))
        assertEquals(60_000L, scheduler.activeElapsedMs(startedAt + 46 * 60_000L))
    }

    @Test fun activeBudgetPausesAcrossPopupAndResumesWithoutCountingPausedTime() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)

        val firstStart = FRIEND_LIFE_INTERVAL_MS
        assertEquals(FriendLifeTrigger.SCHEDULED, scheduler.beginIfReady(firstStart))
        assertEquals(0L, scheduler.activeElapsedMs(firstStart + 10 * 60_000L))

        val firstSendAt = firstStart + 10 * 60_000L
        assertTrue(scheduler.markFirstSend(firstSendAt))
        assertEquals(5 * 60_000L, scheduler.activeElapsedMs(firstSendAt + 5 * 60_000L))
        assertFalse(scheduler.markFirstSend(firstSendAt + 5 * 60_000L))

        val firstInterrupt = firstSendAt + 5 * 60_000L
        scheduler.interrupt(firstInterrupt)
        assertEquals(5 * 60_000L, scheduler.activeElapsedMs(firstInterrupt + 3 * 60_000L))

        val resumedAt = firstInterrupt + 3 * 60_000L
        assertEquals(FriendLifeTrigger.SCHEDULED, scheduler.beginIfReady(resumedAt))
        assertEquals(10 * 60_000L, scheduler.activeElapsedMs(resumedAt + 5 * 60_000L))
        val thirtyMinutesActive = scheduler.activeElapsedMs(resumedAt + 25 * 60_000L)
        assertEquals(FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS, thirtyMinutesActive)
        assertEquals(
            FriendLifeTraversalDecision.ABORT_ABSOLUTE_TIMEOUT,
            friendLifeTraversalDecision(
                sendButtonMatched = false,
                bottomMatched = false,
                elapsedMs = thirtyMinutesActive,
                noProgressMs = 1_000L,
            ),
        )
    }

    @Test fun completionStartsFreshBudgetForNextScheduledCycle() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)

        val startedAt = FRIEND_LIFE_INTERVAL_MS
        val trigger = requireNotNull(scheduler.beginIfReady(startedAt))
        assertEquals(0L, scheduler.activeElapsedMs(startedAt + 10 * 60_000L))
        assertTrue(scheduler.markFirstSend(startedAt + 10 * 60_000L))
        assertEquals(5 * 60_000L, scheduler.activeElapsedMs(startedAt + 15 * 60_000L))
        scheduler.complete(trigger, startedAt + 15 * 60_000L)
        assertEquals(0L, scheduler.activeElapsedMs(startedAt + 20 * 60_000L))

        scheduler.observeTime(scheduler.nextDueAt())
        val nextTrigger = requireNotNull(scheduler.beginIfReady(scheduler.nextDueAt()))
        assertEquals(FriendLifeTrigger.SCHEDULED, nextTrigger)
        assertEquals(0L, scheduler.activeElapsedMs(scheduler.nextDueAt() + 5 * 60_000L))
    }

    @Test fun retryableFailurePreservesAccumulatedActiveBudget() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)

        val startedAt = FRIEND_LIFE_INTERVAL_MS
        assertEquals(FriendLifeTrigger.SCHEDULED, scheduler.beginIfReady(startedAt))
        val firstSendAt = startedAt + 2 * 60_000L
        assertTrue(scheduler.markFirstSend(firstSendAt))
        val failedAt = startedAt + 12 * 60_000L
        scheduler.fail(failedAt, 5 * 60_000L)

        assertEquals(10 * 60_000L, scheduler.activeElapsedMs(failedAt + 4 * 60_000L))
        assertNull(scheduler.beginIfReady(failedAt + 4 * 60_000L))

        val retryAt = failedAt + 5 * 60_000L
        assertEquals(FriendLifeTrigger.SCHEDULED, scheduler.beginIfReady(retryAt))
        assertEquals(13 * 60_000L, scheduler.activeElapsedMs(retryAt + 3 * 60_000L))
        assertFalse(scheduler.markFirstSend(retryAt + 3 * 60_000L))
    }

    @Test fun exhaustedBudgetEndsCurrentCycleWithoutRetry() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)

        val startedAt = FRIEND_LIFE_INTERVAL_MS
        val trigger = requireNotNull(scheduler.beginIfReady(startedAt))
        assertEquals(FriendLifeTrigger.SCHEDULED, trigger)
        assertTrue(scheduler.markFirstSend(startedAt))
        val exhaustedAt = startedAt + FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS
        assertEquals(FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS, scheduler.activeElapsedMs(exhaustedAt))

        scheduler.exhaust(trigger, exhaustedAt)

        assertEquals(0L, scheduler.activeElapsedMs(exhaustedAt + 1L))
        assertFalse(scheduler.scheduledPending())
        assertEquals(exhaustedAt + FRIEND_LIFE_INTERVAL_MS, scheduler.nextDueAt())
        assertFalse(scheduler.hasPendingWork(exhaustedAt + 5 * 60_000L))
        assertFalse(scheduler.observeTime(scheduler.nextDueAt() - 1L))
        assertTrue(scheduler.observeTime(scheduler.nextDueAt()))
        assertEquals(
            FriendLifeTrigger.SCHEDULED,
            scheduler.beginIfReady(scheduler.nextDueAt()),
        )
    }

    @Test fun safetyFailureClearsManualButDelaysScheduledRetry() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)
        scheduler.requestTest(true)
        scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS)
        scheduler.fail(FRIEND_LIFE_INTERVAL_MS, 5 * 60_000L)
        assertEquals(0L, scheduler.activeElapsedMs(FRIEND_LIFE_INTERVAL_MS + 1L))
        assertTrue(scheduler.scheduledPending())
        assertFalse(scheduler.testPending())
        assertNull(scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS + 4 * 60_000L))
        assertEquals(
            FriendLifeTrigger.SCHEDULED,
            scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS + 5 * 60_000L),
        )
    }

    @Test fun testOnlyFailureAfterSendClearsBudgetBeforeNextManualTest() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(false, 0L)
        assertEquals(FriendLifeTestRequest.QUEUED, scheduler.requestTest(true))

        val firstStart = 1_000L
        assertEquals(FriendLifeTrigger.TEST, scheduler.beginIfReady(firstStart))
        assertTrue(scheduler.markFirstSend(firstStart + 2_000L))
        val failedAt = firstStart + 12_000L
        assertEquals(10_000L, scheduler.activeElapsedMs(failedAt))
        scheduler.fail(failedAt, 5 * 60_000L)

        assertEquals(0L, scheduler.activeElapsedMs(failedAt + 60_000L))
        assertFalse(scheduler.testPending())
        assertEquals(FriendLifeTestRequest.QUEUED, scheduler.requestTest(true))

        val secondStart = failedAt + 60_000L
        assertEquals(FriendLifeTrigger.TEST, scheduler.beginIfReady(secondStart))
        assertEquals(0L, scheduler.activeElapsedMs(secondStart + 20_000L))
        assertTrue(scheduler.markFirstSend(secondStart + 20_000L))
        assertEquals(0L, scheduler.activeElapsedMs(secondStart + 20_000L))
    }

    @Test fun successfulSendGestureStartsBudgetAndWatchdogProgressTogether() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(false, 0L)
        assertEquals(FriendLifeTestRequest.QUEUED, scheduler.requestTest(true))
        assertEquals(FriendLifeTrigger.TEST, scheduler.beginIfReady(1_000L))

        val update = applyFriendSendTapProgress(
            scheduler = scheduler,
            step = "SEND_BUTTON",
            gestureSucceeded = true,
            nowMs = 5_000L,
        )

        assertEquals(GameProgressReason.FRIEND_SEND_LIFE_TAP, update.gameProgressReason)
        assertTrue(update.budgetStarted)
        assertEquals(0L, scheduler.activeElapsedMs(5_000L))
        assertEquals(1_000L, scheduler.activeElapsedMs(6_000L))
    }

    @Test fun failedOrNonSendGestureDoesNotStartBudgetOrWatchdogProgress() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(false, 0L)
        assertEquals(FriendLifeTestRequest.QUEUED, scheduler.requestTest(true))
        assertEquals(FriendLifeTrigger.TEST, scheduler.beginIfReady(1_000L))

        val failedSend = applyFriendSendTapProgress(
            scheduler = scheduler,
            step = "SEND_BUTTON",
            gestureSucceeded = false,
            nowMs = 5_000L,
        )
        assertNull(failedSend.gameProgressReason)
        assertFalse(failedSend.budgetStarted)
        assertEquals(0L, scheduler.activeElapsedMs(10_000L))

        val confirm = applyFriendSendTapProgress(
            scheduler = scheduler,
            step = "CONFIRM_1",
            gestureSucceeded = true,
            nowMs = 10_000L,
        )
        assertNull(confirm.gameProgressReason)
        assertFalse(confirm.budgetStarted)
        assertEquals(0L, scheduler.activeElapsedMs(20_000L))
    }

    @Test fun traversalDoesNotFinishUntilBottomIsVisible() {
        assertEquals(
            FriendLifeTraversalDecision.SCROLL_DOWN,
            friendLifeTraversalDecision(false, false, 150_000L, 5_000L),
        )
        assertEquals(
            FriendLifeTraversalDecision.COMPLETE,
            friendLifeTraversalDecision(false, true, 150_000L, 30_000L),
        )
        assertEquals(
            FriendLifeTraversalDecision.ABORT_NO_PROGRESS,
            friendLifeTraversalDecision(false, false, 150_000L, 30_000L),
        )
        assertEquals(
            FriendLifeTraversalDecision.SEND,
            friendLifeTraversalDecision(true, false, 150_000L, 30_000L),
        )
    }

    @Test fun finalVisibleSendButtonWinsWhenBottomMarkerIsAlsoVisible() {
        assertEquals(
            FriendLifeTraversalDecision.SEND,
            friendLifeTraversalDecision(
                sendButtonMatched = true,
                bottomMatched = true,
                elapsedMs = 150_000L,
                noProgressMs = 5_000L,
            ),
        )
    }

    @Test fun finalVisibleSendAndBottomStillRespectAbsoluteTimeout() {
        assertEquals(
            FriendLifeTraversalDecision.ABORT_ABSOLUTE_TIMEOUT,
            friendLifeTraversalDecision(
                sendButtonMatched = true,
                bottomMatched = true,
                elapsedMs = FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS,
                noProgressMs = 1_000L,
            ),
        )
    }

    @Test fun longHealthyTraversalDoesNotTimeoutWhileProgressIsRecent() {
        assertEquals(
            FriendLifeTraversalDecision.SCROLL_DOWN,
            friendLifeTraversalDecision(false, false, 5 * 60_000L, 1_000L),
        )
    }

    @Test fun progressResetAllowsTwoTwentyFiveSecondWindows() {
        assertEquals(
            FriendLifeTraversalDecision.SCROLL_DOWN,
            friendLifeTraversalDecision(false, false, 25_000L, 25_000L),
        )
        // A SEND completion resets lastProgressTime, so noProgressMs is 25 seconds again.
        assertEquals(
            FriendLifeTraversalDecision.SCROLL_DOWN,
            friendLifeTraversalDecision(false, false, 50_000L, 25_000L),
        )
    }

    @Test fun actualNoProgressStallAborts() {
        assertEquals(
            FriendLifeTraversalDecision.ABORT_NO_PROGRESS,
            friendLifeTraversalDecision(false, false, 31_000L, 30_000L),
        )
    }

    @Test fun bottomReachedCompletesInsteadOfTimingOut() {
        assertEquals(
            FriendLifeTraversalDecision.COMPLETE,
            friendLifeTraversalDecision(false, true, FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS, 40_000L),
        )
    }

    @Test fun absoluteSafetyTimeoutAbortsAbnormalTraversal() {
        assertEquals(
            FriendLifeTraversalDecision.ABORT_ABSOLUTE_TIMEOUT,
            friendLifeTraversalDecision(false, false, FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS, 1_000L),
        )
    }

    @Test fun sendCannotBypassAbsoluteSafetyTimeout() {
        assertEquals(
            FriendLifeTraversalDecision.ABORT_ABSOLUTE_TIMEOUT,
            friendLifeTraversalDecision(true, false, FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS, 1_000L),
        )
    }

    @Test fun healthyProgressContinuesBeforeThirtyMinuteAbsoluteLimit() {
        assertEquals(
            FriendLifeTraversalDecision.SCROLL_DOWN,
            friendLifeTraversalDecision(false, false, 20L * 60L * 1000L, 1_000L),
        )
        assertEquals(
            FriendLifeTraversalDecision.SEND,
            friendLifeTraversalDecision(true, false, FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS - 1L, 1_000L),
        )
    }

    @Test fun scheduledPendingValidationFailureDoesNotBeginTransaction() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)
        assertTrue(scheduler.hasPendingWork(FRIEND_LIFE_INTERVAL_MS))
        // MAINMENU validation fails, so beginIfReady is deliberately not called.
        assertFalse(scheduler.inProgress())
        assertTrue(scheduler.scheduledPending())
        assertEquals(FriendLifeTrigger.SCHEDULED, scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS + 1L))
    }

    @Test fun manualPendingValidationFailureDoesNotBeginTransaction() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(false, 0L)
        assertEquals(FriendLifeTestRequest.QUEUED, scheduler.requestTest(true))
        assertTrue(scheduler.hasPendingWork(0L))
        assertFalse(scheduler.inProgress())
        assertTrue(scheduler.testPending())
        assertEquals(FriendLifeTrigger.TEST, scheduler.beginIfReady(1L))
    }

    @Test fun combinedPendingValidationFailurePreservesOneCombinedTransaction() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)
        scheduler.requestTest(true)
        assertTrue(scheduler.hasPendingWork(FRIEND_LIFE_INTERVAL_MS))
        assertFalse(scheduler.inProgress())
        assertTrue(scheduler.scheduledPending())
        assertTrue(scheduler.testPending())
        assertEquals(FriendLifeTrigger.SCHEDULED_AND_TEST, scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS + 1L))
        assertNull(scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS + 2L))
    }

    @Test fun relicInterruptPolicyMatchesDetectRelicOption() {
        val always = listOf(BotStage.CONNECTION_LOST, BotStage.INACTIVE)
        val preGame = listOf(
            BotStage.RELIC_COMPLETE,
            BotStage.RELIC_CLAIM,
            BotStage.CONGRATULATIONS,
            BotStage.MAINMENU,
        )
        val relic = setOf(BotStage.RELIC_COMPLETE, BotStage.RELIC_CLAIM)
        val disabled = friendLifeInterruptStages(always, preGame, false, relic)
        assertFalse(BotStage.RELIC_COMPLETE in disabled)
        assertFalse(BotStage.RELIC_CLAIM in disabled)
        assertTrue(BotStage.CONGRATULATIONS in disabled)
        val enabled = friendLifeInterruptStages(always, preGame, true, relic)
        assertTrue(BotStage.RELIC_COMPLETE in enabled)
        assertTrue(BotStage.RELIC_CLAIM in enabled)
    }

    @Test fun closeButtonIsAnAuxiliaryFriendLifeInterruptWithoutChangingStageList() {
        val stages = friendLifeInterruptStages(
            always = listOf(BotStage.CONNECTION_LOST, BotStage.INACTIVE),
            preGame = listOf(BotStage.CONGRATULATIONS, BotStage.MAINMENU),
            detectRelic = false,
            relicStages = emptySet(),
        )
        assertFalse(BotStage.CLOSE_BUTTON in stages)
        assertEquals(
            BotStage.CLOSE_BUTTON,
            friendLifeInterruptState(primary = null, closeButtonMatched = true),
        )
        assertNull(friendLifeInterruptState(primary = null, closeButtonMatched = false))
        assertEquals(
            BotStage.CONNECTION_LOST,
            friendLifeInterruptState(BotStage.CONNECTION_LOST, closeButtonMatched = true),
        )
    }

    @Test fun onlyFrameNewerThanPostGestureBaselineIsAccepted() {
        val duringGestureFrame = 101L
        val postGestureBaseline = 102L
        assertFalse(isGenuinelyPostGestureFrame(duringGestureFrame, postGestureBaseline))
        assertFalse(isGenuinelyPostGestureFrame(postGestureBaseline, postGestureBaseline))
        assertTrue(isGenuinelyPostGestureFrame(103L, postGestureBaseline))
    }

    @Test fun mainMenuWaitCrossingDueBoundaryCreatesExactlyOneScheduledRequest() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        val beforeWait = FRIEND_LIFE_INTERVAL_MS - 2_000L
        val afterWait = FRIEND_LIFE_INTERVAL_MS + 3_000L
        assertFalse(scheduler.observeTime(beforeWait))
        assertFalse(scheduler.hasPendingWork(beforeWait))
        assertTrue(scheduler.observeTime(afterWait))
        assertTrue(scheduler.hasPendingWork(afterWait))
        assertFalse(scheduler.observeTime(afterWait + 1L))
        assertEquals(FriendLifeTrigger.SCHEDULED, scheduler.beginIfReady(afterWait))
        assertNull(scheduler.beginIfReady(afterWait + 1L))
    }

    @Test fun connectionLostAfterSendButtonStopsBeforeBothConfirms() {
        val decision = friendLifeSendDecision(FriendLifeSendStep.SEND_BUTTON, BotStage.CONNECTION_LOST)
        assertEquals(BotStage.CONNECTION_LOST, decision.interrupt)
        assertNull(decision.nextStep)
    }

    @Test fun connectionLostAfterConfirmOneStopsBeforeConfirmTwo() {
        val afterSend = friendLifeSendDecision(FriendLifeSendStep.SEND_BUTTON, null)
        assertEquals(FriendLifeSendStep.CONFIRM_1, afterSend.nextStep)
        val decision = friendLifeSendDecision(FriendLifeSendStep.CONFIRM_1, BotStage.CONNECTION_LOST)
        assertEquals(BotStage.CONNECTION_LOST, decision.interrupt)
        assertNull(decision.nextStep)
    }

    @Test fun popupAfterConfirmOneStopsBeforeConfirmTwo() {
        val decision = friendLifeSendDecision(FriendLifeSendStep.CONFIRM_1, BotStage.CONGRATULATIONS)
        assertEquals(BotStage.CONGRATULATIONS, decision.interrupt)
        assertNull(decision.nextStep)
    }

    @Test fun inactiveAfterSendButtonStopsBeforeConfirmOne() {
        val decision = friendLifeSendDecision(FriendLifeSendStep.SEND_BUTTON, BotStage.INACTIVE)
        assertEquals(BotStage.INACTIVE, decision.interrupt)
        assertNull(decision.nextStep)
    }

    @Test fun uninterruptedSendSequenceAllowsEachNextStep() {
        assertEquals(
            FriendLifeSendStep.CONFIRM_1,
            friendLifeSendDecision(FriendLifeSendStep.SEND_BUTTON, null).nextStep,
        )
        assertEquals(
            FriendLifeSendStep.CONFIRM_2,
            friendLifeSendDecision(FriendLifeSendStep.CONFIRM_1, null).nextStep,
        )
        assertEquals(
            FriendLifeSendStep.COMPLETE,
            friendLifeSendDecision(FriendLifeSendStep.CONFIRM_2, null).nextStep,
        )
    }

    @Test fun friendLifeRegionsContainTheirTemplates() {
        assertTrue(friendLifeRoiContainsTemplate(160, 390, 53, 301))
        assertTrue(friendLifeRoiContainsTemplate(450, 170, 340, 81))
        assertTrue(friendLifeRoiContainsTemplate(240, 440, 111, 69))
    }

    @Test fun friendLifeRegionValidationRejectsSmallerRoi() {
        assertFalse(friendLifeRoiContainsTemplate(52, 301, 53, 301))
        assertFalse(friendLifeRoiContainsTemplate(340, 80, 340, 81))
    }

    @Test fun initialTopMatchCompletesSearchWithoutSwiping() {
        assertEquals(
            FriendLifeTopSearchDecision.TOP_FOUND,
            friendLifeTopSearchDecision(matched = true, elapsedMs = 0),
        )
        assertFalse(friendLifeTopCheckDue(0))
    }

    @Test fun initialTopNoMatchRequiresSwiping() {
        assertEquals(
            FriendLifeTopSearchDecision.SWIPE,
            friendLifeTopSearchDecision(matched = false, elapsedMs = 0),
        )
    }

    @Test fun topIsCheckedAfterEveryFiveSwipes() {
        for (swipeCount in 1..15) {
            assertEquals(swipeCount % 5 == 0, friendLifeTopCheckDue(swipeCount))
        }
    }

    @Test fun topMatchAfterFiveSwipesStopsFurtherSwiping() {
        assertEquals(
            FriendLifeTopSearchDecision.TOP_FOUND,
            friendLifeTopSearchDecision(matched = true, elapsedMs = 12_000),
        )
    }

    @Test fun tenMinuteTopTimeoutContinuesTraversal() {
        assertEquals(
            FriendLifeTopSearchDecision.SWIPE,
            friendLifeTopSearchDecision(matched = false, elapsedMs = 599_999),
        )
        assertEquals(
            FriendLifeTopSearchDecision.CONTINUE_TRAVERSAL,
            friendLifeTopSearchDecision(matched = false, elapsedMs = 600_000),
        )
    }

    @Test fun friendLifeUsesConfiguredThresholdWithoutChangingGlobalThreshold() {
        assertTrue(templateScoreMatches(0.80, FRIEND_LIFE_MATCH_THRESHOLD))
        assertFalse(templateScoreMatches(0.79, FRIEND_LIFE_MATCH_THRESHOLD))
        assertEquals(0.80, FRIEND_LIFE_MATCH_THRESHOLD, 0.0)
        assertEquals(0.80, AutomationConfig.MATCH_THRESHOLD, 0.0)
    }

    @Test fun friendLifeTimingConstantsMatchPolicy() {
        assertEquals(360L, FRIEND_FIND_TOP_SWIPE_DURATION_MS)
        assertEquals(800L, FRIEND_TRAVERSE_SWIPE_DURATION_MS)
        assertEquals(3_500L, FRIEND_GESTURE_TIMEOUT_MS)
        assertEquals(200, FRIEND_SEND_PACING_MIN_MS)
        assertEquals(400, FRIEND_SEND_PACING_MAX_MS)
        assertEquals(150, FRIEND_FINAL_PACING_MIN_MS)
        assertEquals(300, FRIEND_FINAL_PACING_MAX_MS)
        assertEquals(30_000L, FRIEND_LIFE_NO_PROGRESS_TIMEOUT_MS)
        assertEquals(1_800_000L, FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS)
        assertEquals(5, FRIEND_TOP_SWIPES_PER_CHECK)
        assertEquals(2_000, FRIEND_TOP_CHECK_WAIT_MIN_MS)
        assertEquals(2_600, FRIEND_TOP_CHECK_WAIT_MAX_MS)
        assertEquals(600_000L, FRIEND_TOP_SEARCH_TIMEOUT_MS)
        assertEquals(1_000, FRIEND_TRAVERSE_SETTLE_MIN_MS)
        assertEquals(1_600, FRIEND_TRAVERSE_SETTLE_MAX_MS)
        assertEquals(3_660_000L, FRIEND_LIFE_INTERVAL_MS)
    }

    @Test fun dashboardStatusReportsPendingAndRetryWithoutChangingSchedule() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L, friendLifeIntervalMs(7))
        val initially = scheduler.dashboardStatus(60_000L)
        assertEquals(6 * 60_000L, initially.nextDueInMs)
        assertFalse(initially.scheduledPending)
        assertFalse(initially.inProgress)
        assertTrue(scheduler.observeTime(7 * 60_000L))
        val pending = scheduler.dashboardStatus(7 * 60_000L)
        assertTrue(pending.scheduledPending)
        assertEquals(0L, pending.nextDueInMs)
        scheduler.beginIfReady(7 * 60_000L)
        scheduler.fail(7 * 60_000L, 15 * 60_000L)
        val failed = scheduler.dashboardStatus(8 * 60_000L)
        assertEquals(14 * 60_000L, failed.retryInMs)
        assertTrue(failed.scheduledPending)
        assertFalse(failed.inProgress)
    }

    @Test fun configuredMinuteIntervalSetsFirstRunAtExactMinute() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 1_000L, friendLifeIntervalMs(7))
        assertFalse(scheduler.observeTime(7 * 60_000L + 999L))
        assertTrue(scheduler.observeTime(7 * 60_000L + 1_000L))
        assertEquals(FriendLifeTrigger.SCHEDULED, scheduler.beginIfReady(7 * 60_000L + 1_000L))
    }

    @Test fun configuredMinuteIntervalRepeatsFromSuccessfulScheduledCompletion() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L, friendLifeIntervalMs(5))
        assertTrue(scheduler.observeTime(5 * 60_000L))
        val scheduled = requireNotNull(scheduler.beginIfReady(5 * 60_000L))
        scheduler.complete(scheduled, 9 * 60_000L)
        assertEquals(14 * 60_000L, scheduler.nextDueAt())
        assertFalse(scheduler.observeTime(14 * 60_000L - 1L))
        assertTrue(scheduler.observeTime(14 * 60_000L))
    }

    @Test fun restartReplacesPriorScheduleWithNewSelectedMinutes() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L, friendLifeIntervalMs(5))
        scheduler.stop()
        scheduler.start(true, 30_000L, friendLifeIntervalMs(12))
        assertEquals(30_000L + 12 * 60_000L, scheduler.nextDueAt())
        assertFalse(scheduler.scheduledPending())
    }

    @Test fun manualTestKeepsCustomScheduledDueTime() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L, friendLifeIntervalMs(7))
        val due = scheduler.nextDueAt()
        scheduler.requestTest(true)
        scheduler.complete(requireNotNull(scheduler.beginIfReady(60_000L)), 90_000L)
        assertEquals(due, scheduler.nextDueAt())
    }

    @Test fun preStartRecheckCapturesScheduleThatBecameDueDuringManualWork() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L, friendLifeIntervalMs(1))
        scheduler.requestTest(true)
        val manual = requireNotNull(scheduler.beginIfReady(30_000L))
        assertEquals(FriendLifeTrigger.TEST, manual)
        scheduler.complete(manual, 70_000L)
        assertTrue(scheduler.observeTime(70_000L))
        assertTrue(scheduler.hasPendingWork(70_000L))
        assertEquals(FriendLifeTrigger.SCHEDULED, scheduler.beginIfReady(70_000L))
    }

    @Test fun invalidConfiguredMinuteIntervalIsRejectedWithoutChangingExistingSchedule() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L, friendLifeIntervalMs(7))
        try {
            scheduler.start(true, 60_000L, 0L)
            org.junit.Assert.fail("Expected invalid interval to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(7 * 60_000L, scheduler.nextDueAt())
        }
        assertEquals(60_000L, friendLifeIntervalMs(1))
        assertEquals(1440 * 60_000L, friendLifeIntervalMs(1440))
    }

}
