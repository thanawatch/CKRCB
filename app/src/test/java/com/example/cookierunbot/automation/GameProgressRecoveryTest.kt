package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.TemplateMatch
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProgressRecoveryTest {
    @Test fun confirmMatchThresholdIsIndependentAndAcceptsSampleScore() {
        assertEquals(0.78, GameProgressRecovery.MATCH_THRESHOLD, 0.0)
        assertTrue(com.example.cookierunbot.detection.templateScoreMatches(
            0.794015, GameProgressRecovery.MATCH_THRESHOLD,
        ))
        assertTrue(GameProgressRecovery.MATCH_THRESHOLD < 0.8)
    }

    @Test fun actualTemplateMatchDeterminesTapCenter() {
        assertEquals(
            LogicalPoint(740, 452),
            GameProgressRecovery.centerIfMatched(TemplateMatch(0.95, true, 640, 402, 200, 100)),
        )
    }

    @Test fun noMatchNeverProducesTapTarget() {
        assertNull(GameProgressRecovery.centerIfMatched(TemplateMatch(0.1, false, 640, 402, 200, 100)))
    }

    @Test fun confirmRepeatsAfterSuccessfulFirstAndSecondMatches() {
        assertEquals(ConfirmRecoveryDecision.TAP_AND_CONTINUE, GameProgressRecovery.decision(1, true))
        assertEquals(ConfirmRecoveryDecision.TAP_AND_CONTINUE, GameProgressRecovery.decision(2, true))
        assertEquals(3, GameProgressRecovery.MAX_ATTEMPTS)
        assertEquals(2_000L, GameProgressRecovery.RETRY_DELAY_MS)
    }

    @Test fun confirmStopsScanningOnFirstMissingMatchSoCloseCanRunNext() {
        assertEquals(ConfirmRecoveryDecision.STOP_NO_MATCH, GameProgressRecovery.decision(1, false))
        assertEquals(ConfirmRecoveryDecision.STOP_NO_MATCH, GameProgressRecovery.decision(2, false))
        assertEquals(ConfirmRecoveryDecision.STOP_NO_MATCH, GameProgressRecovery.decision(3, false))
    }

    @Test fun thirdConfirmedMatchIsLastConfirmTapThenCloseRunsNext() {
        assertEquals(ConfirmRecoveryDecision.TAP_AND_STOP, GameProgressRecovery.decision(3, true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun confirmNeverAllowsFourthAttempt() {
        GameProgressRecovery.decision(4, true)
    }

    @Test fun circularLogicalJitterNeverExceedsRadiusFive() {
        val center = LogicalPoint(740, 452)
        val random = Random(1234)
        repeat(10_000) {
            val point = GameProgressRecovery.pointInCircle(center, random)
            assertTrue(hypot((point.x - center.x).toDouble(), (point.y - center.y).toDouble()) <= 5.0)
        }
    }
}
