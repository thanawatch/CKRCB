package com.example.cookierunbot.automation

import com.example.cookierunbot.detection.TemplateMatch
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendLifeConfirmPolicyTest {
    @Test fun confirmWaitAndRadiusStayWithinRequestedBounds() {
        assertEquals(150, FriendLifeConfirmPolicy.WAIT_MIN_MS)
        assertEquals(300, FriendLifeConfirmPolicy.WAIT_MAX_MS)
        assertEquals(5.0, FriendLifeConfirmPolicy.RADIUS_LOGICAL, 0.0)
    }

    @Test fun confirmTwoRetryWindowMatchesDelayedPopupRequirement() {
        assertEquals(10_000L, FriendLifeConfirmPolicy.CONFIRM_2_SEARCH_TIMEOUT_MS)
        assertEquals(120L, FriendLifeConfirmPolicy.CONFIRM_2_RETRY_INTERVAL_MS)
    }

    @Test fun orphanConfirmDoesNotResetGameProgressWatchdog() {
        assertNull(friendSendTapProgressReason("CONFIRM_2_ORPHAN", gestureSucceeded = true))
    }

    @Test fun orphanConfirmTapsOnlyOnceUntilThePopupDisappears() {
        assertEquals(
            FriendLifeConfirmPolicy.OrphanDecision.TAP_ONCE,
            FriendLifeConfirmPolicy.orphanDecision(
                tapSuppressedUntilDisappear = false,
                stuckConfirmDue = false,
                elapsedMs = 1_000L,
                noProgressMs = 1_000L,
            ),
        )
        assertEquals(
            FriendLifeConfirmPolicy.OrphanDecision.WAIT_FOR_DISAPPEAR,
            FriendLifeConfirmPolicy.orphanDecision(
                tapSuppressedUntilDisappear = true,
                stuckConfirmDue = false,
                elapsedMs = 2_000L,
                noProgressMs = 2_000L,
            ),
        )
    }

    @Test fun orphanConfirmCannotBypassNoProgressSafety() {
        assertEquals(
            FriendLifeConfirmPolicy.OrphanDecision.ABORT_NO_PROGRESS,
            FriendLifeConfirmPolicy.orphanDecision(
                tapSuppressedUntilDisappear = true,
                stuckConfirmDue = false,
                elapsedMs = 60_000L,
                noProgressMs = FRIEND_LIFE_NO_PROGRESS_TIMEOUT_MS,
            ),
        )
    }

    @Test fun orphanConfirmKeepsFullFrameStuckSafetyAheadOfAnotherTap() {
        assertEquals(
            FriendLifeConfirmPolicy.OrphanDecision.INTERRUPT_STUCK,
            FriendLifeConfirmPolicy.orphanDecision(
                tapSuppressedUntilDisappear = false,
                stuckConfirmDue = true,
                elapsedMs = 60_000L,
                noProgressMs = 1_000L,
            ),
        )
    }

    @Test fun orphanConfirmCannotBypassAbsoluteActiveTimeout() {
        assertEquals(
            FriendLifeConfirmPolicy.OrphanDecision.ABORT_ABSOLUTE_TIMEOUT,
            FriendLifeConfirmPolicy.orphanDecision(
                tapSuppressedUntilDisappear = false,
                stuckConfirmDue = true,
                elapsedMs = FRIEND_LIFE_ABSOLUTE_TIMEOUT_MS,
                noProgressMs = 0L,
            ),
        )
    }

    @Test fun confirmTwoFreshFrameWaitRetriesUntilOverallDeadline() {
        assertEquals(
            FriendLifeConfirmPolicy.NoFreshFrameDecision.RETRY,
            FriendLifeConfirmPolicy.confirmTwoNoFreshFrameDecision(
                nowMs = 2_500L,
                deadlineMs = FriendLifeConfirmPolicy.CONFIRM_2_SEARCH_TIMEOUT_MS,
            ),
        )
        assertEquals(
            FriendLifeConfirmPolicy.NoFreshFrameDecision.SKIP,
            FriendLifeConfirmPolicy.confirmTwoNoFreshFrameDecision(
                nowMs = FriendLifeConfirmPolicy.CONFIRM_2_SEARCH_TIMEOUT_MS,
                deadlineMs = FriendLifeConfirmPolicy.CONFIRM_2_SEARCH_TIMEOUT_MS,
            ),
        )
    }

    @Test fun friendLifeUsesSeparateConfirmRegionsFromReferenceFrames() {
        val confirmOne = FriendLifeConfirmPolicy.confirmOneRegion(1280, 720)
        assertEquals(640, confirmOne.x)
        assertEquals(402, confirmOne.y)
        assertEquals(304, confirmOne.width)
        assertEquals(113, confirmOne.height)

        val confirmTwo = FriendLifeConfirmPolicy.confirmTwoRegion(1280, 720)
        assertEquals(487, confirmTwo.x)
        assertEquals(402, confirmTwo.y)
        assertEquals(304, confirmTwo.width)
        assertEquals(113, confirmTwo.height)
    }
    @Test fun actualMatchDeterminesConfirmCenter() {
        assertEquals(
            LogicalPoint(740, 452),
            FriendLifeConfirmPolicy.centerIfMatched(TemplateMatch(0.95, true, 640, 402, 200, 100)),
        )
    }

    @Test fun fullFrameUsesActualFrameDimensions() {
        val region = FriendLifeConfirmPolicy.fullFrame(1280, 720)
        assertEquals(0, region.x)
        assertEquals(0, region.y)
        assertEquals(1280, region.width)
        assertEquals(720, region.height)
    }

    @Test fun circularLogicalTapStaysWithinFivePixels() {
        val center = LogicalPoint(740, 452)
        val random = Random(37)
        repeat(10_000) {
            val point = FriendLifeConfirmPolicy.pointInCircle(center, random)
            assertTrue(hypot((point.x - center.x).toDouble(), (point.y - center.y).toDouble()) <= 5.0)
        }
    }

    @Test fun confirmOneNoMatchSkipsTapAndContinuesToConfirmTwo() {
        val noMatch = TemplateMatch(0.1, false, 640, 402, 200, 100)
        assertNull(FriendLifeConfirmPolicy.centerIfMatched(noMatch))
        assertEquals(
            FriendLifeSendStep.CONFIRM_2,
            friendLifeSendDecision(FriendLifeSendStep.CONFIRM_1, null).nextStep,
        )
    }

    @Test fun confirmTwoNoMatchSkipsTapAndCompletesSendSequence() {
        val noMatch = TemplateMatch(0.1, false, 640, 402, 200, 100)
        assertNull(FriendLifeConfirmPolicy.centerIfMatched(noMatch))
        assertEquals(
            FriendLifeSendStep.COMPLETE,
            friendLifeSendDecision(FriendLifeSendStep.CONFIRM_2, null).nextStep,
        )
    }
}
