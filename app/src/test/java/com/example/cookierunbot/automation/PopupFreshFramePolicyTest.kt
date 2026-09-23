package com.example.cookierunbot.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupFreshFramePolicyTest {
    @Test fun callbackBaselineBlocksOldAndInFlightFramesUntilNewGeneration() {
        val postCallbackBaseline = 42L
        assertTrue(PopupFreshFramePolicy.blocks(41L, postCallbackBaseline))
        assertTrue(PopupFreshFramePolicy.blocks(42L, postCallbackBaseline))
        assertFalse(PopupFreshFramePolicy.blocks(43L, postCallbackBaseline))
    }

    @Test fun noCallbackGateAllowsFrame() {
        assertFalse(PopupFreshFramePolicy.blocks(42L, -1L))
    }

    @Test fun callbackBaselineInstalledDuringDetectorPassCannotBeConsumedByOldFrame() {
        val frameGeneration = 100L
        var baseline = -1L
        assertFalse(PopupFreshFramePolicy.blocks(frameGeneration, baseline))

        // The callback publishes a newer gate before the detector consumes it.
        baseline = 101L
        assertTrue(PopupFreshFramePolicy.blocks(frameGeneration, baseline))
        baseline = PopupFreshFramePolicy.baselineAfterAcceptedFrame(frameGeneration, baseline)
        assertEquals(101L, baseline)
        assertEquals(-1L, PopupFreshFramePolicy.baselineAfterAcceptedFrame(102L, baseline))
    }

    @Test fun callbackBaselineBlocksDetectedStageMutationUntilFreshFrame() {
        val retry = PurchaseItemRetryState().apply { boostPurchasePerformed() }
        val frameGeneration = 100L
        var currentBaseline = -1L
        assertFalse(PopupFreshFramePolicy.blocks(frameGeneration, currentBaseline))

        // The callback installs a new gate after detection but before state mutation.
        currentBaseline = 101L
        val staleApplied = PopupFreshFramePolicy.applySemanticMutationIfFresh(
            frameGeneration,
            currentBaseline,
            currentRunActive = true,
        ) { retry.onDetectedStage(BotStage.MAINMENU) }
        assertFalse(staleApplied)
        assertFalse(retry.shouldPrepareBoost(true))

        val freshApplied = PopupFreshFramePolicy.applySemanticMutationIfFresh(
            102L,
            currentBaseline,
            currentRunActive = true,
        ) { retry.onDetectedStage(BotStage.MAINMENU) }
        assertTrue(freshApplied)
        assertTrue(retry.shouldPrepareBoost(true))
    }

    @Test fun actionBoundaryRejectsFrameThatBecameStaleAfterFinalDetectorCheck() {
        val detectedFrame = 100L
        var baseline = -1L
        assertTrue(PopupFreshFramePolicy.allowsActionDispatch(true, detectedFrame, baseline))
        baseline = 101L // Accessibility callback completes before gesture dispatch.
        assertFalse(PopupFreshFramePolicy.allowsActionDispatch(true, detectedFrame, baseline))
        assertTrue(PopupFreshFramePolicy.allowsActionDispatch(true, 102L, baseline))
        assertFalse(PopupFreshFramePolicy.allowsActionDispatch(false, 102L, baseline))
    }
    @Test fun acceptedFirstActionDoesNotEndHandlerFreshnessProtection() {
        var detectionFrameGeneration: Long? = 100L
        var baseline = -1L

        assertTrue(
            PopupFreshFramePolicy.allowsActionDispatch(
                currentRunActive = true,
                detectionFrameGeneration = detectionFrameGeneration,
                currentBaseline = baseline,
            ),
        )

        // First action is accepted, but the handler still belongs to frame 100.
        baseline = 101L
        assertFalse(
            PopupFreshFramePolicy.allowsActionDispatch(
                currentRunActive = true,
                detectionFrameGeneration = detectionFrameGeneration,
                currentBaseline = baseline,
            ),
        )

        // handleStage finally owns the normal token cleanup.
        detectionFrameGeneration = null
        assertTrue(
            PopupFreshFramePolicy.allowsActionDispatch(
                currentRunActive = true,
                detectionFrameGeneration = detectionFrameGeneration,
                currentBaseline = baseline,
            ),
        )
    }

}
