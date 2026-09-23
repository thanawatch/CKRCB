package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLivesSafetyPolicyTest {
    private val phase = QuickLivesSafetyPolicy.Phase.WAITING_FOR_RESULT

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

    @Test fun stablePopupFalsePositiveDoesNotBeatMainMenuBehindMail() {
        val mailSignal = signal(
            popupState = BotStage.PREVIOUS_RESULTS,
            mainMenuMatched = true,
            expectedMailContextValid = true,
        )
        assertNull(QuickLivesSafetyPolicy.interruptState(mailSignal, mailSignal))
    }

    @Test fun confirmedPopupWithoutMainMenuInterrupts() {
        val popup = signal(popupState = BotStage.CONGRATULATIONS)
        assertEquals(BotStage.CONGRATULATIONS, QuickLivesSafetyPolicy.interruptState(popup, popup))
    }

    @Test fun realPopupStillInterruptsWhenMainMenuRemainsVisibleBehindIt() {
        val popup = signal(popupState = BotStage.TIME_DEAL, mainMenuMatched = true)
        assertEquals(BotStage.TIME_DEAL, QuickLivesSafetyPolicy.interruptState(popup, popup))
    }

    @Test fun earlyMailPhasesSuppressStableFalsePositiveWithoutResultTemplates() {
        for (mailPhase in listOf(
            QuickLivesSafetyPolicy.Phase.AFTER_MAIL_BOX,
            QuickLivesSafetyPolicy.Phase.AFTER_LIVES_TAB,
        )) {
            val context = QuickLivesSafetyPolicy.MailContext()
            val stableMail = PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT) { 42 })
            assertTrue(context.expectedScreenVisible(mailPhase, stableMail, BotStage.PREVIOUS_RESULTS, true, false))
            assertTrue(context.expectedScreenVisible(mailPhase, stableMail, BotStage.PREVIOUS_RESULTS, true, false))
            val signal = signal(
                phase = mailPhase,
                popupState = BotStage.PREVIOUS_RESULTS,
                mainMenuMatched = true,
                expectedMailContextValid = true,
            )
            assertNull(QuickLivesSafetyPolicy.interruptState(signal, signal))
        }
    }

    @Test fun genuinePopupChangesExpectedMailContextEvenIfMainMenuStillMatches() {
        val context = QuickLivesSafetyPolicy.MailContext()
        val normalMail = PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT))
        val realPopup = PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT) { 127 })
        context.expectedScreenVisible(phase, normalMail, null, true, false)
        assertFalse(context.expectedScreenVisible(phase, realPopup, BotStage.TIME_DEAL, true, false))
        assertFalse(context.expectedScreenVisible(phase, realPopup, BotStage.TIME_DEAL, true, false))
        val signal = signal(popupState = BotStage.TIME_DEAL, mainMenuMatched = true)
        assertEquals(BotStage.TIME_DEAL, QuickLivesSafetyPolicy.interruptState(signal, signal))
    }

    @Test fun waitingForResultKnownMailTemplateSuppressesFalsePositive() {
        val context = QuickLivesSafetyPolicy.MailContext()
        assertTrue(context.expectedScreenVisible(phase, null, BotStage.CONGRATULATIONS, true, true))
        val signal = signal(
            popupState = BotStage.CONGRATULATIONS,
            mainMenuMatched = true,
            expectedMailContextValid = true,
        )
        assertNull(QuickLivesSafetyPolicy.interruptState(signal, signal))
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
        expectedMailContextValid: Boolean = false,
        phase: QuickLivesSafetyPolicy.Phase = this.phase,
    ) = QuickLivesSafetyPolicy.ScreenSignal(phase, alwaysState, popupState, mainMenuMatched, expectedMailContextValid)
}
