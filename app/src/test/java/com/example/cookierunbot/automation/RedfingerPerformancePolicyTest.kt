package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RedfingerPerformancePolicyTest {
    @Test
    fun profilingIsStrictlyOptIn() {
        assertFalse(RedfingerPerformancePolicy.profilingEnabled(PerformanceProfile.LEGACY))
        assertTrue(
            RedfingerPerformancePolicy.profilingEnabled(PerformanceProfile.REDFINGER_OPTIMIZED),
        )
    }

    @Test
    fun detailedNoMatchScoringDoesNotInterruptOptimizedGameplay() {
        assertTrue(
            RedfingerPerformancePolicy.deferDetailedNoMatchScoring(
                PerformanceProfile.REDFINGER_OPTIMIZED, DetectionGroup.IN_GAME,
            ),
        )
        assertFalse(
            RedfingerPerformancePolicy.deferDetailedNoMatchScoring(
                PerformanceProfile.REDFINGER_OPTIMIZED, DetectionGroup.PRE_GAME,
            ),
        )
        assertFalse(
            RedfingerPerformancePolicy.deferDetailedNoMatchScoring(
                PerformanceProfile.REDFINGER_OPTIMIZED, DetectionGroup.POST_GAME,
            ),
        )
        assertFalse(
            RedfingerPerformancePolicy.deferDetailedNoMatchScoring(
                PerformanceProfile.LEGACY, DetectionGroup.IN_GAME,
            ),
        )
    }

    @Test
    fun shortRoundCanDrainAtGameStartCompletion() {
        val metrics = FastStartAttemptMetrics()
        metrics.record(durationNanos = 12L, skipped = 2L)

        val summary = metrics.drain()!!

        assertEquals(1L, summary.count)
        assertEquals(12L, summary.minDurationNanos)
        assertEquals(12L, summary.maxDurationNanos)
        assertEquals(2L, summary.skippedGenerations)
        assertNull(metrics.drain())
    }

    @Test
    fun boundaryCrossingAttemptRemainsInCapturedWindowSummary() {
        val metrics = FastStartAttemptMetrics()
        // The caller records from the single pre-detection window decision even if matching
        // completes after the deadline.
        metrics.record(durationNanos = 8_000_000_001L, skipped = 0L)

        assertEquals(8_000_000_001L, metrics.drain()!!.maxDurationNanos)
    }

    @Test
    fun stopBeforeNextTickCanDrainPendingSamples() {
        val metrics = FastStartAttemptMetrics()
        metrics.record(durationNanos = 5L, skipped = 1L)
        metrics.record(durationNanos = 9L, skipped = 3L)

        val stopSummary = metrics.drain()!!

        assertEquals(2L, stopSummary.count)
        assertEquals(14L, stopSummary.totalDurationNanos)
        assertEquals(4L, stopSummary.skippedGenerations)
    }

    @Test
    fun completeStopTeardownFinishesBeforeNewStartTouchesSharedServices() {
        val lifecycle = BotLifecycleTransition()
        val oldStopEntered = CountDownLatch(1)
        val allowOldStopToFinish = CountDownLatch(1)
        val newStartFinished = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var watchdogRunning = true
        var listenerRunning = true

        val oldStop = Thread {
            lifecycle.run {
                events += "old-stop-entered"
                oldStopEntered.countDown()
                assertTrue(allowOldStopToFinish.await(2, TimeUnit.SECONDS))
                watchdogRunning = false
                listenerRunning = false
                events += "old-stop-finished"
            }
        }
        val newStart = Thread {
            assertTrue(oldStopEntered.await(2, TimeUnit.SECONDS))
            lifecycle.run {
                watchdogRunning = true
                listenerRunning = true
                events += "new-start-finished"
            }
            newStartFinished.countDown()
        }

        oldStop.start()
        newStart.start()
        assertTrue(oldStopEntered.await(2, TimeUnit.SECONDS))
        assertFalse(newStartFinished.await(100, TimeUnit.MILLISECONDS))
        allowOldStopToFinish.countDown()
        oldStop.join(2_000L)
        newStart.join(2_000L)

        assertEquals(
            listOf("old-stop-entered", "old-stop-finished", "new-start-finished"),
            events,
        )
        assertTrue(watchdogRunning)
        assertTrue(listenerRunning)
    }

    @Test
    fun staleManagerStopCannotDeactivateNewRunSession() {
        val session = RunGenerationSession()
        session.activate(20L)
        session.activate(21L)

        assertFalse(session.deactivateIfCurrent(20L))
        assertTrue(session.deactivateIfCurrent(21L))
        assertFalse(session.deactivateIfCurrent(21L))
    }

    @Test
    fun popupCalibrationBlockRemainsAuthoritativeUntilDifferentDetection() {
        val block = CalibrationBlockState()

        assertTrue(block.block("TIME_DEAL"))
        assertEquals(
            CalibrationBlockState.WAITING_FOR_REDFINGER_CALIBRATION,
            block.authoritativeState("WAITING_FOR_PRE_GAME"),
        )
        assertFalse(block.block("TIME_DEAL")) // repeated identical popup frame
        assertNull(block.clearIfDifferent("TIME_DEAL"))
        assertEquals(
            CalibrationBlockState.WAITING_FOR_REDFINGER_CALIBRATION,
            block.authoritativeState("WAITING_FOR_PRE_GAME"), // intervening NO_MATCH
        )
        assertEquals("TIME_DEAL", block.clearIfDifferent("MAINMENU"))
        assertEquals("MAINMENU", block.authoritativeState("MAINMENU"))
    }

    @Test
    fun overlayCalibrationStatusNamesManualClearAction() {
        assertEquals(
            "Waiting: Redfinger calibration (PARTY_RUN_SEASON_REWARDS) — clear popup manually",
            redfingerCalibrationStatusText("PARTY_RUN_SEASON_REWARDS"),
        )
    }
}
