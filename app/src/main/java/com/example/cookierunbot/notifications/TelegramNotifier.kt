package com.example.cookierunbot.notifications

import android.content.Context
import android.util.Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.diagnostics.DebugLogStore
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

data class TelegramSendResult(
    val success: Boolean,
    val reason: String,
    val httpCode: Int? = null,
)

internal fun shouldSendTelegramText(config: TelegramConfig, manualTest: Boolean): Boolean =
    manualTest || config.sendTextNotifications

class TelegramNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "telegram-notifications")
    }

    fun sendText(text: String) = enqueue { config -> sendTextBlocking(config, text, manualTest = false) }

    fun sendTestMessage(text: String, callback: (TelegramSendResult) -> Unit) {
        val config = TelegramConfigStore.load(appContext)
        logConfig(config)
        if (!config.enabled || !config.isValid) {
            val reason = if (!config.enabled) "disabled" else "invalid_config:${config.invalidReason}"
            val result = TelegramSendResult(false, reason)
            log("[TELEGRAM_SEND] success=false reason=$reason method=sendMessage test=true")
            callback(result)
            return
        }
        executor.execute {
            callback(sendTextBlocking(config, text, manualTest = true))
        }
    }

    fun sendPhoto(png: ByteArray, filename: String, caption: String) = enqueue { config ->
        if (!config.sendDebugImages) {
            log("[TELEGRAM_SEND] skipped=true reason=debug_images_disabled")
        } else {
            sendPhotoBlocking(config, png, filename, caption)
        }
    }

    fun notifyNoMatch(normalizedPng: ByteArray, caption: String): Boolean =
        notifyDiagnosticImage(normalizedPng, "no_match_normalized.png", caption)

    fun notifyDiagnosticImage(
        normalizedPng: ByteArray,
        filename: String,
        caption: String,
        callback: (TelegramSendResult) -> Unit = {},
    ): Boolean {
        val config = TelegramConfigStore.load(appContext)
        logConfig(config)
        if (!config.enabled) return false
        if (!config.isValid) {
            log("[TELEGRAM_SEND] success=false reason=invalid_config detail=${config.invalidReason}")
            return false
        }
        if (!config.sendDebugImages && !config.sendTextNotifications) return false
        return try {
            executor.execute {
                val result = runCatching {
                if (config.sendDebugImages) {
                    sendPhotoBlocking(config, normalizedPng, filename, caption)
                } else {
                    sendTextBlocking(config, caption, manualTest = false)
                }
                }.getOrElse { error ->
                    log("[TELEGRAM_SEND] success=false reason=async_exception type=${error.javaClass.simpleName}")
                    TelegramSendResult(false, "exception:${error.javaClass.simpleName}")
                }
                runCatching { callback(result) }
                    .onFailure { error ->
                        log("[TELEGRAM_CALLBACK] success=false type=${error.javaClass.simpleName}")
                    }
            }
            true
        } catch (error: Exception) {
            log("[TELEGRAM_SEND] success=false reason=queue_rejected type=${error.javaClass.simpleName}")
            false
        }
    }

    fun isDebugImageEnabled(): Boolean = TelegramConfigStore.load(appContext).let { config ->
        config.enabled && config.isValid && config.sendDebugImages
    }

    private fun enqueue(block: (TelegramConfig) -> Unit) {
        val config = TelegramConfigStore.load(appContext)
        logConfig(config)
        if (!config.enabled || !config.isValid) {
            val reason = if (!config.enabled) "disabled" else "invalid_config detail=${config.invalidReason}"
            log("[TELEGRAM_SEND] success=false reason=$reason")
            return
        }
        executor.execute { block(config) }
    }

    private fun sendTextBlocking(
        config: TelegramConfig,
        text: String,
        manualTest: Boolean,
    ): TelegramSendResult {
        if (!shouldSendTelegramText(config, manualTest)) {
            log("[TELEGRAM_SEND] skipped=true reason=text_notifications_disabled")
            return TelegramSendResult(false, "text_notifications_disabled")
        }
        return request(config, "sendMessage", "application/x-www-form-urlencoded") { output ->
            val body = "chat_id=${encode(config.chatId)}&text=${encode(text)}"
            output.write(body.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun sendPhotoBlocking(
        config: TelegramConfig,
        png: ByteArray,
        filename: String,
        caption: String,
    ): TelegramSendResult {
        val boundary = "RobotRunBoundary${System.nanoTime()}"
        return request(config, "sendPhoto", "multipart/form-data; boundary=$boundary") { output ->
            fun field(name: String, value: String) {
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("chat_id", config.chatId)
            field("caption", caption)
            output.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"$filename\"\r\n".toByteArray(),
            )
            output.write("Content-Type: image/png\r\n\r\n".toByteArray())
            output.write(png)
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }
    }

    private fun request(
        config: TelegramConfig,
        method: String,
        contentType: String,
        writeBody: (BufferedOutputStream) -> Unit,
    ): TelegramSendResult {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("https://api.telegram.org/bot${config.botToken}/$method")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            BufferedOutputStream(connection.outputStream).use(writeBody)
            val code = connection.responseCode
            if (code in 200..299) {
                log("[TELEGRAM_SEND] success=true method=$method code=$code")
                return TelegramSendResult(true, "success", code)
            }
            log("[TELEGRAM_SEND] success=false reason=http_error method=$method code=$code")
            return TelegramSendResult(false, "http_error", code)
        } catch (error: Exception) {
            // Exception messages from URLConnection can contain the request URL;
            // never risk exposing the bot token in app logs.
            log("[TELEGRAM_SEND] success=false reason=exception type=${error.javaClass.simpleName}")
            return TelegramSendResult(false, "exception:${error.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun logConfig(config: TelegramConfig) = log(
        "[TELEGRAM_CONFIG] enabled=${config.enabled} valid=${config.isValid} " +
            "hasToken=${config.botToken.isNotBlank()} hasChatId=${config.chatId.isNotBlank()}",
    )

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun log(message: String) {
        Log.i(CookieRunBotApplication.TAG, message)
        DebugLogStore.log(message)
    }
}
