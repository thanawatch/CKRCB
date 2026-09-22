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
}
