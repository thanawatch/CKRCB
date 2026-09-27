package com.example.cookierunbot.notifications

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.diagnostics.DebugLogStore
import com.example.cookierunbot.update.UpdateFeedClient
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class TelegramSendResult(
    val success: Boolean,
    val reason: String,
    val httpCode: Int? = null,
    val retryAfterSeconds: Long? = null,
)

/** Keep the complete /all summary even if the optional screenshot is unavailable. */
internal fun formatAllWithScreenNote(allReply: String, screenFailure: String?): String =
    if (screenFailure == null) allReply else "$screenFailure\n\n$allReply"

internal const val TELEGRAM_COMMAND_MAX_ATTEMPTS = 3
private const val TELEGRAM_COMMAND_RETRY_1_MS = 2_000L
private const val TELEGRAM_COMMAND_RETRY_2_MS = 5_000L

internal data class TelegramCommandReplyAttempt(
    val replyText: String,
    val attempt: Int = 1,
)

internal class TelegramCommandReplyTarget private constructor(
    private val botTokenIdentity: String,
    private val chatId: String,
) {
    fun matches(config: TelegramConfig): Boolean =
        config.enabled && config.isValid &&
            config.chatId.trim() == chatId &&
            telegramBotTokenIdentity(config.botToken) == botTokenIdentity

    companion object {
        fun from(config: TelegramConfig) = TelegramCommandReplyTarget(
            botTokenIdentity = telegramBotTokenIdentity(config.botToken),
            chatId = config.chatId.trim(),
        )
    }
}

