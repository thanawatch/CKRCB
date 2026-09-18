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
}
