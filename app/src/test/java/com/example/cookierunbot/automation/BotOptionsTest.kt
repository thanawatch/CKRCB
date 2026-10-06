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

        val slide = BotOptions(slideMode = SlideMode.FIXED_COUNT, fixedSlideCount = 7)
        assertEquals(SlideMode.FIXED_COUNT, slide.slideMode)
        assertEquals(7, slide.fixedSlideCount)
    }

    @Test
    fun invalidTapIntervalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BotOptions(jumpMode = JumpMode.CONTINUOUS, tapIntervalMs = 99)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BotOptions(slideMode = SlideMode.CONTINUOUS, slideIntervalMs = 99)
        }
    }

    @Test
    fun slideTargetMatchesNormalizedSlideButtonLabel() {
        assertEquals(LogicalPoint(1115, 619), AutomationConfig.AUTO_SLIDE)
        assertEquals(100.0, AutomationConfig.AUTO_SLIDE_PHONE_RADIUS, 0.0)
    }

    @Test
    fun fixedSlideRequiresPositiveCount() {
        assertThrows(IllegalArgumentException::class.java) {
            BotOptions(slideMode = SlideMode.FIXED_COUNT, fixedSlideCount = 0)
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
    @Test
    fun friendLifeMinuteIntervalDefaultsToPreviousSixtyOneMinutes() {
        assertEquals(61, BotOptions().friendLifeIntervalMinutes)
        assertEquals(61, BotOptionsStore.friendLifeIntervalMinutesFromStoredValue(61))
        assertEquals(61, BotOptionsStore.friendLifeIntervalMinutesFromStoredValue(0))
        assertEquals(61, BotOptionsStore.friendLifeIntervalMinutesFromStoredValue(1441))
        assertEquals(61, BotOptionsStore.friendLifeIntervalMinutesFromStoredValue(-15))
    }

    @Test
    fun friendLifeIntervalSupportsMinutePrecisionIncludingBounds() {
        assertEquals(1, BotOptions(friendLifeIntervalMinutes = 1).friendLifeIntervalMinutes)
        assertEquals(5, BotOptions(friendLifeIntervalMinutes = 5).friendLifeIntervalMinutes)
        assertEquals(1440, BotOptions(friendLifeIntervalMinutes = 1440).friendLifeIntervalMinutes)
        assertEquals(5, BotOptionsStore.friendLifeIntervalMinutesFromStoredValue(5))
        assertThrows(IllegalArgumentException::class.java) { BotOptions(friendLifeIntervalMinutes = 0) }
        assertThrows(IllegalArgumentException::class.java) { BotOptions(friendLifeIntervalMinutes = 1441) }
    }

    @Test
    fun disabledFriendLifeCanRetainSelectedMinutesForLaterStart() {
        val settings = BotOptions(sendFriendLife = false, friendLifeIntervalMinutes = 7)
        assertEquals(false, settings.sendFriendLife)
        assertEquals(7, settings.friendLifeIntervalMinutes)
    }

    @Test
    fun quickLivesIntervalDefaultsToOneMinuteAndStoredValuesAreSanitized() {
        assertEquals(1, BotOptions().quickLivesIntervalMinutes)
        assertEquals(1, BotOptionsStore.quickLivesIntervalMinutesFromStoredValue(1))
        assertEquals(5, BotOptionsStore.quickLivesIntervalMinutesFromStoredValue(5))
        assertEquals(1440, BotOptionsStore.quickLivesIntervalMinutesFromStoredValue(1440))
        assertEquals(1, BotOptionsStore.quickLivesIntervalMinutesFromStoredValue(0))
        assertEquals(1, BotOptionsStore.quickLivesIntervalMinutesFromStoredValue(1441))
    }

    @Test
    fun quickLivesIntervalSupportsMinutePrecisionAndBounds() {
        assertEquals(
            5,
            BotOptions(
                receiveSendLivesAfterEachGame = true,
                quickLivesIntervalMinutes = 5,
            ).quickLivesIntervalMinutes,
        )
        assertEquals(300_000L, quickLivesIntervalMs(5))
        assertThrows(IllegalArgumentException::class.java) {
            BotOptions(quickLivesIntervalMinutes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BotOptions(quickLivesIntervalMinutes = 1441)
        }
    }

    @Test
    fun disabledQuickLivesCanRetainSelectedMinutesForLaterStart() {
        val settings = BotOptions(
            receiveSendLivesAfterEachGame = false,
            quickLivesIntervalMinutes = 30,
        )
        assertEquals(false, settings.receiveSendLivesAfterEachGame)
        assertEquals(30, settings.quickLivesIntervalMinutes)
    }

}
