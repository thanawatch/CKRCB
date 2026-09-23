package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLivesSafetyPolicyTest {
    @Test fun mailScreenContinuesUntilDeadline() {
        assertNull(QuickLivesSafetyPolicy.interruptState(signal()))
        assertFalse(QuickLivesSafetyPolicy.timedOut(119_999L, 120_000L))
        assertTrue(QuickLivesSafetyPolicy.timedOut(120_000L, 120_000L))
    }

    @Test fun mainMenuVisibleBehindMailDoesNotInterrupt() {
        assertNull(QuickLivesSafetyPolicy.interruptState(signal(mainMenuMatched = true)))
    }

    @Test fun popupNeedsConfirmationOnAnotherFrame() {
        val popup = signal(popupState = BotStage.PREVIOUS_RESULTS, mainMenuMatched = true)
        assertNull(QuickLivesSafetyPolicy.interruptState(popup))
        assertNull(QuickLivesSafetyPolicy.interruptState(popup, signal(mainMenuMatched = true)))
        assertEquals(
            BotStage.PREVIOUS_RESULTS,
            QuickLivesSafetyPolicy.interruptState(popup, signal(popupState = BotStage.PREVIOUS_RESULTS)),
        )
    }

    @Test fun alwaysStatesInterruptImmediately() {
        assertEquals(
            BotStage.CONNECTION_LOST,
            QuickLivesSafetyPolicy.interruptState(
                signal(BotStage.CONNECTION_LOST, BotStage.CONGRATULATIONS, true),
            ),
        )
        assertEquals(BotStage.INACTIVE, QuickLivesSafetyPolicy.interruptState(signal(BotStage.INACTIVE)))
    }

    private fun signal(
        alwaysState: BotStage? = null,
        popupState: BotStage? = null,
        mainMenuMatched: Boolean = false,
    ) = QuickLivesSafetyPolicy.ScreenSignal(alwaysState, popupState, mainMenuMatched)
}
