package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MysteryBoxTapPolicyTest {
    @Test fun sequenceStopsAfterThirdSuccessfulPhase() {
        assertEquals(1, MysteryBoxTapPolicy.nextPhase(0))
        assertEquals(2, MysteryBoxTapPolicy.nextPhase(1))
        assertEquals(3, MysteryBoxTapPolicy.nextPhase(2))
        assertNull(MysteryBoxTapPolicy.nextPhase(3))
        assertNull(MysteryBoxTapPolicy.nextPhase(4))
    }

    @Test fun finalPhaseUsesNoJitter() {
        assertEquals(AutomationConfig.DEFAULT_SAFE_JITTER, MysteryBoxTapPolicy.jitterForPhase(1))
        assertEquals(AutomationConfig.DEFAULT_SAFE_JITTER, MysteryBoxTapPolicy.jitterForPhase(2))
        assertEquals(0, MysteryBoxTapPolicy.jitterForPhase(3))
    }

    @Test fun finalPhaseRequiresTwoFreshRevalidationFrames() {
        assertEquals(1, MysteryBoxTapPolicy.requiredFreshFrames(1))
        assertEquals(1, MysteryBoxTapPolicy.requiredFreshFrames(2))
        assertEquals(2, MysteryBoxTapPolicy.requiredFreshFrames(3))
    }

    @Test fun onlyPreFinalPhasesRearmSameMysteryBoxStage() {
        assertTrue(MysteryBoxTapPolicy.shouldRearmSameStageAfterSuccess(1))
        assertTrue(MysteryBoxTapPolicy.shouldRearmSameStageAfterSuccess(2))
        assertEquals(false, MysteryBoxTapPolicy.shouldRearmSameStageAfterSuccess(3))
    }

    @Test fun ambiguousMainMenuFramesWaitBeforeStoppingTheTap() {
        assertEquals(
            MysteryBoxTapPolicy.FrameDecision.WAIT_FOR_UNAMBIGUOUS_FRAME,
            MysteryBoxTapPolicy.frameDecision(
                mysteryBoxMatched = true,
                mainMenuMatched = true,
                ambiguousMainMenuFrames = 0,
            ),
        )
        assertEquals(
            MysteryBoxTapPolicy.FrameDecision.WAIT_FOR_UNAMBIGUOUS_FRAME,
            MysteryBoxTapPolicy.frameDecision(
                mysteryBoxMatched = true,
                mainMenuMatched = true,
                ambiguousMainMenuFrames = 1,
            ),
        )
        assertEquals(
            MysteryBoxTapPolicy.FrameDecision.STOP_MAINMENU_VISIBLE,
            MysteryBoxTapPolicy.frameDecision(
                mysteryBoxMatched = true,
                mainMenuMatched = true,
                ambiguousMainMenuFrames = 2,
            ),
        )
    }

    @Test fun cleanMysteryBoxFrameAllowsTapAndCleanMainMenuStopsIt() {
        assertEquals(
            MysteryBoxTapPolicy.FrameDecision.ALLOW_TAP,
            MysteryBoxTapPolicy.frameDecision(
                mysteryBoxMatched = true,
                mainMenuMatched = false,
                ambiguousMainMenuFrames = 0,
            ),
        )
        assertEquals(
            MysteryBoxTapPolicy.FrameDecision.STOP_MAINMENU_VISIBLE,
            MysteryBoxTapPolicy.frameDecision(
                mysteryBoxMatched = false,
                mainMenuMatched = true,
                ambiguousMainMenuFrames = 0,
            ),
        )
        assertEquals(
            MysteryBoxTapPolicy.FrameDecision.DEFER_MYSTERY_BOX_GONE,
            MysteryBoxTapPolicy.frameDecision(
                mysteryBoxMatched = false,
                mainMenuMatched = false,
                ambiguousMainMenuFrames = 0,
            ),
        )
    }

    @Test fun acceptedGestureTimeoutCommitsPhaseToPreventDuplicateLateTap() {
        assertTrue(MysteryBoxTapPolicy.shouldCommitAcceptedGesture(success = true, timedOut = false))
        assertTrue(MysteryBoxTapPolicy.shouldCommitAcceptedGesture(success = false, timedOut = true))
        assertFalse(MysteryBoxTapPolicy.shouldCommitAcceptedGesture(success = false, timedOut = false))
    }
}
