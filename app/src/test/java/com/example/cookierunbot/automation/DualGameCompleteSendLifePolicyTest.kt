package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualGameCompleteSendLifePolicyTest {
    @Test fun requiresBothMatchesFromTheSameFrame() {
        assertFalse(DualGameCompleteSendLifePolicy.matched(false, false))
        assertFalse(DualGameCompleteSendLifePolicy.matched(true, false))
        assertFalse(DualGameCompleteSendLifePolicy.matched(false, true))
        assertTrue(DualGameCompleteSendLifePolicy.matched(true, true))
    }

    @Test fun oldGameCompleteAlwaysTriggersDualProbeBeforeLegacyHandler() {
        val now = 5_000L
        assertTrue(DualGameCompleteSendLifePolicy.shouldProbe(DetectionGroup.PRE_GAME, now, now - 1L, true, true, false))
        assertFalse(DualGameCompleteSendLifePolicy.shouldProbe(DetectionGroup.PRE_GAME, now, now - 1L, false, true, false))
        assertFalse(DualGameCompleteSendLifePolicy.shouldProbe(DetectionGroup.PRE_GAME, now, now - 1L, false, false, false))
        assertTrue(DualGameCompleteSendLifePolicy.shouldProbe(DetectionGroup.PRE_GAME, now, now - 1_500L, false, false, false))
        assertTrue(DualGameCompleteSendLifePolicy.shouldProbe(DetectionGroup.PRE_GAME, now, 0L, false, false, false))
        assertFalse(DualGameCompleteSendLifePolicy.shouldProbe(DetectionGroup.PRE_GAME, now, 0L, false, false, true))
        assertTrue(DualGameCompleteSendLifePolicy.shouldProbe(DetectionGroup.PRE_GAME, now, now - 1L, true, true, true))
    }

    @Test fun probeIsRestrictedToNormalPopupGroupsEvenIfOldGameCompleteMatched() {
        assertTrue(DualGameCompleteSendLifePolicy.eligibleGroup(DetectionGroup.PRE_GAME))
        assertTrue(DualGameCompleteSendLifePolicy.eligibleGroup(DetectionGroup.POST_GAME))
        assertFalse(DualGameCompleteSendLifePolicy.eligibleGroup(DetectionGroup.IN_GAME))
        val now = 10_000L
        for (group in listOf(DetectionGroup.PRE_GAME, DetectionGroup.POST_GAME)) {
            assertTrue(DualGameCompleteSendLifePolicy.shouldProbe(
                group, now, 0L, false, false, false,
            ))
            assertTrue(DualGameCompleteSendLifePolicy.shouldProbe(
                group, now, now, true, false, false,
            ))
        }
        assertFalse(DualGameCompleteSendLifePolicy.shouldProbe(
            DetectionGroup.IN_GAME, now, 0L, false, false, false,
        ))
        assertFalse(DualGameCompleteSendLifePolicy.shouldProbe(
            DetectionGroup.IN_GAME, now, now, true, false, false,
        ))
    }

    @Test fun confirmCenterIsExactAndNoRandomJitterIsAdded() {
        assertEquals(LogicalPoint(640, 458),
            DualGameCompleteSendLifePolicy.center(495, 408, 290, 101))
        assertNull(DualGameCompleteSendLifePolicy.center(-1, 408, 290, 101))
        assertNull(DualGameCompleteSendLifePolicy.center(495, 408, 0, 101))
    }

    @Test fun delayAndRetryBudgetAreIndependentFromLegacyGameComplete() {
        assertEquals(1_600L, DualGameCompleteSendLifePolicy.BEFORE_TAP_DELAY_MS)
        assertEquals(0.8, DualGameCompleteSendLifePolicy.MATCH_THRESHOLD, 0.0)
        assertEquals(3, DualGameCompleteSendLifePolicy.MAX_VISIBLE_ATTEMPTS)
    }
}
