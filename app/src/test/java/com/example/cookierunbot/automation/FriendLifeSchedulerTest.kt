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
        scheduler.interrupt()
        assertTrue(scheduler.scheduledPending())
        assertTrue(scheduler.testPending())
        assertEquals(FriendLifeTrigger.SCHEDULED_AND_TEST, scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS + 1L))
    }

    @Test fun safetyFailureClearsManualButDelaysScheduledRetry() {
        val scheduler = FriendLifeScheduler()
        scheduler.start(true, 0L)
        scheduler.observeTime(FRIEND_LIFE_INTERVAL_MS)
        scheduler.requestTest(true)
        scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS)
        scheduler.fail(FRIEND_LIFE_INTERVAL_MS, 15 * 60_000L)
        assertTrue(scheduler.scheduledPending())
        assertFalse(scheduler.testPending())
        assertNull(scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS + 14 * 60_000L))
        assertEquals(
            FriendLifeTrigger.SCHEDULED,
            scheduler.beginIfReady(FRIEND_LIFE_INTERVAL_MS + 15 * 60_000L),
        )
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
            friendLifeTraversalDecision(false, true, 700_000L, 40_000L),
        )
    }

    @Test fun absoluteSafetyTimeoutAbortsAbnormalTraversal() {
        assertEquals(
            FriendLifeTraversalDecision.ABORT_ABSOLUTE_TIMEOUT,
            friendLifeTraversalDecision(false, false, FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS, 1_000L),
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

    @Test fun topRoundMatchedCompletesSearch() {
        assertEquals(
            FriendLifeTopRoundDecision.COMPLETE,
            friendLifeTopRoundDecision(matched = true, round = 1),
        )
    }

    @Test fun firstFailedTopRoundStartsSecondRound() {
        assertEquals(
            FriendLifeTopRoundDecision.NEXT_ROUND,
            friendLifeTopRoundDecision(matched = false, round = 1),
        )
    }

    @Test fun twoFailedTopRoundsContinueToTraversalInsteadOfAborting() {
        assertEquals(
            FriendLifeTopRoundDecision.CONTINUE_TRAVERSAL,
            friendLifeTopRoundDecision(matched = false, round = 2),
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
        assertEquals(30_000L, FRIEND_LIFE_NO_PROGRESS_TIMEOUT_MS)
        assertEquals(600_000L, FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS)
        assertEquals(60_000L, FRIEND_TOP_SCROLL_ROUND_MS)
        assertEquals(2_800L, FRIEND_TOP_CHECK_SETTLE_MS)
        assertEquals(2, FRIEND_TOP_SEARCH_ROUNDS)
        assertEquals(1_000, FRIEND_TRAVERSE_SETTLE_MIN_MS)
        assertEquals(1_600, FRIEND_TRAVERSE_SETTLE_MAX_MS)
        assertEquals(3_660_000L, FRIEND_LIFE_INTERVAL_MS)
    }

}
