package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostRelayTargetPolicyTest {
    @Test
    fun redfingerUsesUserCalibratedNormalizedTargetsInSequenceOrder() {
        val points = PostRelayTargetPolicy.optimizedLogicalTargets(PerformanceProfile.REDFINGER_OPTIMIZED)
        assertEquals(
            listOf(LogicalPoint(1198, 35), LogicalPoint(647, 425), LogicalPoint(637, 389)),
            points,
        )
        assertTrue(points!!.all { it.x in 0 until 1280 && it.y in 0 until 720 })
    }

    @Test
    fun legacyDoesNotReceiveReplacementLogicalCoordinates() {
        assertNull(PostRelayTargetPolicy.optimizedLogicalTargets(PerformanceProfile.LEGACY))
        assertEquals(PhysicalPoint(2180, 55), AutomationConfig.POST_RELAY_1_PHYSICAL)
        assertEquals(PhysicalPoint(1150, 640), AutomationConfig.POST_RELAY_2_PHYSICAL)
        assertEquals(PhysicalPoint(1150, 570), AutomationConfig.POST_RELAY_3_PHYSICAL)
    }
}
