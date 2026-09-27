package com.example.cookierunbot.notifications

import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Serialize one original PNG file as a Telegram sendDocument multipart upload.
 * The source png bytes are copied verbatim; only multipart headers/fields use UTF-8.
 */
internal fun writeScreenshotDocumentMultipart(
    output: OutputStream,
    boundary: String,
    chatId: String,
    png: ByteArray,
    filename: String,
    caption: String,
    replyMarkup: String?,
) {
    fun write(value: String) = output.write(value.toByteArray(StandardCharsets.UTF_8))
    fun field(name: String, value: String) {
        write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
    }

    field("chat_id", chatId)
    field("caption", caption)
    if (replyMarkup != null) field("reply_markup", replyMarkup)
    write("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"$filename\"\r\n")
    write("Content-Type: image/png\r\n\r\n")
    output.write(png)
    write("\r\n--$boundary--\r\n")
}
