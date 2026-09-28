package com.example.cookierunbot.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentVideoLoadPolicyTest {
    @Test fun processCpuBudgetUsesSustainedTwoSecondWindow() {
        assertFalse(IncidentVideoLoadPolicy.processCpuTooBusy(4_000L, 1_999L))
        assertFalse(IncidentVideoLoadPolicy.processCpuTooBusy(3_000L, 2_000L))
        assertTrue(IncidentVideoLoadPolicy.processCpuTooBusy(3_001L, 2_000L))
        assertFalse(IncidentVideoLoadPolicy.processCpuTooBusy(-1L, 2_000L))
    }

    @Test fun sampleWallAndThreadCpuBudgetsAreIndependent() {
        assertFalse(IncidentVideoLoadPolicy.sampleTooExpensive(250L, 80_000_000L))
        assertTrue(IncidentVideoLoadPolicy.sampleTooExpensive(251L, 10_000_000L))
        assertTrue(IncidentVideoLoadPolicy.sampleTooExpensive(100L, 80_000_001L))
        assertFalse(IncidentVideoLoadPolicy.sampleTooExpensive(20L, -1L))
    }
}
