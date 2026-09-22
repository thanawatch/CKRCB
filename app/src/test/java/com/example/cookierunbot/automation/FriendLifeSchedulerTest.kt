package com.example.cookierunbot.automation

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

    @Test fun firstRunIsDueExactlyOneHourAfterStart() {
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
            friendLifeTraversalDecision(false, false, 1, 30),
        )
        assertEquals(
            FriendLifeTraversalDecision.COMPLETE,
            friendLifeTraversalDecision(false, true, 1, 30),
        )
        assertEquals(
            FriendLifeTraversalDecision.ABORT_SCROLL_LIMIT,
            friendLifeTraversalDecision(false, false, 30, 30),
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
        assertTrue(friendLifeRoiContainsTemplate(100, 350, 53, 301))
        assertTrue(friendLifeRoiContainsTemplate(390, 130, 340, 81))
        assertTrue(friendLifeRoiContainsTemplate(180, 400, 111, 69))
    }

    @Test fun friendLifeRegionValidationRejectsSmallerRoi() {
        assertFalse(friendLifeRoiContainsTemplate(52, 301, 53, 301))
        assertFalse(friendLifeRoiContainsTemplate(340, 80, 340, 81))
    }

}
