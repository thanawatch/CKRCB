package com.example.cookierunbot.notifications

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelegramDiagnosticImageGateTest {
    @Before fun setUp() = TelegramDiagnosticImageGate.resetForTest()

    @After fun tearDown() = TelegramDiagnosticImageGate.resetForTest()

    @Test fun duplicateImageBurstIsSuppressedWithoutAffectingLaterImage() {
        assertTrue(TelegramDiagnosticImageGate.tryAcquire(100_000L))
        assertFalse(TelegramDiagnosticImageGate.tryAcquire(100_000L))
        assertFalse(TelegramDiagnosticImageGate.tryAcquire(114_999L))
        assertTrue(TelegramDiagnosticImageGate.tryAcquire(115_000L))
    }
}
