package com.example.cookierunbot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdatePolicyTest {
    @Test fun acceptsOnlyHttpsWithoutEmbeddedCredentials() {
        assertEquals("https", UpdatePolicy.secureUrl("https://updates.example.com/update.json").protocol)
        listOf(
            "http://updates.example.com/update.json",
            "https://user:password@updates.example.com/update.json",
            "https://updates.example.com/update.json#fragment",
            "file:///tmp/update.json", "",
        ).forEach { invalid ->
            try { UpdatePolicy.secureUrl(invalid); fail("Accepted unsafe URL") }
            catch (_: IllegalArgumentException) { }
            catch (_: java.net.URISyntaxException) { }
        }
    }

    @Test fun requiresValidPackageVersionAndChecksum() {
        val update = UpdatePolicy.descriptor(
            "com.example.cookierunbot", 3L, "1.1.1",
            "https://downloads.example.com/RobotRun-v1.1.1.apk",
            "ab".repeat(32), "Bug fixes",
        )
        assertEquals(3L, update.versionCode)
        assertEquals("ab".repeat(32), update.sha256)
        assertTrue(UpdatePolicy.hasNewerVersion(2L, 3L))
        assertFalse(UpdatePolicy.hasNewerVersion(2L, 2L))
        assertFalse(UpdatePolicy.hasNewerVersion(2L, 1L))
        try {
            UpdatePolicy.descriptor("com.example.cookierunbot", 3L, "1.1.1",
                "https://downloads.example.com/robotrun.apk", "not-a-sha", "")
            fail("Missing checksum must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun knownSha256IsStableAndDownloadsAreBounded() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdatePolicy.sha256("abc".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(250L * 1024L * 1024L, UpdatePolicy.MAX_APK_BYTES)
    }
}
