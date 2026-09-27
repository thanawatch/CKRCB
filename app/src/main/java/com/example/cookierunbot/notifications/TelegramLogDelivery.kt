package com.example.cookierunbot.notifications

import com.example.cookierunbot.diagnostics.OvernightLogPolicy
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Streams a completed ZIP as a Document. Authorization is rechecked between chunks. */
internal fun writeTelegramLogMultipart(
    output: OutputStream,
    boundary: String,
    chatId: String,
    archive: File,
    stillAuthorized: () -> Boolean,
    replyMarkup: String?,
) {
    require(archive.isFile && archive.length() in 1L..OvernightLogPolicy.TELEGRAM_MAX_BYTES)
    require(archive.name.matches(Regex("[A-Za-z0-9._-]+[.]zip")))
    fun write(value: String) = output.write(value.toByteArray(StandardCharsets.UTF_8))
    fun field(name: String, value: String) {
        write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
    }
    if (!stillAuthorized()) throw IOException("Authorized Telegram destination changed.")
    field("chat_id", chatId)
    field("caption", "RobotRun diagnostic archive (last 24 hours, up to five incidents). Keep private.")
    if (replyMarkup != null) field("reply_markup", replyMarkup)
    write("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${archive.name}\"\r\n")
    write("Content-Type: application/zip\r\n\r\n")
    archive.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            if (!stillAuthorized() || Thread.currentThread().isInterrupted) {
                throw IOException("Log upload cancelled or authorization changed.")
            }
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }
    write("\r\n--$boundary--\r\n")
}
