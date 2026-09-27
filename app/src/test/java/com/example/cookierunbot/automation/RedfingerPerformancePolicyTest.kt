package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedfingerPerformancePolicyTest {
    @Test
    fun profilingIsStrictlyOptIn() {
        assertFalse(RedfingerPerformancePolicy.profilingEnabled(PerformanceProfile.LEGACY))
        assertTrue(
            RedfingerPerformancePolicy.profilingEnabled(PerformanceProfile.REDFINGER_OPTIMIZED),
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
}
