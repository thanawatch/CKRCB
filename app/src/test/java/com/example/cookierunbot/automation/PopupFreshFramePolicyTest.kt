package com.example.cookierunbot.automation

import org.junit.Assert.assertFalse
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
}
