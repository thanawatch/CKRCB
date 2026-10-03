package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.TemplateMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameProgressCloseRecoveryTest {
    @Test fun closeRecoveryUsesDedicatedThreshold() {
        assertEquals(0.8, GameProgressCloseRecovery.MATCH_THRESHOLD, 0.0)
    }

    @Test fun closeRecoveryChecksOriginalScaleBeforeSmallerAndLargerFallbacks() {
        assertEquals(1.0, GameProgressCloseRecovery.PRIMARY_SCALE, 0.0)
        assertEquals(
            listOf(0.9, 1.1, 0.8, 1.2, 1.3, 1.4),
            GameProgressCloseRecovery.FALLBACK_SCALES,
        )
        assertEquals(
            listOf(1.0, 0.9, 1.1, 0.8, 1.2, 1.3, 1.4),
            GameProgressCloseRecovery.MATCH_SCALES,
        )
    }

    @Test fun fallbackScalesAreAllowedOnlyEveryFiveSeconds() {
        assertEquals(5_000L, GameProgressCloseRecovery.FALLBACK_INTERVAL_MS)
        org.junit.Assert.assertTrue(GameProgressCloseRecovery.fallbackDue(1_000L, 0L))
        org.junit.Assert.assertFalse(GameProgressCloseRecovery.fallbackDue(5_999L, 1_000L))
        org.junit.Assert.assertTrue(GameProgressCloseRecovery.fallbackDue(6_000L, 1_000L))
    }

    @Test fun closeRecoveryWaitsTwoSecondsBetweenAttempts() {
        assertEquals(2_000L, GameProgressCloseRecovery.RETRY_DELAY_MS)
    }

    @Test fun topRightRoiUsesFrameDimensions() {
        val even = GameProgressCloseRecovery.topRightRoi(1280, 720)
        assertEquals(640, even.x)
        assertEquals(0, even.y)
        assertEquals(640, even.width)
        assertEquals(360, even.height)

        val odd = GameProgressCloseRecovery.topRightRoi(1281, 721)
        assertEquals(640, odd.x)
        assertEquals(0, odd.y)
        assertEquals(641, odd.width)
        assertEquals(360, odd.height)
    }

    @Test fun actualFullFrameMatchCoordinatesDetermineExactCenter() {
        assertEquals(
            LogicalPoint(920, 110),
            GameProgressCloseRecovery.centerIfMatched(TemplateMatch(0.96, true, 900, 100, 40, 20)),
        )
        assertNull(GameProgressCloseRecovery.centerIfMatched(TemplateMatch(0.2, false, 900, 100, 40, 20)))
    }

    @Test fun noMatchOnFirstAttemptStopsWithoutTap() {
        assertEquals(0, tapCountBeforeStop(false, true, true))
    }

    @Test fun noMatchOnSecondAttemptStopsAfterOneTap() {
        assertEquals(1, tapCountBeforeStop(true, false, true))
    }

    @Test fun threeMatchesStopAfterExactlyThreeTaps() {
        assertEquals(3, GameProgressCloseRecovery.MAX_ATTEMPTS)
        assertEquals(3, tapCountBeforeStop(true, true, true, true))
    }

    private fun tapCountBeforeStop(vararg matches: Boolean): Int {
        var taps = 0
        for (attempt in 1..GameProgressCloseRecovery.MAX_ATTEMPTS) {
            when (GameProgressCloseRecovery.decision(attempt, matches[attempt - 1])) {
                CloseRecoveryDecision.STOP_NO_MATCH -> break
                CloseRecoveryDecision.TAP_AND_CONTINUE -> taps += 1
                CloseRecoveryDecision.TAP_AND_STOP -> {
                    taps += 1
                    break
                }
            }
        }
        return taps
    }
}
