package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLivesSafetyPolicyTest {
    @Test fun mailScreenContinuesUntilDeadline() {
        assertNull(QuickLivesSafetyPolicy.interruptState(null, null, false))
        assertFalse(QuickLivesSafetyPolicy.timedOut(119_999L, 120_000L))
        assertTrue(QuickLivesSafetyPolicy.timedOut(120_000L, 120_000L))
    }

    @Test fun popupOrMainMenuReturnsControlToDetector() {
        assertEquals(
            BotStage.PREVIOUS_RESULTS,
            QuickLivesSafetyPolicy.interruptState(null, BotStage.PREVIOUS_RESULTS, false),
        )
        assertEquals(BotStage.MAINMENU, QuickLivesSafetyPolicy.interruptState(null, null, true))
    }

    @Test fun connectionLostHasPriorityOverPopup() {
        assertEquals(
            BotStage.CONNECTION_LOST,
            QuickLivesSafetyPolicy.interruptState(
                BotStage.CONNECTION_LOST,
                BotStage.CONGRATULATIONS,
                true,
            ),
        )
    }
}
