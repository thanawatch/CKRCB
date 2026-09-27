package com.example.cookierunbot.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalTargetPolicyTest {
    @Test
    fun legacyTargetsRemainCalibrated() {
        assertTrue(PhysicalTargetPolicy.isCalibrated(PerformanceProfile.LEGACY))
    }

    @Test
    fun redfingerTargetsRequireSeparateCalibration() {
        assertFalse(PhysicalTargetPolicy.isCalibrated(PerformanceProfile.REDFINGER_OPTIMIZED))
    }
}
