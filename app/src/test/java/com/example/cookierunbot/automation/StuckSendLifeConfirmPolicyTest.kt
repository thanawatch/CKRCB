package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckSendLifeConfirmPolicyTest {
    @Test fun onlyPreAndPostGameAreEligible() {
        assertTrue(StuckSendLifeConfirmPolicy.eligibleGroup(DetectionGroup.PRE_GAME))
        assertTrue(StuckSendLifeConfirmPolicy.eligibleGroup(DetectionGroup.POST_GAME))
        assertFalse(StuckSendLifeConfirmPolicy.eligibleGroup(DetectionGroup.IN_GAME))
    }

    @Test fun requiresHighConfidenceForFullMinute() {
        val tracker = StuckSendLifeConfirmTracker()

        val first = tracker.observe(DetectionGroup.POST_GAME, 1_000L, 0.99)
        assertTrue(first.startedNewEpisode)
        assertEquals(0L, first.elapsedMs)
        assertFalse(first.shouldTap)

        var now = 2_500L
        var latest = first
        while (now < 61_000L) {
            latest = tracker.observe(DetectionGroup.POST_GAME, now, 0.99)
            assertFalse(latest.shouldTap)
            now += 1_500L
        }
        assertTrue(tracker.observe(DetectionGroup.POST_GAME, 61_000L, 0.99).shouldTap)
    }

    @Test fun oneMissResetsTheMinute() {
        val tracker = StuckSendLifeConfirmTracker()
        tracker.observe(DetectionGroup.PRE_GAME, 10_000L, 0.99)
        var now = 11_500L
        while (now < 40_000L) {
            tracker.observe(DetectionGroup.PRE_GAME, now, 0.99)
            now += 1_500L
        }

        val miss = tracker.observe(DetectionGroup.PRE_GAME, 40_000L, 0.94)
        assertTrue(miss.disappearedReset)
        assertFalse(miss.shouldTap)

        val restarted = tracker.observe(DetectionGroup.PRE_GAME, 41_000L, 0.99)
        assertTrue(restarted.startedNewEpisode)
        assertEquals(0L, restarted.elapsedMs)
        assertFalse(restarted.shouldTap)

        now = 42_500L
        while (now < 101_000L) {
            assertFalse(tracker.observe(DetectionGroup.PRE_GAME, now, 0.99).shouldTap)
            now += 1_500L
        }
        assertTrue(tracker.observe(DetectionGroup.PRE_GAME, 101_000L, 0.99).shouldTap)
    }

    @Test fun longObservationGapKeepsTimerWhenNextProbeStillMatches() {
        val tracker = StuckSendLifeConfirmTracker()
        tracker.observe(DetectionGroup.POST_GAME, 1_000L, 0.99)

        val afterWorkflowGap = tracker.observe(
            DetectionGroup.POST_GAME,
            61_000L,
            0.99,
        )
        assertFalse(afterWorkflowGap.continuityReset)
        assertFalse(afterWorkflowGap.startedNewEpisode)
        assertEquals(60_000L, afterWorkflowGap.elapsedMs)
        assertTrue(afterWorkflowGap.shouldTap)
    }

    @Test fun inGameObservationClearsAnExistingEpisode() {
        val tracker = StuckSendLifeConfirmTracker()
        tracker.observe(DetectionGroup.POST_GAME, 1_000L, 0.99)

        val inGame = tracker.observe(DetectionGroup.IN_GAME, 2_000L, 0.99)
        assertTrue(inGame.disappearedReset)
        assertFalse(inGame.shouldTap)

        val restarted = tracker.observe(DetectionGroup.POST_GAME, 3_000L, 0.99)
        assertTrue(restarted.startedNewEpisode)
        assertEquals(0L, restarted.elapsedMs)
    }


    @Test fun changingEligibleGroupKeepsTheSameMinute() {
        val tracker = StuckSendLifeConfirmTracker()
        tracker.observe(DetectionGroup.PRE_GAME, 1_000L, 0.99)
        tracker.observe(DetectionGroup.PRE_GAME, 31_000L, 0.99)

        val changed = tracker.observe(DetectionGroup.POST_GAME, 32_000L, 0.99)
        assertFalse(changed.startedNewEpisode)
        assertFalse(changed.continuityReset)
        assertFalse(changed.groupChangedReset)
        assertEquals(31_000L, changed.elapsedMs)
        assertFalse(changed.shouldTap)

        val due = tracker.observe(DetectionGroup.POST_GAME, 61_000L, 0.99)
        assertTrue(due.shouldTap)
    }

    @Test fun explicitResetStartsANewMinute() {
        val tracker = StuckSendLifeConfirmTracker()
        tracker.observe(DetectionGroup.POST_GAME, 1_000L, 0.99)
        assertTrue(tracker.reset())

        val restarted = tracker.observe(DetectionGroup.POST_GAME, 2_000L, 0.99)
        assertTrue(restarted.startedNewEpisode)
        assertEquals(0L, restarted.elapsedMs)
        assertFalse(restarted.shouldTap)
    }

    @Test fun centerUsesExactTemplateMatchCenter() {
        assertEquals(LogicalPoint(797, 458), StuckSendLifeConfirmPolicy.center(640, 402, 314, 113))
    }
}
