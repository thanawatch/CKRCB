package com.example.cookierunbot.notifications

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class TelegramLogDeliveryTest {
    @Test fun sendDocumentPreservesExactZipBytes() {
        val directory = Files.createTempDirectory("robotrun-logs-test").toFile()
        try {
            val zip = File(directory, "RobotRun-Logs-20260928.zip")
            val payload = byteArrayOf(0, 1, 2, 3, -128, 127, 0, 42)
            zip.writeBytes(payload)
            val out = ByteArrayOutputStream()
            writeTelegramLogMultipart(out, "boundary", "123", zip, { true }, null)
            val bytes = out.toByteArray()
            assertTrue(String(bytes, Charsets.ISO_8859_1).contains("application/zip"))
            val marker = "\r\n\r\n".toByteArray()
            val preface = bytes.indices.firstOrNull { start ->
                start + marker.size <= bytes.size && bytes.copyOfRange(start, start + marker.size)
                    .contentEquals(marker) &&
                    String(bytes, 0, start, Charsets.ISO_8859_1).contains("filename=")
            } ?: error("No document boundary found")
            assertArrayEquals(payload, bytes.copyOfRange(preface + marker.size, preface + marker.size + payload.size))
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsUnauthorizedUpload() {
        val directory = Files.createTempDirectory("robotrun-logs-denied").toFile()
        try {
            val file = File(directory, "RobotRun-Logs.zip").apply { writeText("test") }
            val out = ByteArrayOutputStream()
            var rejected = false
            try {
                writeTelegramLogMultipart(out, "boundary", "123", file, { false }, null)
            } catch (_: IOException) { rejected = true }
            assertTrue(rejected)
            assertFalse(String(out.toByteArray()).contains("test"))
        } finally { directory.deleteRecursively() }
    }
}
