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

    @Test fun exportedTextFilesUseTxtWithoutChangingOtherEvidence() {
        assertEquals("robotrun_10h.txt", OvernightLogPolicy.exportedFileName("robotrun_10h.log"))
        assertEquals("before_30_minutes.txt", OvernightLogPolicy.exportedFileName("before_30_minutes.log"))
        assertEquals("watchdog_action_timeline.txt", OvernightLogPolicy.exportedFileName("watchdog_action_timeline.LOG"))
        assertEquals("incident_status.txt", OvernightLogPolicy.exportedFileName("incident_status.txt"))
        assertEquals("watchdog_screenshot.png", OvernightLogPolicy.exportedFileName("watchdog_screenshot.png"))
        assertEquals("incident_video.mp4", OvernightLogPolicy.exportedFileName("incident_video.mp4"))
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
