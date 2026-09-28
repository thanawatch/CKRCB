package com.example.cookierunbot.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OvernightLogPolicyTest {
    @Test fun rollingWindowKeepsOnlyLastTenHours() {
        val now = 200_000_000L
        assertEquals(10L * 60L * 60L * 1000L, OvernightLogPolicy.RETENTION_MS)
        assertTrue(OvernightLogPolicy.shouldKeep(now - OvernightLogPolicy.RETENTION_MS, now))
        assertFalse(OvernightLogPolicy.shouldKeep(now - OvernightLogPolicy.RETENTION_MS - 1L, now))
        assertFalse(OvernightLogPolicy.shouldKeep(now + 1L, now))
        assertTrue(OvernightLogPolicy.inWindow(now - 30_000, now - 30_000, now))
    }

    @Test fun redactionAndEpochParsing() {
        val clean = OvernightLogPolicy.sanitized(
            "https://api.telegram.org/bot123456:SECRET_abc/sendMessage token=abc\nNEXT"
        )
        assertFalse(clean.contains("SECRET_abc"))
        assertFalse(clean.contains("token=abc"))
        assertEquals(123L, OvernightLogPolicy.lineTimestamp("[123] [INFO] example"))
        assertEquals(null, OvernightLogPolicy.lineTimestamp("bad line"))
    }
}
