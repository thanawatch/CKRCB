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
    fun randomJumpAndSlideIntervalsUseIndependentMillisecondRanges() {
        val options = BotOptions(
            jumpMode = JumpMode.CONTINUOUS,
            tapIntervalMs = 500,
            tapIntervalMaxMs = 1500,
            slideMode = SlideMode.CONTINUOUS,
            slideIntervalMs = 1000,
            slideIntervalMaxMs = 2500,
        )
        assertEquals(500, options.tapIntervalMs)
        assertEquals(1500, options.tapIntervalMaxMs)
        assertEquals(1000, options.slideIntervalMs)
        assertEquals(2500, options.slideIntervalMaxMs)

        repeat(100) {
            val jumpDelay = randomAutoActionIntervalMs(500, 1500)
            val slideDelay = randomAutoActionIntervalMs(1000, 2500)
            org.junit.Assert.assertTrue(jumpDelay in 500L..1500L)
            org.junit.Assert.assertTrue(slideDelay in 1000L..2500L)
        }
        assertEquals(250L, randomAutoActionIntervalMs(250, 250))
    }

    @Test
    fun randomIntervalsRejectInvalidRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            BotOptions(tapIntervalMs = 500, tapIntervalMaxMs = 499)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BotOptions(slideIntervalMs = 10_001, slideIntervalMaxMs = 10_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            randomAutoActionIntervalMs(1000, 500)
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
    fun antiBotImageDeliveryIsExplicitOptIn() {
        assertEquals(false, BotOptions().sendAntiBotImagesToTelegram)
        assertEquals(true, BotOptions(sendAntiBotImagesToTelegram = true).sendAntiBotImagesToTelegram)
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
