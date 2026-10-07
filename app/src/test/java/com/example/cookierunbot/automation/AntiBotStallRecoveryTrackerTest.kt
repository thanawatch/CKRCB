package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiBotStallRecoveryTrackerTest {
    @Test
    fun recoveryBecomesDueAfterSixtySecondsOfContinuousMatches() {
        val tracker = AntiBotStallRecoveryTracker(stallTimeoutMs = 60_000L, maxMatchGapMs = 2_000L)

        var status = tracker.observeMatched(1_000L)
        for (now in 2_000L..61_000L step 1_000L) {
            status = tracker.observeMatched(now)
        }

        assertEquals(60_000L, status.idleMs)
        assertTrue(status.recoveryDue)
    }

    @Test
    fun successfulTapOrAcceptedLayoutRestartsTheStallClock() {
        val tracker = AntiBotStallRecoveryTracker(stallTimeoutMs = 60_000L, maxMatchGapMs = 2_000L)

        for (now in 1_000L..60_000L step 1_000L) tracker.observeMatched(now)
        tracker.recordProgress(60_500L)
        val afterProgress = tracker.observeMatched(61_000L)

        assertEquals(500L, afterProgress.idleMs)
        assertFalse(afterProgress.recoveryDue)
    }

    @Test
    fun intermittentAntiBotMatchRestartsWindowBeforeRecovery() {
        val tracker = AntiBotStallRecoveryTracker(stallTimeoutMs = 60_000L, maxMatchGapMs = 2_000L)

        tracker.observeMatched(1_000L)
        tracker.observeMatched(2_000L)
        val restarted = tracker.observeMatched(5_500L)

        assertEquals(0L, restarted.idleMs)
        assertFalse(restarted.recoveryDue)
    }

    @Test
    fun consumedRecoveryDoesNotFireTwiceInSameWindow() {
        val tracker = AntiBotStallRecoveryTracker(stallTimeoutMs = 60_000L, maxMatchGapMs = 2_000L)

        for (now in 1_000L..61_000L step 1_000L) tracker.observeMatched(now)
        assertTrue(tracker.tryConsumeRecovery(61_000L))
        assertFalse(tracker.tryConsumeRecovery(61_500L))
        assertTrue(tracker.status(61_500L).recoveryConsumed)
    }

    @Test
    fun failedEmergencyAttemptStartsAnotherFullSixtySecondWindow() {
        val tracker = AntiBotStallRecoveryTracker(stallTimeoutMs = 60_000L, maxMatchGapMs = 2_000L)

        for (now in 1_000L..61_000L step 1_000L) tracker.observeMatched(now)
        assertTrue(tracker.tryConsumeRecovery(61_000L))
        tracker.recordFailedRecoveryAttempt(62_000L)

        for (now in 63_000L..121_000L step 1_000L) tracker.observeMatched(now)
        assertFalse(tracker.status(121_000L).recoveryDue)
        tracker.observeMatched(122_000L)
        assertTrue(tracker.tryConsumeRecovery(122_000L))
    }

    @Test
    fun incompleteTestRetriesOnlyCardsWithoutSuccessfulCallbacks() {
        assertEquals(
            listOf(6),
            antiBotIncompleteRetryCards(
                testIndexInChallenge = 1,
                selectedCards = listOf(3, 6),
                successfulCards = listOf(3),
            ),
        )
        assertEquals(
            emptyList<Int>(),
            antiBotIncompleteRetryCards(
                testIndexInChallenge = 2,
                selectedCards = listOf(3, 6),
                successfulCards = listOf(3, 6),
            ),
        )
        assertEquals(
            emptyList<Int>(),
            antiBotIncompleteRetryCards(
                testIndexInChallenge = 3,
                selectedCards = listOf(3, 6),
                successfulCards = emptyList(),
            ),
        )
    }
}
