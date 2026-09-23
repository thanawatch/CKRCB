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

    @Test fun stablePopupFalsePositiveDoesNotBeatTrustedMailContext() {
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

    @Test fun genuinePopupCannotSeedReferenceAsFirstFrameOfNewPhase() {
        val context = QuickLivesSafetyPolicy.MailContext()
        val popupFingerprint =
            PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT) { 127 })

        assertFalse(
            context.expectedScreenVisible(
                phase,
                popupFingerprint,
                BotStage.TIME_DEAL,
                mainMenuMatched = true,
                knownMailTemplateMatched = false,
            ),
        )
        assertFalse(
            context.expectedScreenVisible(
                phase,
                popupFingerprint,
                BotStage.TIME_DEAL,
                mainMenuMatched = true,
                knownMailTemplateMatched = false,
            ),
        )

        val popup = signal(
            popupState = BotStage.TIME_DEAL,
            mainMenuMatched = true,
            expectedMailContextValid = false,
        )
        assertEquals(BotStage.TIME_DEAL, QuickLivesSafetyPolicy.interruptState(popup, popup))
    }

    @Test fun popupFreeMailFrameSeedsReferenceThenStableFalsePositiveContinues() {
        for (mailPhase in listOf(
            QuickLivesSafetyPolicy.Phase.AFTER_MAIL_BOX,
            QuickLivesSafetyPolicy.Phase.AFTER_LIVES_TAB,
        )) {
            val context = QuickLivesSafetyPolicy.MailContext()
            val normalMail =
                PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT) { 42 })

            context.expectedScreenVisible(
                mailPhase,
                normalMail,
                popupCandidate = null,
                mainMenuMatched = true,
                knownMailTemplateMatched = false,
            )

            assertTrue(
                context.expectedScreenVisible(
                    mailPhase,
                    normalMail,
                    BotStage.PREVIOUS_RESULTS,
                    mainMenuMatched = true,
                    knownMailTemplateMatched = false,
                ),
            )
            assertTrue(
                context.expectedScreenVisible(
                    mailPhase,
                    normalMail,
                    BotStage.PREVIOUS_RESULTS,
                    mainMenuMatched = true,
                    knownMailTemplateMatched = false,
                ),
            )

            val falsePositive = signal(
                phase = mailPhase,
                popupState = BotStage.PREVIOUS_RESULTS,
                mainMenuMatched = true,
                expectedMailContextValid = true,
            )
            assertNull(QuickLivesSafetyPolicy.interruptState(falsePositive, falsePositive))
        }
    }

    @Test fun trustedMailReferenceRejectsMeaningfullyDifferentPopup() {
        val context = QuickLivesSafetyPolicy.MailContext()
        val normalMail =
            PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT))
        val realPopup =
            PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT) { 127 })

        context.expectedScreenVisible(phase, normalMail, null, true, false)

        assertFalse(
            context.expectedScreenVisible(
                phase,
                realPopup,
                BotStage.TIME_DEAL,
                mainMenuMatched = true,
                knownMailTemplateMatched = false,
            ),
        )

        val popup = signal(popupState = BotStage.TIME_DEAL, mainMenuMatched = true)
        assertEquals(BotStage.TIME_DEAL, QuickLivesSafetyPolicy.interruptState(popup, popup))
    }

    @Test fun knownMailTemplateCannotOverrideMeaningfulPopupChange() {
        val context = QuickLivesSafetyPolicy.MailContext()
        val normalMail =
            PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT))
        val realPopup =
            PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT) { 127 })

        context.expectedScreenVisible(phase, normalMail, null, true, false)

        assertFalse(
            context.expectedScreenVisible(
                phase,
                realPopup,
                BotStage.TIME_DEAL,
                mainMenuMatched = true,
                knownMailTemplateMatched = true,
            ),
        )
    }

    @Test fun knownMailTemplateSupportsStableFalsePositiveAfterTrustedReferenceExists() {
        val context = QuickLivesSafetyPolicy.MailContext()
        val normalMail =
            PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT) { 42 })

        context.expectedScreenVisible(phase, normalMail, null, true, false)

        assertTrue(
            context.expectedScreenVisible(
                phase,
                normalMail,
                BotStage.CONGRATULATIONS,
                mainMenuMatched = true,
                knownMailTemplateMatched = true,
            ),
        )
        val falsePositive = signal(
            popupState = BotStage.CONGRATULATIONS,
            mainMenuMatched = true,
            expectedMailContextValid = true,
        )
        assertNull(QuickLivesSafetyPolicy.interruptState(falsePositive, falsePositive))
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
    ) = QuickLivesSafetyPolicy.ScreenSignal(
        phase,
        alwaysState,
        popupState,
        mainMenuMatched,
        expectedMailContextValid,
    )
}
