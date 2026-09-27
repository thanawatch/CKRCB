package com.example.cookierunbot.notifications

import com.example.cookierunbot.update.UpdateDescriptor
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramApkDeliveryTest {
    private val release = UpdateDescriptor(
        "com.example.cookierunbot", 5, "1.1.3",
        "https://example.org/RobotRun-v1.1.3.apk", "ab".repeat(32), "Bug fixes",
    )

    @Test fun onlyFilesBelowTelegramUploadLimitAreEligible() {
        assertFalse(TelegramApkDeliveryPolicy.canSend(0))
        assertTrue(TelegramApkDeliveryPolicy.canSend(49_000_000L))
        assertFalse(TelegramApkDeliveryPolicy.canSend(49_000_001L))
        assertEquals("RobotRun-v1.1.3.apk", TelegramApkDeliveryPolicy.fileName(release))
    }

    @Test fun repeatDeliveryCooldownAppliesOnlyToSameVerifiedRelease() {
        val now = 600_000L
        assertTrue(TelegramApkDeliveryPolicy.recentlyDelivered(
            now, 500_000L, release, release.sha256,
        ))
        assertFalse(TelegramApkDeliveryPolicy.recentlyDelivered(
            now, 500_000L, release, "cd".repeat(32),
        ))
        assertFalse(TelegramApkDeliveryPolicy.recentlyDelivered(
            now, 0L, release, release.sha256,
        ))
        assertFalse(TelegramApkDeliveryPolicy.recentlyDelivered(
            now + TelegramApkDeliveryPolicy.REPEAT_DELIVERY_COOLDOWN_MS,
            now, release, release.sha256,
        ))
    }

    @Test fun deliveryIdentityIncludesAuthorizedChatAndBot() {
        val primary = TelegramCommandReplyTarget.from(
            TelegramConfig(enabled = true, botToken = "a-token", chatId = "100"),
        )
        val same = TelegramCommandReplyTarget.from(
            TelegramConfig(enabled = true, botToken = "a-token", chatId = "100"),
        )
        val anotherChat = TelegramCommandReplyTarget.from(
            TelegramConfig(enabled = true, botToken = "a-token", chatId = "200"),
        )
        val anotherBot = TelegramCommandReplyTarget.from(
            TelegramConfig(enabled = true, botToken = "b-token", chatId = "100"),
        )
        assertTrue(primary.sameDestination(same))
        assertFalse(primary.sameDestination(anotherChat))
        assertFalse(primary.sameDestination(anotherBot))
    }

    @Test fun multipartStreamsOriginalApkBytesAsDocumentWithMarkup() {
        val content = byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte(), 0x21)
        val file = Files.createTempFile("robotrun-test-apk-", ".apk").toFile()
        try {
            file.writeBytes(content)
            val output = ByteArrayOutputStream()
            writeTelegramApkMultipart(
                output, "TestBoundary", "-100123", file,
                "RobotRun-v1.1.3.apk", "Verified update",
                TelegramDashboardPolicy.keyboardJson,
            )
            val bytes = output.toByteArray()
            val text = String(bytes, StandardCharsets.ISO_8859_1)
            assertTrue(text.contains("name=\"document\"; filename=\"RobotRun-v1.1.3.apk\""))
            assertFalse(text.contains("name=\"photo\""))
            assertTrue(text.contains("Content-Type: application/vnd.android.package-archive\r\n\r\n"))
            assertTrue(text.contains("name=\"chat_id\"\r\n\r\n-100123"))
            assertTrue(text.contains("name=\"reply_markup\""))
            val marker = "Content-Type: application/vnd.android.package-archive\r\n\r\n"
                .toByteArray(StandardCharsets.UTF_8)
            val start = bytes.indexOfBytes(marker) + marker.size
            assertTrue(start >= marker.size)
            assertArrayEquals(content, bytes.copyOfRange(start, start + content.size))
            assertTrue(String(bytes.copyOfRange(start + content.size, bytes.size), StandardCharsets.UTF_8)
                == "\r\n--TestBoundary--\r\n")
        } finally {
            file.delete()
        }
    }

    private fun ByteArray.indexOfBytes(needle: ByteArray): Int {
        for (i in 0..size - needle.size) {
            if (needle.indices.all { this[i + it] == needle[it] }) return i
        }
        return -1
    }
}
