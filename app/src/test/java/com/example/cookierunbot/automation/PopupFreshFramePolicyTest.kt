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
}
