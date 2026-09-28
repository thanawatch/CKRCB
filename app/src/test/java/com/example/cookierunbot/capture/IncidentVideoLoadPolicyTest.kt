package com.example.cookierunbot.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentVideoLoadPolicyTest {
    @Test fun processCpuBudgetUsesSustainedTwoSecondWindow() {
        assertFalse(IncidentVideoLoadPolicy.processCpuTooBusy(4_000L, 1_999L))
        assertFalse(IncidentVideoLoadPolicy.processCpuTooBusy(2_000L, 2_000L))
        assertTrue(IncidentVideoLoadPolicy.processCpuTooBusy(2_001L, 2_000L))
        assertFalse(IncidentVideoLoadPolicy.processCpuTooBusy(-1L, 2_000L))
    }

    @Test fun captureRateMustLeaveHeadroomForGameplay() {
        assertTrue(IncidentVideoLoadPolicy.captureTooSlow(0.0, 2))
        assertTrue(IncidentVideoLoadPolicy.captureTooSlow(Double.NaN, 2))
        assertTrue(IncidentVideoLoadPolicy.captureTooSlow(Double.POSITIVE_INFINITY, 2))
        assertTrue(IncidentVideoLoadPolicy.captureTooSlow(-1.0, 2))
        assertTrue(IncidentVideoLoadPolicy.captureTooSlow(4.0, 0))
        assertFalse(IncidentVideoLoadPolicy.captureTooSlow(4.0, 2))
        assertTrue(IncidentVideoLoadPolicy.captureTooSlow(3.99, 2))
        // New 0.5-FPS JPEG sampler asks for a minimum 2 capture FPS (4x headroom).
        assertFalse(IncidentVideoLoadPolicy.captureTooSlow(2.0, 1))
        assertTrue(IncidentVideoLoadPolicy.captureTooSlow(1.99, 1))
    }

    @Test fun degradedSamplingRequiresTenTicksRatherThanTwoCostlyImages() {
        assertFalse(IncidentVideoLoadPolicy.shouldStopSampling(0))
        assertFalse(IncidentVideoLoadPolicy.shouldStopSampling(2))
        assertFalse(IncidentVideoLoadPolicy.shouldStopSampling(9))
        assertTrue(IncidentVideoLoadPolicy.shouldStopSampling(10))
    }

    @Test fun sampleWallAndThreadCpuBudgetsAreIndependent() {
        assertFalse(IncidentVideoLoadPolicy.sampleTooExpensive(250L, 80_000_000L))
        assertTrue(IncidentVideoLoadPolicy.sampleTooExpensive(251L, 10_000_000L))
        assertTrue(IncidentVideoLoadPolicy.sampleTooExpensive(100L, 80_000_001L))
        assertFalse(IncidentVideoLoadPolicy.sampleTooExpensive(20L, -1L))
    }
}
