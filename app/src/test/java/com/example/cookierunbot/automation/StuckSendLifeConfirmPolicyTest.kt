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

        val middle = tracker.observe(DetectionGroup.POST_GAME, 31_000L, 0.99)
        assertEquals(30_000L, middle.elapsedMs)
        assertFalse(middle.shouldTap)

        val due = tracker.observe(DetectionGroup.POST_GAME, 61_000L, 0.99)
        assertEquals(60_000L, due.elapsedMs)
        assertTrue(due.shouldTap)
    }

    @Test fun oneMissResetsTheMinute() {
        val tracker = StuckSendLifeConfirmTracker()
        tracker.observe(DetectionGroup.PRE_GAME, 10_000L, 0.99)

        val miss = tracker.observe(DetectionGroup.PRE_GAME, 40_000L, 0.94)
        assertTrue(miss.disappearedReset)
        assertFalse(miss.shouldTap)

        val restarted = tracker.observe(DetectionGroup.PRE_GAME, 41_000L, 0.99)
        assertTrue(restarted.startedNewEpisode)
        assertEquals(0L, restarted.elapsedMs)
        assertFalse(restarted.shouldTap)

        assertFalse(tracker.observe(DetectionGroup.PRE_GAME, 100_999L, 0.99).shouldTap)
        assertTrue(tracker.observe(DetectionGroup.PRE_GAME, 101_000L, 0.99).shouldTap)
    }

    @Test fun longObservationGapBreaksContinuity() {
        val tracker = StuckSendLifeConfirmTracker()
        tracker.observe(DetectionGroup.POST_GAME, 1_000L, 0.99)
        tracker.observe(DetectionGroup.POST_GAME, 3_000L, 0.99)

        val afterWorkflowGap = tracker.observe(
            DetectionGroup.POST_GAME,
            3_000L + StuckSendLifeConfirmPolicy.MAX_OBSERVATION_GAP_MS + 1L,
            0.99,
        )
        assertTrue(afterWorkflowGap.continuityReset)
        assertTrue(afterWorkflowGap.startedNewEpisode)
        assertEquals(0L, afterWorkflowGap.elapsedMs)
        assertFalse(afterWorkflowGap.shouldTap)
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

    @Test fun centerUsesExactTemplateMatchCenter() {
        assertEquals(LogicalPoint(797, 458), StuckSendLifeConfirmPolicy.center(640, 402, 314, 113))
    }
}
