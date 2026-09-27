package com.example.cookierunbot.notifications

import com.example.cookierunbot.update.UpdateDescriptor
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Explicit user-requested APK delivery. Telegram's public Bot API permits file
 * uploads up to 50 MB; leave headroom for transport and use a separate download
 * directory so the in-app installer's verified APK cannot be overwritten.
 */
internal object TelegramApkDeliveryPolicy {
    const val MAX_FILE_BYTES = 49_000_000L
    const val REPEAT_DELIVERY_COOLDOWN_MS = 5 * 60 * 1000L

    fun fileName(candidate: UpdateDescriptor): String = "RobotRun-v${candidate.versionName}.apk"

    fun canSend(size: Long): Boolean = size in 1L..MAX_FILE_BYTES

    fun recentlyDelivered(
        nowMs: Long,
        lastDeliveredAtMs: Long,
        candidate: UpdateDescriptor,
        lastDeliveredSha256: String?,
    ): Boolean = lastDeliveredAtMs > 0L && nowMs >= lastDeliveredAtMs &&
        nowMs - lastDeliveredAtMs < REPEAT_DELIVERY_COOLDOWN_MS &&
        lastDeliveredSha256 == candidate.sha256
}

/** Streams the verified file, never buffering the entire APK in RAM. */
internal fun writeTelegramApkMultipart(
    output: OutputStream,
    boundary: String,
    chatId: String,
    file: File,
    filename: String,
    caption: String,
    replyMarkup: String?,
) {
    require(file.isFile && TelegramApkDeliveryPolicy.canSend(file.length())) {
        "APK is missing or exceeds Telegram's upload limit."
    }
    fun write(value: String) = output.write(value.toByteArray(StandardCharsets.UTF_8))
    fun field(name: String, value: String) {
        write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
    }
    field("chat_id", chatId)
    field("caption", caption)
    if (replyMarkup != null) field("reply_markup", replyMarkup)
    write("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"$filename\"\r\n")
    write("Content-Type: application/vnd.android.package-archive\r\n\r\n")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.IOException("APK upload cancelled.")
            val size = input.read(buffer)
            if (size < 0) break
            output.write(buffer, 0, size)
        }
    }
    write("\r\n--$boundary--\r\n")
}
