package com.example.cookierunbot.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramSecureTokenMigrationTest {
    @Test fun plaintextTokenMigratesAndIsRemovedOnlyAfterSecureWrite() {
        var removed = false
        var written = ""
        val token = migrateTelegramToken(null, "old-secret", { written = it; true }) { removed = true }
        assertEquals("old-secret", token)
        assertEquals("old-secret", written)
        assertTrue(removed)
    }

    @Test fun failedSecureWriteDoesNotRemovePlaintext() {
        var removed = false
        val token = migrateTelegramToken(null, "old-secret", { false }) { removed = true }
        assertEquals("", token)
        assertFalse(removed)
    }

    @Test fun existingSecureTokenWinsAndLegacyPlaintextIsRemoved() {
        var removed = false
        var wrote = false
        val token = migrateTelegramToken("secure-secret", "old-secret", { wrote = true; true }) { removed = true }
        assertEquals("secure-secret", token)
        assertFalse(wrote)
        assertTrue(removed)
    }
}
