package com.example.cookierunbot.notifications

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramScreenshotMultipartTest {
    @Test fun documentUploadKeepsExactPngBytesAndDashboard() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0xff.toByte(), 0x10)
        val output = ByteArrayOutputStream()
        writeScreenshotDocumentMultipart(
            output,
            boundary = "RobotRunTestBoundary",
            chatId = "12345",
            png = png,
            filename = "robotrun_screen.png",
            caption = "RobotRun screenshot",
            replyMarkup = """{"keyboard":[[{"text":"📊 All"}]]}""",
        )
        val payload = output.toByteArray()
        val header = String(payload, StandardCharsets.ISO_8859_1)
        assertTrue(header.contains("name=\"document\"; filename=\"robotrun_screen.png\""))
        assertTrue(header.contains("Content-Type: image/png\r\n\r\n"))
        assertTrue(header.contains("name=\"chat_id\"\r\n\r\n12345"))
        assertTrue(header.contains("name=\"reply_markup\""))
        assertTrue(payload.containsSequence("📊 All".toByteArray(StandardCharsets.UTF_8)))
        assertFalse(header.contains("name=\"photo\""))

        val marker = "Content-Type: image/png\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
        val start = payload.indexOfSequence(marker) + marker.size
        assertTrue(start >= marker.size)
        assertArrayEquals(png, payload.copyOfRange(start, start + png.size))
        val trailer = String(payload.copyOfRange(start + png.size, payload.size), StandardCharsets.UTF_8)
        assertTrue(trailer == "\r\n--RobotRunTestBoundary--\r\n")
    }

    @Test fun optionalMarkupIsNotSentWhenAbsent() {
        val output = ByteArrayOutputStream()
        writeScreenshotDocumentMultipart(
            output, "Boundary2", "-1001234", byteArrayOf(1, 2, 3),
            "robotrun_screen.png", "Screenshot", null,
        )
        val payload = String(output.toByteArray(), StandardCharsets.ISO_8859_1)
        assertTrue(payload.contains("name=\"document\""))
        assertFalse(payload.contains("name=\"photo\""))
        assertFalse(payload.contains("name=\"reply_markup\""))
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        for (start in 0..(size - needle.size)) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        return -1
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean = indexOfSequence(needle) >= 0
}
