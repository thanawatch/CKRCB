package com.example.cookierunbot.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendScrollVerifierTest {
    @Test fun identicalViewportIsUnchanged() {
        val sample = FriendScrollSample(ByteArray(110 * 60) { 100.toByte() })
        val result = FriendScrollVerifier.compare(sample, sample)
        assertFalse(result.changed)
    }

    @Test fun tinyCaptureNoiseIsUnchanged() {
        val before = ByteArray(110 * 60) { 100.toByte() }
        val after = before.copyOf().also {
            for (index in 0 until 50) it[index] = 104.toByte()
        }
        val result = FriendScrollVerifier.compare(FriendScrollSample(before), FriendScrollSample(after))
        assertFalse(result.changed)
    }

    @Test fun movedLeaderboardRowBandsAreChanged() {
        val before = ByteArray(110 * 60) { index ->
            if (index / 110 < 30) 20.toByte() else 220.toByte()
        }
        val after = ByteArray(110 * 60) { index ->
            if (index / 110 < 30) 220.toByte() else 20.toByte()
        }
        val result = FriendScrollVerifier.compare(FriendScrollSample(before), FriendScrollSample(after))
        assertTrue(result.changed)
        assertTrue(result.difference > FriendScrollVerifier.DIFFERENCE_THRESHOLD)
    }

    @Test fun unchangedFindTopGestureIsNotMeaningfulProgress() {
        assertNull(verifiedFriendScrollProgressEvent("FIND_TOP", changed = false))
        assertFalse(isMeaningfulFriendLifeProgressEvent("TOP_SCROLL_COMPLETE"))
        assertFalse(isMeaningfulFriendLifeProgressEvent("TOP_SCROLL_UNCHANGED"))
    }

    @Test fun verifiedFindTopMovementBecomesMeaningfulProgress() {
        val event = verifiedFriendScrollProgressEvent("FIND_TOP", changed = true)
        assertEquals("TOP_SCROLL_CHANGED", event)
        assertTrue(isMeaningfulFriendLifeProgressEvent(requireNotNull(event)))
    }

    @Test fun topDetectedRemainsMeaningfulProgress() {
        assertTrue(isMeaningfulFriendLifeProgressEvent("TOP_DETECTED"))
    }
}
