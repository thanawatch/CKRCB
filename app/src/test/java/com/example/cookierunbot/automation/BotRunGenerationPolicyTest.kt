package com.example.cookierunbot.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRunGenerationPolicyTest {
    @Test fun oldWorkerCannotJoinNewRun() {
        assertTrue(BotRunGenerationPolicy.isActive(true, 1L, 1L))
        assertFalse(BotRunGenerationPolicy.isActive(false, 2L, 1L))
        assertFalse(BotRunGenerationPolicy.isActive(true, 3L, 1L))
        assertTrue(BotRunGenerationPolicy.isActive(true, 3L, 3L))
    }

    @Test fun externalStatusReadsCurrentBotState() {
        assertTrue(BotRunGenerationPolicy.isActive(true, 3L, null))
        assertFalse(BotRunGenerationPolicy.isActive(false, 3L, null))
    }
}
