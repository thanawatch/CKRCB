package com.example.cookierunbot.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupInstanceGuardTest {
    @Test
    fun identicalPopupIsSameInstance() {
        val guard = PopupInstanceGuard(3)
        val popup = fingerprint(80)
        guard.record(BotStage.CONGRATULATIONS, popup)

        val decision = guard.evaluate(BotStage.CONGRATULATIONS, popup.copyPixels())

        assertTrue(decision.sameInstance)
        assertTrue(decision.allowed)
    }

    @Test
    fun differentCongratulationsContentIsNewInstance() {
        val guard = PopupInstanceGuard(3)
        guard.record(BotStage.CONGRATULATIONS, fingerprint(80))

        val decision = guard.evaluate(BotStage.CONGRATULATIONS, fingerprint(130))

        assertFalse(decision.sameInstance)
        assertTrue(decision.allowed)
    }

    @Test
    fun backgroundOnlyChangeDoesNotChangeDialogFingerprint() {
        val region = PopupFingerprinting.regionFor(BotStage.CONGRATULATIONS)
        val before = grayFrame(background = 20, dialog = 90, region = region)
        val after = grayFrame(background = 220, dialog = 90, region = region)

        val beforeFingerprint = requireNotNull(PopupFingerprinting.sample(before, 1280, 720, region))
        val afterFingerprint = requireNotNull(PopupFingerprinting.sample(after, 1280, 720, region))

        assertFalse(PopupFingerprinting.isMeaningfullyDifferent(beforeFingerprint, afterFingerprint))
    }

    @Test
    fun moreThanThreeDistinctCongratulationsInstancesRemainAllowed() {
        val guard = PopupInstanceGuard(3)
        for (instance in 1..10) {
            val candidate = fingerprint(20 + instance * 20)
            val decision = guard.evaluate(BotStage.CONGRATULATIONS, candidate)
            assertTrue("instance=$instance", decision.allowed)
            assertFalse("instance=$instance", decision.sameInstance)
            guard.record(BotStage.CONGRATULATIONS, candidate)
        }
    }

    private fun fingerprint(value: Int) =
        PopupFingerprint(ByteArray(PopupFingerprinting.WIDTH * PopupFingerprinting.HEIGHT) { value.toByte() })

    private fun PopupFingerprint.copyPixels() = PopupFingerprint(pixels.copyOf())

    private fun grayFrame(
        background: Int,
        dialog: Int,
        region: FingerprintRegion,
    ): ByteArray = ByteArray(1280 * 720) { index ->
        val x = index % 1280
        val y = index / 1280
        if (x in region.left until region.right && y in region.top until region.bottom) {
            dialog.toByte()
        } else {
            background.toByte()
        }
    }
}
