package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoMatchWatchdogTest {
    @Test fun firstCaptureAtThirtySecondsAndSecondAtNinetySeconds() {
        val watchdog = NoMatchWatchdog()
        assertNull(watchdog.observeNoMatch(DetectionGroup.POST_GAME, 1_000L))
        assertNull(watchdog.observeNoMatch(DetectionGroup.POST_GAME, 21_000L))
        assertEquals(1, watchdog.observeNoMatch(DetectionGroup.POST_GAME, 31_000L)?.captureIndex)
        assertNull(watchdog.observeNoMatch(DetectionGroup.POST_GAME, 90_999L))
        assertEquals(2, watchdog.observeNoMatch(DetectionGroup.POST_GAME, 91_000L)?.captureIndex)
    }

    @Test fun matchedStateResetStartsANewIncident() {
        val watchdog = NoMatchWatchdog()
        watchdog.observeNoMatch(DetectionGroup.POST_GAME, 1_000L)
        watchdog.observeNoMatch(DetectionGroup.POST_GAME, 31_000L)
        val summary = watchdog.reset(101_000L)
        assertEquals(1, summary?.captures)
        assertNull(watchdog.observeNoMatch(DetectionGroup.POST_GAME, 200_000L))
        assertEquals(1, watchdog.observeNoMatch(DetectionGroup.POST_GAME, 230_000L)?.captureIndex)
    }

    @Test fun groupChangeResetsTimerAndCaptureIndex() {
        val watchdog = NoMatchWatchdog()
        watchdog.observeNoMatch(DetectionGroup.PRE_GAME, 1_000L)
        watchdog.observeNoMatch(DetectionGroup.PRE_GAME, 31_000L)
        assertNull(watchdog.observeNoMatch(DetectionGroup.IN_GAME, 32_000L))
        assertEquals(1, watchdog.observeNoMatch(DetectionGroup.IN_GAME, 62_000L)?.captureIndex)
    }

    @Test fun incidentNeverExceedsTenCaptures() {
        val watchdog = NoMatchWatchdog()
        watchdog.observeNoMatch(DetectionGroup.POST_GAME, 1_000L)
        val captures = (31_000L..700_000L step 60_000L).mapNotNull {
            watchdog.observeNoMatch(DetectionGroup.POST_GAME, it)
        }
        assertEquals(10, captures.size)
        assertTrue(watchdog.markLimitReported())
        assertNull(watchdog.observeNoMatch(DetectionGroup.POST_GAME, 800_000L))
    }

    @Test fun longObservationGapDoesNotBurstCatchUpCaptures() {
        val watchdog = NoMatchWatchdog()
        watchdog.observeNoMatch(DetectionGroup.POST_GAME, 1_000L)
        assertEquals(1, watchdog.observeNoMatch(DetectionGroup.POST_GAME, 301_000L)?.captureIndex)
        assertNull(watchdog.observeNoMatch(DetectionGroup.POST_GAME, 302_000L))
        assertNull(watchdog.observeNoMatch(DetectionGroup.POST_GAME, 360_999L))
        assertEquals(2, watchdog.observeNoMatch(DetectionGroup.POST_GAME, 361_000L)?.captureIndex)
    }

    @Test fun postGameDiagnosticsContainExpectedCandidates() {
        val stages = noMatchDiagnosticStages(DetectionGroup.POST_GAME)
        assertTrue(stages.containsAll(listOf(
            BotStage.ANTI_BOT,
            BotStage.OVERTAKE_BREAK_SCORE,
            BotStage.MYSTERY_BOX,
            BotStage.MAINMENU,
            BotStage.CONNECTION_LOST,
            BotStage.INACTIVE,
        )))
    }

    @Test fun inGameDiagnosticsContainExpectedCandidates() {
        val stages = noMatchDiagnosticStages(DetectionGroup.IN_GAME)
        assertTrue(stages.containsAll(listOf(
            BotStage.GAME_START,
            BotStage.GAME_RELAY,
            BotStage.GAME_COMPLETE,
            BotStage.CONNECTION_LOST,
            BotStage.INACTIVE,
        )))
    }
}
