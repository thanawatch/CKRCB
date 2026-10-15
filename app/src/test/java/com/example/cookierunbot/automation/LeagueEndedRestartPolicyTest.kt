package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeagueEndedRestartPolicyTest {
    @Test fun prePostAndInGameAreEligibleForBackgroundDetection() {
        assertTrue(LeagueEndedRestartPolicy.eligibleGroup(DetectionGroup.PRE_GAME))
        assertTrue(LeagueEndedRestartPolicy.eligibleGroup(DetectionGroup.POST_GAME))
        assertTrue(LeagueEndedRestartPolicy.eligibleGroup(DetectionGroup.IN_GAME))
    }

    @Test fun referenceGeometryMatchesTheSuppliedNormalizedFrame() {
        assertEquals("LEAGUE_ENDED_RESTART_1.png", LeagueEndedRestartPolicy.TEMPLATE)
        assertEquals(330, LeagueEndedRestartPolicy.ROI_X)
        assertEquals(160, LeagueEndedRestartPolicy.ROI_Y)
        assertEquals(604, LeagueEndedRestartPolicy.ROI_WIDTH)
        assertEquals(252, LeagueEndedRestartPolicy.ROI_HEIGHT)
        assertEquals(LogicalPoint(640, 458), LeagueEndedRestartPolicy.CONFIRM_POINT)
    }
}