private fun telegramBotTokenIdentity(token: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(token.trim().toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

internal data class TelegramCommandRetryDecision(
    val retry: Boolean,
    val delayMs: Long = 0L,
    val nextAttempt: TelegramCommandReplyAttempt? = null,
)

internal fun telegramCommandRetryDecision(
    current: TelegramCommandReplyAttempt,
    result: TelegramSendResult,
): TelegramCommandRetryDecision {
    if (result.success || current.attempt >= TELEGRAM_COMMAND_MAX_ATTEMPTS ||
        !isTransientTelegramFailure(result)
    ) {
        return TelegramCommandRetryDecision(retry = false)
    }
    val fallback = if (current.attempt == 1) TELEGRAM_COMMAND_RETRY_1_MS else TELEGRAM_COMMAND_RETRY_2_MS
    val telegramDelay = result.retryAfterSeconds?.coerceAtLeast(0L)?.let(::secondsToMillisSafely)
    return TelegramCommandRetryDecision(
        retry = true,
        delayMs = telegramDelay ?: fallback,
        nextAttempt = current.copy(attempt = current.attempt + 1),
    )
}

internal fun isTransientTelegramFailure(result: TelegramSendResult): Boolean = when {
    result.httpCode == 429 -> true
    result.httpCode != null && result.httpCode in 500..599 -> true
    result.reason.startsWith("io_exception:") -> true
    else -> false
}

internal fun parseTelegramRetryAfterSeconds(body: String): Long? =
    RETRY_AFTER_PATTERN.find(body)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }

private val RETRY_AFTER_PATTERN = Regex("\\\"retry_after\\\"\\s*:\\s*(\\d+)")

internal fun secondsToMillisSafely(seconds: Long): Long =
    seconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L

internal fun shouldSendTelegramText(config: TelegramConfig, manualTest: Boolean): Boolean =
    manualTest || config.sendTextNotifications

class TelegramNotifier(context: Context) {
    private val appContext = context.applicationContext
    private var lastOnDemandScreenAtMs = 0L
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
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

    internal fun sendCommandReply(
        text: String,
        target: TelegramCommandReplyTarget,
        callback: (TelegramSendResult) -> Unit = {},
    ) {
        scheduleCommandReply(
            target,
            TelegramCommandReplyAttempt(text),
            0L,
            callback,
        )
    }

    /** Check the same feed configured by UpdateActivity, without remote installation. */
    @Suppress("DEPRECATION")
    internal fun sendCommandUpdate(target: TelegramCommandReplyTarget) {
        try {
            executor.execute {
                if (!target.matches(TelegramConfigStore.load(appContext))) return@execute
                val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                val installedCode = if (Build.VERSION.SDK_INT >= 28) {
                    info.longVersionCode
                } else {
                    info.versionCode.toLong()
                }
                val installedName = info.versionName ?: "unknown"
                val feedUrl = appContext.getSharedPreferences("robotrun_update", Context.MODE_PRIVATE)
                    .getString("https_update_feed", "").orEmpty().trim()
                val reply = if (feedUrl.isBlank()) {
                    formatTelegramUpdateReply(installedName, installedCode, null, false)
                } else {
                    try {
                        val available = UpdateFeedClient(appContext).check(feedUrl)
                        formatTelegramUpdateReply(installedName, installedCode, available, true)
                    } catch (error: Exception) {
                        log("[TELEGRAM_UPDATE] check_failed=true type=${error.javaClass.simpleName}")
                        formatTelegramUpdateReply(installedName, installedCode, null, true, true)
                    }
                }
                sendCommandReply(reply, target)
            }
        } catch (error: Exception) {
            log("[TELEGRAM_UPDATE] queue_rejected=true type=${error.javaClass.simpleName}")
        }
    }

    internal fun sendCommandScreenshot(target: TelegramCommandReplyTarget) {
        try {
            executor.execute {
                if (!target.matches(TelegramConfigStore.load(appContext))) return@execute
                val screenFailure = try {
                    sendOnDemandScreen(target)
                } catch (error: Exception) {
                    log("[TELEGRAM_SCREEN] failed=true type=${error.javaClass.simpleName}")
                    "📸 Screen: unavailable."
                }
                if (screenFailure != null) {
                    val config = TelegramConfigStore.load(appContext)
                    if (target.matches(config)) {
                        sendTextBlocking(config, screenFailure, manualTest = true,
                            replyMarkup = TelegramDashboardPolicy.keyboardJson)
                    }
                }
            }
        } catch (error: Exception) {
            log("[TELEGRAM_SCREEN] queue_rejected=true type=${error.javaClass.simpleName}")
        }
    }

    /** Original-quality PNG document first, then complete /all text. File failure never suppresses stats. */
    internal fun sendCommandAll(replyText: String, target: TelegramCommandReplyTarget) {
        try {
            executor.execute {
                if (!target.matches(TelegramConfigStore.load(appContext))) return@execute
                val screenFailure = try {
                    sendOnDemandScreen(target)
                } catch (error: Exception) {
                    log("[TELEGRAM_ALL_SCREEN] failed=true type=${error.javaClass.simpleName}")
                    "📸 Screen: unavailable."
                }
                scheduleCommandReply(
                    target,
                    TelegramCommandReplyAttempt(formatAllWithScreenNote(replyText, screenFailure)),
                    0L,
                    {},
                )
            }
        } catch (error: Exception) {
            log("[TELEGRAM_ALL] queue_rejected=true type=${error.javaClass.simpleName}")
        }
    }

    private fun sendOnDemandScreen(target: TelegramCommandReplyTarget): String? {
        val config = TelegramConfigStore.load(appContext)
        if (!target.matches(config)) return "📸 Screen: configuration changed."
        val now = SystemClock.elapsedRealtime()
        if (lastOnDemandScreenAtMs > 0L && now - lastOnDemandScreenAtMs < 15_000L) {
            return "📸 Screen: cooldown active; try again shortly."
        }
        lastOnDemandScreenAtMs = now
        if (!LatestFrameProvider.diagnostics().captureRunning) {
            return "📸 Screen: capture is not running."
        }
        val frame = LatestFrameProvider.snapshot()
            ?: return "📸 Screen: no frame available."
        try {
            if (!TelegramDashboardPolicy.screenshotFrameIsFresh(
                    frame.capturedAtNanos, SystemClock.elapsedRealtimeNanos(),
                )
            ) {
                return "📸 Screen: latest frame is stale."
            }
            val png = TelegramScreenshotEncoder.encodeNormalizedPng(frame.mat)
            val currentConfig = TelegramConfigStore.load(appContext)
            if (!target.matches(currentConfig)) return "📸 Screen: configuration changed."
            val result = sendScreenshotDocumentBlocking(
                currentConfig, png, "robotrun_screen.png",
                "📸 RobotRun | normalized 1280x720 | frame ${frame.generation}",
                replyMarkup = TelegramDashboardPolicy.keyboardJson,
            )
            return if (result.success) null else "📸 Screen: document upload failed."
        } finally {
            frame.mat.release()
        }
    }

    private fun scheduleCommandReply(
        target: TelegramCommandReplyTarget,
        state: TelegramCommandReplyAttempt,
        delayMs: Long,
        callback: (TelegramSendResult) -> Unit,
    ) {
        try {
            executor.schedule({
                val currentConfig = TelegramConfigStore.load(appContext)
                if (!target.matches(currentConfig)) {
                    log("[TELEGRAM_COMMAND_REPLY] cancelled=true reason=config_changed")
                    callback(TelegramSendResult(false, "config_changed"))
                    return@schedule
                }
                val result = sendTextBlocking(
                    currentConfig, state.replyText, manualTest = true,
                    replyMarkup = TelegramDashboardPolicy.keyboardJson,
                )
                val decision = telegramCommandRetryDecision(state, result)
                if (decision.retry) {
                    log(
                        "[TELEGRAM_COMMAND_REPLY] success=false attempt=${state.attempt}/$TELEGRAM_COMMAND_MAX_ATTEMPTS " +
                            "retryInMs=${decision.delayMs} code=${result.httpCode ?: "none"}",
                    )
                    scheduleCommandReply(
                        target,
                        requireNotNull(decision.nextAttempt),
                        decision.delayMs,
                        callback,
                    )
                } else {
                    log(
                        "[TELEGRAM_COMMAND_REPLY] success=${result.success} " +
                            "attempt=${state.attempt}/$TELEGRAM_COMMAND_MAX_ATTEMPTS " +
                            "code=${result.httpCode ?: "none"}",
                    )
                    callback(result)
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            callback(TelegramSendResult(false, "queue_rejected:${error.javaClass.simpleName}"))
        }
    }

    fun sendPhoto(png: ByteArray, filename: String, caption: String) = enqueue { config ->
        if (!config.sendDebugImages) {
            log("[TELEGRAM_SEND] skipped=true reason=debug_images_disabled")
        } else {
            sendPhotoBlocking(config, png, filename, caption)
        }
    }

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
        replyMarkup: String? = null,
    ): TelegramSendResult {
        if (!shouldSendTelegramText(config, manualTest)) {
            log("[TELEGRAM_SEND] skipped=true reason=text_notifications_disabled")
            return TelegramSendResult(false, "text_notifications_disabled")
        }
        return request(config, "sendMessage", "application/x-www-form-urlencoded") { output ->
            val body = "chat_id=${encode(config.chatId)}&text=${encode(text)}" +
                (replyMarkup?.let { "&reply_markup=${encode(it)}" } ?: "")
            output.write(body.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun sendPhotoBlocking(
        config: TelegramConfig,
        png: ByteArray,
        filename: String,
        caption: String,
        replyMarkup: String? = null,
    ): TelegramSendResult {
        val boundary = "RobotRunBoundary${System.nanoTime()}"
        return request(config, "sendPhoto", "multipart/form-data; boundary=$boundary") { output ->
            fun field(name: String, value: String) {
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("chat_id", config.chatId)
            field("caption", caption)
            if (replyMarkup != null) field("reply_markup", replyMarkup)
            output.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"$filename\"\r\n".toByteArray(),
            )
            output.write("Content-Type: image/png\r\n\r\n".toByteArray())
            output.write(png)
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }
    }

    /**
     * Manual /screen and /all only. Upload the already-encoded PNG as a Telegram
     * document, so its original 1280x720 bytes are available instead of a photo rendition.
     * Automatic diagnostic images retain their existing sendPhoto behavior.
     */
    private fun sendScreenshotDocumentBlocking(
        config: TelegramConfig,
        png: ByteArray,
        filename: String,
        caption: String,
        replyMarkup: String? = null,
    ): TelegramSendResult {
        val boundary = "RobotRunBoundary${System.nanoTime()}"
        return request(config, "sendDocument", "multipart/form-data; boundary=$boundary") { output ->
            writeScreenshotDocumentMultipart(
                output, boundary, config.chatId, png, filename, caption, replyMarkup,
            )
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
                connection.inputStream?.close()
                log("[TELEGRAM_SEND] success=true method=$method code=$code")
                return TelegramSendResult(true, "success", code)
            }
            val responseBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val retryAfterSeconds = parseTelegramRetryAfterSeconds(responseBody)
            log(
                "[TELEGRAM_SEND] success=false reason=http_error method=$method code=$code " +
                    "retryAfterSeconds=${retryAfterSeconds ?: "none"}",
            )
            return TelegramSendResult(false, "http_error", code, retryAfterSeconds)
        } catch (error: Exception) {
            // Exception messages from URLConnection can contain the request URL;
            // never risk exposing the bot token in app logs.
            val reason = if (error is IOException) "io_exception" else "exception"
            log("[TELEGRAM_SEND] success=false reason=$reason type=${error.javaClass.simpleName}")
            return TelegramSendResult(false, "$reason:${error.javaClass.simpleName}")
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
