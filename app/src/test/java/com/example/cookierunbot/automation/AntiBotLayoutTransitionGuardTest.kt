package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiBotLayoutTransitionGuardTest {
    @Test
    fun postTestStateFollowsTestsOneAndTwoThenWaitsForExitAfterThree() {
        assertEquals(AntiBotPostTestState(true, false), antiBotPostTestState(1))
        assertEquals(AntiBotPostTestState(true, false), antiBotPostTestState(2))
        assertEquals(AntiBotPostTestState(false, true), antiBotPostTestState(3))
        assertEquals(AntiBotPostTestState(false, false), antiBotPostTestState(0))
        assertEquals(AntiBotPostTestState(false, false), antiBotPostTestState(4))
    }

    @Test
    fun uniformBrightnessShiftDoesNotBecomeNewLayout() {
        val before = layoutFrame(cardBase = 70)
        val after = before.copyOf().also { pixels ->
            for (index in pixels.indices) {
                val value = (pixels[index].toInt() and 0xff)
                pixels[index] = (value + 20).coerceAtMost(255).toByte()
            }
        }

        val a = requireNotNull(AntiBotCardLayoutFingerprinting.sample(before, 1280, 720))
        val b = requireNotNull(AntiBotCardLayoutFingerprinting.sample(after, 1280, 720))

        assertFalse(AntiBotCardLayoutFingerprinting.isMeaningfullyDifferent(a, b))
    }

    @Test
    fun changedCardArtworkBecomesNewLayout() {
        val before = layoutFrame(cardBase = 70)
        val after = before.copyOf()
        paintCardInterior(after, cardIndex = 2) { x, y ->
            if ((x + y) % 2 == 0) 20 else 230
        }

        val a = requireNotNull(AntiBotCardLayoutFingerprinting.sample(before, 1280, 720))
        val b = requireNotNull(AntiBotCardLayoutFingerprinting.sample(after, 1280, 720))

        assertTrue(AntiBotCardLayoutFingerprinting.isMeaningfullyDifferent(a, b))
    }

    @Test
    fun newLayoutMustBeStableAcrossTwoFreshSamples() {
        val guard = AntiBotLayoutTransitionGuard(requiredStableSamples = 2)
        val baseline = requireNotNull(
            AntiBotCardLayoutFingerprinting.sample(layoutFrame(cardBase = 70), 1280, 720),
        )
        val changedFrame = layoutFrame(cardBase = 70).also { pixels ->
            paintCardInterior(pixels, cardIndex = 4) { x, y ->
                if ((x / 8 + y / 8) % 2 == 0) 30 else 220
            }
        }
        val changed = requireNotNull(
            AntiBotCardLayoutFingerprinting.sample(changedFrame, 1280, 720),
        )

        guard.recordAccepted(baseline)

        assertEquals(
            AntiBotLayoutDecision.WAIT_FOR_STABLE_NEW_LAYOUT,
            guard.evaluateFollowUp(changed),
        )
        assertEquals(
            AntiBotLayoutDecision.ACCEPT_NEW_LAYOUT,
            guard.evaluateFollowUp(changed),
        )
    }

    @Test
    fun sameAcceptedLayoutClearsPendingTransition() {
        val guard = AntiBotLayoutTransitionGuard(requiredStableSamples = 2)
        val baseline = requireNotNull(
            AntiBotCardLayoutFingerprinting.sample(layoutFrame(cardBase = 70), 1280, 720),
        )
        val changedFrame = layoutFrame(cardBase = 70).also { pixels ->
            paintCardInterior(pixels, cardIndex = 1) { x, y ->
                if ((x + y) % 3 == 0) 25 else 225
            }
        }
        val changed = requireNotNull(
            AntiBotCardLayoutFingerprinting.sample(changedFrame, 1280, 720),
        )
        guard.recordAccepted(baseline)

        assertEquals(
            AntiBotLayoutDecision.WAIT_FOR_STABLE_NEW_LAYOUT,
            guard.evaluateFollowUp(changed),
        )
        assertEquals(
            AntiBotLayoutDecision.SAME_ACCEPTED_LAYOUT,
            guard.evaluateFollowUp(baseline),
        )
        assertEquals(
            AntiBotLayoutDecision.WAIT_FOR_STABLE_NEW_LAYOUT,
            guard.evaluateFollowUp(changed),
        )
    }

    private fun layoutFrame(cardBase: Int): ByteArray {
        val frame = ByteArray(1280 * 720) { 10.toByte() }
        repeat(6) { card ->
            paintCardInterior(frame, card) { x, y ->
                (cardBase + card * 9 + (x / 11) + (y / 13)).coerceAtMost(240)
            }
        }
        return frame
    }

    private fun paintCardInterior(
        frame: ByteArray,
        cardIndex: Int,
        valueAt: (Int, Int) -> Int,
    ) {
        val origins = listOf(
            346 to 179, 542 to 179, 738 to 179,
            346 to 435, 542 to 435, 738 to 435,
        )
        val (originX, originY) = origins[cardIndex]
        for (y in originY + 30 until originY + 241 - 30) {
            for (x in originX + 28 until originX + 183 - 28) {
                frame[y * 1280 + x] = valueAt(x - originX, y - originY)
                    .coerceIn(0, 255)
                    .toByte()
            }
        }
    }
}
