package com.example.cookierunbot.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRecoveryGateTest {
    @Test fun normalDetectionIsBlockedThroughoutGestureFrames() {
        val gate = ProgressRecoveryGate()
        assertTrue(gate.begin(1L))
        // Frames 101 and 102 can arrive after timeout frame 100 while the tap is pending.
        assertTrue(gate.isActive())
        assertFalse(gate.clearAfterFreshFrame(1L, 102L, 101L))
        assertFalse(gate.clearAfterFreshFrame(1L, 102L, 102L))
        assertTrue(gate.isActive())
    }

    @Test fun onlyFrameNewerThanPostTapBaselineReleasesGate() {
        val gate = ProgressRecoveryGate()
        gate.begin(1L)
        assertTrue(gate.clearAfterFreshFrame(1L, 102L, 103L))
        assertFalse(gate.isActive())
    }

    @Test fun gestureFailureOrTimeoutCanClearGate() {
        val gate = ProgressRecoveryGate()
        gate.begin(1L)
        assertTrue(gate.clearIfOwner(1L)) // callback failure
        assertFalse(gate.isActive())
        gate.begin(1L)
        assertTrue(gate.clearIfOwner(1L)) // callback timeout
        assertFalse(gate.isActive())
    }

    @Test fun freshFrameTimeoutCanClearGate() {
        val gate = ProgressRecoveryGate()
        gate.begin(1L)
        assertFalse(gate.clearAfterFreshFrame(1L, 102L, 102L))
        assertTrue(gate.clearIfOwner(1L))
        assertFalse(gate.isActive())
    }

    @Test fun stopAndNewRunRejectStaleClear() {
        val gate = ProgressRecoveryGate()
        gate.begin(1L)
        gate.reset() // Stop Bot
        assertTrue(gate.begin(3L)) // New Start Bot
        assertFalse(gate.clearIfOwner(1L))
        assertTrue(gate.isActive())
        assertTrue(gate.clearIfOwner(3L))
    }
}
