package com.example.cookierunbot.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BotOptionsTest {
    @Test
    fun fixedAndContinuousModesAreRepresentedExclusively() {
        val fixed = BotOptions(jumpMode = JumpMode.FIXED_COUNT, fixedJumpCount = 10)
        assertEquals(JumpMode.FIXED_COUNT, fixed.jumpMode)
        assertEquals(10, fixed.fixedJumpCount)
    }

    @Test
    fun invalidTapIntervalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BotOptions(jumpMode = JumpMode.CONTINUOUS, tapIntervalMs = 99)
        }
    }

    @Test
    fun postRelayRequiresCookieRelay() {
        assertThrows(IllegalArgumentException::class.java) {
            BotOptions(postCookieRelaySequence = true)
        }
    }

    @Test
    fun localImageSavingChoicesAreIndependent() {
        val defaults = BotOptions()
        assertEquals(true, defaults.saveDebugImages)
        assertEquals(true, defaults.saveResultImages)

        val noMatchOff = defaults.copy(saveDebugImages = false)
        assertEquals(false, noMatchOff.saveDebugImages)
        assertEquals(true, noMatchOff.saveResultImages)

        val resultsOff = defaults.copy(saveResultImages = false)
        assertEquals(true, resultsOff.saveDebugImages)
        assertEquals(false, resultsOff.saveResultImages)
    }

    @Test
    fun performanceProfileDefaultsToLegacyAndCanBeSelected() {
        assertEquals(PerformanceProfile.LEGACY, BotOptions().performanceProfile)
        assertEquals(
            PerformanceProfile.REDFINGER_OPTIMIZED,
            BotOptions(performanceProfile = PerformanceProfile.REDFINGER_OPTIMIZED).performanceProfile,
        )
    }

    @Test
    fun storedPerformanceProfileSafelyDefaultsAndRoundTripsSelection() {
        assertEquals(
            PerformanceProfile.LEGACY,
            BotOptionsStore.performanceProfileFromStoredValue(null),
        )
        assertEquals(
            PerformanceProfile.LEGACY,
            BotOptionsStore.performanceProfileFromStoredValue("UNKNOWN_FUTURE_PROFILE"),
        )
        assertEquals(
            PerformanceProfile.REDFINGER_OPTIMIZED,
            BotOptionsStore.performanceProfileFromStoredValue("REDFINGER_OPTIMIZED"),
        )
    }
}
