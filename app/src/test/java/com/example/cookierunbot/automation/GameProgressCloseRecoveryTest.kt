package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.TemplateMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameProgressCloseRecoveryTest {
    @Test fun closeRecoveryUsesDedicatedThreshold() {
        assertEquals(0.13, GameProgressCloseRecovery.MATCH_THRESHOLD, 0.0)
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
