package com.example.cookierunbot.notifications

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.example.cookierunbot.diagnostics.RobotLog as Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.diagnostics.DebugLogStore
import com.example.cookierunbot.diagnostics.LogPackageBuilder
import com.example.cookierunbot.update.UpdateFeedClient
import com.example.cookierunbot.update.UpdatePolicy
import com.example.cookierunbot.update.UpdateDescriptor
import java.io.File
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
import java.util.concurrent.atomic.AtomicBoolean

data class TelegramSendResult(
    val success: Boolean,
    val reason: String,
    val httpCode: Int? = null,
    val retryAfterSeconds: Long? = null,
    val attempt: Int = 1,
)

/** Keep the complete /all summary even if the optional screenshot is unavailable. */
internal fun formatAllWithScreenNote(allReply: String, screenFailure: String?): String =
    if (screenFailure == null) allReply else "$screenFailure\n\n$allReply"

internal const val TELEGRAM_COMMAND_MAX_ATTEMPTS = 3
private const val TELEGRAM_COMMAND_RETRY_1_MS = 2_000L
private const val TELEGRAM_COMMAND_RETRY_2_MS = 5_000L
private const val TELEGRAM_ANTI_BOT_MAX_ATTEMPTS = 3
private const val TELEGRAM_ANTI_BOT_RETRY_1_MS = 2_000L
private const val TELEGRAM_ANTI_BOT_RETRY_2_MS = 5_000L

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

    fun sameDestination(other: TelegramCommandReplyTarget): Boolean =
        chatId == other.chatId && botTokenIdentity == other.botTokenIdentity

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

internal data class TelegramAntiBotRetryDecision(
    val retry: Boolean,
    val delayMs: Long = 0L,
    val nextAttempt: Int = 0,
)

internal fun telegramAntiBotRetryDecision(
    attempt: Int,
    result: TelegramSendResult,
): TelegramAntiBotRetryDecision {
    if (
        result.success ||
        attempt >= TELEGRAM_ANTI_BOT_MAX_ATTEMPTS ||
        !isTransientTelegramFailure(result)
    ) {
        return TelegramAntiBotRetryDecision(retry = false)
    }
    val fallback = if (attempt == 1) {
        TELEGRAM_ANTI_BOT_RETRY_1_MS
    } else {
        TELEGRAM_ANTI_BOT_RETRY_2_MS
    }
    val telegramDelay = result.retryAfterSeconds
        ?.coerceAtLeast(0L)
        ?.let(::secondsToMillisSafely)
    return TelegramAntiBotRetryDecision(
        retry = true,
        delayMs = telegramDelay ?: fallback,
        nextAttempt = attempt + 1,
    )
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
    private val updateRequestBusy = AtomicBoolean(false)
    private val logRequestBusy = AtomicBoolean(false)
    private var lastDeliveredApkHash: String? = null // update executor only
    private var lastDeliveredApkTarget: TelegramCommandReplyTarget? = null // update executor only
    private var lastDeliveredApkAtMs = 0L // update executor only
    private val updateExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "telegram-apk-delivery")
    }
    private val logsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "telegram-log-delivery")
    }
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

    /** The one-tap Update button delivers a verified newer APK, never remotely installs. */
    @Suppress("DEPRECATION")
    internal fun sendCommandUpdate(target: TelegramCommandReplyTarget) {
        if (!updateRequestBusy.compareAndSet(false, true)) {
            sendCommandReply("📦 Update check/download already in progress. Please use the current request.", target)
            return
        }
        try {
            updateExecutor.execute {
                try {
                    deliverUpdateOnRequest(target)
                } catch (error: Exception) {
                    log("[TELEGRAM_UPDATE] failed=true type=${error.javaClass.simpleName}")
                    sendCommandReply(
                        "📦 Update check or delivery failed. Open RobotRun → Update App to " +
                            "check the configured HTTPS feed or use a downloaded APK.",
                        target,
                    )
                } finally {
                    updateRequestBusy.set(false)
                }
            }
        } catch (error: Exception) {
            updateRequestBusy.set(false)
            log("[TELEGRAM_UPDATE] queue_rejected=true type=${error.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun deliverUpdateOnRequest(target: TelegramCommandReplyTarget) {
        var config = TelegramConfigStore.load(appContext)
        if (!target.matches(config)) return
        val installed = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode
            else installed.versionCode.toLong()
        val installedName = installed.versionName ?: "unknown"
        val feedUrl = appContext.getSharedPreferences("robotrun_update", Context.MODE_PRIVATE)
            .getString("https_update_feed", "").orEmpty().trim()
        if (feedUrl.isBlank()) {
            sendCommandReply(formatTelegramUpdateReply(installedName, installedCode, null, false), target)
            return
        }

        val candidate = try {
            UpdateFeedClient(appContext).check(feedUrl)
        } catch (error: Exception) {
            log("[TELEGRAM_UPDATE] feed_failed=true type=${error.javaClass.simpleName}")
            sendCommandReply(formatTelegramUpdateReply(installedName, installedCode, null, true, true), target)
            return
        }
        if (!target.matches(TelegramConfigStore.load(appContext))) return
        if (!UpdatePolicy.hasNewerVersion(installedCode, candidate.versionCode)) {
            sendCommandReply(formatTelegramUpdateReply(installedName, installedCode, candidate, true), target)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastDeliveredApkTarget?.sameDestination(target) == true &&
            TelegramApkDeliveryPolicy.recentlyDelivered(
                now, lastDeliveredApkAtMs, candidate, lastDeliveredApkHash,
            )
        ) {
            sendCommandReply("📦 RobotRun v${candidate.versionName} was already sent to this chat " +
                "recently. Please find the previous APK or try again after five minutes.", target)
            return
        }

        config = TelegramConfigStore.load(appContext)
        if (!target.matches(config)) return
        // Status before attachment, on this separate worker; ordinary watchdog send executor remains free.
        sendTextBlocking(
            config, formatTelegramUpdateReply(installedName, installedCode, candidate, true),
            manualTest = true, replyMarkup = TelegramDashboardPolicy.keyboardJson,
        )

        val directory = File(appContext.cacheDir, "telegram-apk-delivery")
        var verifiedApk: File? = null
        try {
            verifiedApk = UpdateFeedClient(appContext).download(
                candidate, destinationDirectory = directory,
                maxBytes = TelegramApkDeliveryPolicy.MAX_FILE_BYTES,
            )
            config = TelegramConfigStore.load(appContext)
            if (!target.matches(config)) {
                log("[TELEGRAM_UPDATE] cancelled=true reason=config_changed_before_upload")
                return
            }
            val file = requireNotNull(verifiedApk)
            if (!TelegramApkDeliveryPolicy.canSend(file.length())) {
                throw IOException("APK exceeds Telegram's upload limit.")
            }
            val result = sendApkDocumentBlocking(config, candidate, file)
            if (result.success) {
                lastDeliveredApkHash = candidate.sha256
                lastDeliveredApkTarget = target
                lastDeliveredApkAtMs = SystemClock.elapsedRealtime()
            } else if (target.matches(TelegramConfigStore.load(appContext))) {
                sendCommandReply(
                    "📦 APK upload failed. Open RobotRun → Update App to download and install " +
                        "the verified version on your Android device.", target,
                )
            }
        } catch (error: Exception) {
            log("[TELEGRAM_UPDATE] apk_failed=true type=${error.javaClass.simpleName}")
            if (target.matches(TelegramConfigStore.load(appContext))) {
                val reason = if (error is IOException &&
                    error.message?.contains("size", ignoreCase = true) == true
                ) "The APK is too large for Telegram's 50 MB Bot API upload limit." else
                    "The APK could not be downloaded or verified. No unverified APK was sent."
                sendCommandReply(
                    "📦 $reason Open RobotRun → Update App for a direct download or an offline APK.",
                    target,
                )
            }
        } finally {
            verifiedApk?.delete()
            File(directory, "incoming.apk").delete()
        }
    }

    /** One upload attempt: retries after an ambiguous upload could send the APK twice. */
    private fun sendApkDocumentBlocking(
        config: TelegramConfig,
        candidate: UpdateDescriptor,
        apk: File,
    ): TelegramSendResult {
        val boundary = "RobotRunBoundary${System.nanoTime()}"
        return request(
            config, "sendDocument", "multipart/form-data; boundary=$boundary",
            streamed = true, readTimeoutMs = 120_000,
        ) { output ->
            writeTelegramApkMultipart(
                output, boundary, config.chatId, apk,
                TelegramApkDeliveryPolicy.fileName(candidate),
                "📦 Verified RobotRun v${candidate.versionName} (code ${candidate.versionCode}). " +
                    "Download on the Android device, stop Bot and Screen Capture, then approve " +
                    "the Android installer. This message does not install the app.",
                TelegramDashboardPolicy.keyboardJson,
            )
        }
    }

    /** Manual authorized /logs only. Build and upload off the notification/watchdog executors. */
    internal fun sendCommandLogs(target: TelegramCommandReplyTarget) {
        if (!logRequestBusy.compareAndSet(false, true)) {
            sendCommandReply("📥 A log export is already in progress.", target)
            return
        }
        try {
            logsExecutor.execute {
                var archive: File? = null
                try {
                    if (!target.matches(TelegramConfigStore.load(appContext))) return@execute
                    archive = LogPackageBuilder.create(appContext)
                    val current = TelegramConfigStore.load(appContext)
                    if (!target.matches(current)) return@execute
                    val boundary = "RobotRunBoundary${System.nanoTime()}"
                    val file = requireNotNull(archive)
                    val result = request(
                        current, "sendDocument", "multipart/form-data; boundary=$boundary",
                        streamed = true, readTimeoutMs = 120_000,
                    ) { output ->
                        writeTelegramLogMultipart(
                            output, boundary, current.chatId, file,
                            { target.matches(TelegramConfigStore.load(appContext)) },
                            TelegramDashboardPolicy.keyboardJson,
                        )
                    }
                    if (target.matches(TelegramConfigStore.load(appContext))) {
                        sendCommandReply(
                            if (result.success) "📥 The 10-hour diagnostic ZIP was sent above."
                            else "📥 Log upload failed. Use RobotRun → Debug Log → Save 10h ZIP.",
                            target,
                        )
                    }
                } catch (error: Exception) {
                    log("[TELEGRAM_LOGS] export_failed type=${error.javaClass.simpleName}")
                    if (target.matches(TelegramConfigStore.load(appContext))) {
                        sendCommandReply(
                            "📥 Log export is unavailable or exceeds Telegram's 49 MB limit. " +
                                "Use RobotRun → Debug Log → Save 10h ZIP.", target,
                        )
                    }
                } finally {
                    archive?.let(LogPackageBuilder::cleanup)
                    logRequestBusy.set(false)
                }
            }
        } catch (error: Exception) {
            logRequestBusy.set(false)
            log("[TELEGRAM_LOGS] queue_rejected type=${error.javaClass.simpleName}")
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

    /**
     * Explicit Anti-bot evidence delivery. The Startup Options toggle is the opt-in,
     * so this path intentionally does not depend on the generic debug-image toggle.
     * The destination is bound when queued and revalidated before every attempt.
     */
    fun notifyAntiBotImage(
        normalizedPng: ByteArray,
        filename: String,
        caption: String,
        isStillEnabled: () -> Boolean = { true },
        onRetry: (nextAttempt: Int) -> Unit = {},
        callback: (TelegramSendResult) -> Unit = {},
    ): Boolean {
        val config = TelegramConfigStore.load(appContext)
        logConfig(config)
        if (!config.enabled) {
            val result = TelegramSendResult(false, "telegram_disabled", attempt = 0)
            log("[TELEGRAM_ANTI_BOT] queued=false reason=telegram_disabled")
            runCatching { callback(result) }
            return false
        }
        if (!config.isValid) {
            val result = TelegramSendResult(false, "invalid_config", attempt = 0)
            log("[TELEGRAM_ANTI_BOT] queued=false reason=invalid_config detail=${config.invalidReason}")
            runCatching { callback(result) }
            return false
        }
        return scheduleAntiBotImage(
            target = TelegramCommandReplyTarget.from(config),
            normalizedPng = normalizedPng,
            filename = filename,
            caption = caption,
            attempt = 1,
            delayMs = 0L,
            isStillEnabled = isStillEnabled,
            onRetry = onRetry,
            callback = callback,
        )
    }

    private fun scheduleAntiBotImage(
        target: TelegramCommandReplyTarget,
        normalizedPng: ByteArray,
        filename: String,
        caption: String,
        attempt: Int,
        delayMs: Long,
        isStillEnabled: () -> Boolean,
        onRetry: (nextAttempt: Int) -> Unit,
        callback: (TelegramSendResult) -> Unit,
    ): Boolean {
        return try {
            executor.schedule({
                if (!runCatching { isStillEnabled() }.getOrDefault(false)) {
                    val result = TelegramSendResult(false, "feature_disabled", attempt = attempt)
                    log(
                        "[TELEGRAM_ANTI_BOT] success=false reason=feature_disabled " +
                            "attempt=$attempt/$TELEGRAM_ANTI_BOT_MAX_ATTEMPTS",
                    )
                    runCatching { callback(result) }
                    return@schedule
                }
                val currentConfig = TelegramConfigStore.load(appContext)
                if (!target.matches(currentConfig)) {
                    val result = TelegramSendResult(false, "config_changed", attempt = attempt)
                    log(
                        "[TELEGRAM_ANTI_BOT] success=false reason=config_changed " +
                            "attempt=$attempt/$TELEGRAM_ANTI_BOT_MAX_ATTEMPTS",
                    )
                    runCatching { callback(result) }
                    return@schedule
                }

                val result = runCatching {
                    sendPhotoBlocking(currentConfig, normalizedPng, filename, caption)
                }.getOrElse { error ->
                    log(
                        "[TELEGRAM_ANTI_BOT] success=false reason=async_exception " +
                            "type=${error.javaClass.simpleName}",
                    )
                    TelegramSendResult(false, "exception:${error.javaClass.simpleName}")
                }.copy(attempt = attempt)

                val retryDecision = telegramAntiBotRetryDecision(attempt, result)
                if (retryDecision.retry) {
                    log(
                        "[TELEGRAM_ANTI_BOT] success=false attempt=$attempt/" +
                            "$TELEGRAM_ANTI_BOT_MAX_ATTEMPTS retryInMs=${retryDecision.delayMs} " +
                            "code=${result.httpCode ?: "none"} reason=${result.reason}",
                    )
                    runCatching { onRetry(retryDecision.nextAttempt) }
                    scheduleAntiBotImage(
                        target,
                        normalizedPng,
                        filename,
                        caption,
                        retryDecision.nextAttempt,
                        retryDecision.delayMs,
                        isStillEnabled,
                        onRetry,
                        callback,
                    )
                    return@schedule
                }

                log(
                    "[TELEGRAM_ANTI_BOT] success=${result.success} " +
                        "attempt=$attempt/$TELEGRAM_ANTI_BOT_MAX_ATTEMPTS " +
                        "code=${result.httpCode ?: "none"} reason=${result.reason}",
                )
                runCatching { callback(result) }
                    .onFailure { error ->
                        log("[TELEGRAM_CALLBACK] success=false type=${error.javaClass.simpleName}")
                    }
            }, delayMs, TimeUnit.MILLISECONDS)
            true
        } catch (error: Exception) {
            val result = TelegramSendResult(
                false,
                "queue_rejected:${error.javaClass.simpleName}",
                attempt = attempt,
            )
            log(
                "[TELEGRAM_ANTI_BOT] queued=false reason=queue_rejected " +
                    "attempt=$attempt type=${error.javaClass.simpleName}",
            )
            runCatching { callback(result) }
            false
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

    /**
     * Mystery-box result delivery is gated by its dedicated Startup Options switch,
     * not the local result-image save switch or Telegram's generic debug-image preference.
     */
    fun notifyResultImage(
        png: ByteArray,
        filename: String,
        caption: String,
        isStillEnabled: () -> Boolean,
        callback: (TelegramSendResult) -> Unit = {},
    ): Boolean {
        val config = TelegramConfigStore.load(appContext)
        logConfig(config)
        if (!runCatching { isStillEnabled() }.getOrDefault(false)) {
            log("[TELEGRAM_BOX_RESULT] queued=false reason=feature_disabled")
            return false
        }
        if (!config.enabled || !config.isValid) {
            val reason = if (!config.enabled) "telegram_disabled" else "invalid_config"
            log("[TELEGRAM_BOX_RESULT] queued=false reason=$reason")
            return false
        }
        val target = TelegramCommandReplyTarget.from(config)
        return try {
            executor.execute {
                val result = if (!runCatching { isStillEnabled() }.getOrDefault(false)) {
                    TelegramSendResult(false, "feature_disabled")
                } else {
                    val currentConfig = TelegramConfigStore.load(appContext)
                    if (!target.matches(currentConfig)) {
                        TelegramSendResult(false, "config_changed")
                    } else {
                        runCatching {
                            sendPhotoBlocking(currentConfig, png, filename, caption)
                        }.getOrElse { error ->
                            TelegramSendResult(false, "exception:${error.javaClass.simpleName}")
                        }
                    }
                }
                log(
                    "[TELEGRAM_BOX_RESULT] success=${result.success} " +
                        "code=${result.httpCode ?: "none"} reason=${result.reason}",
                )
                runCatching { callback(result) }
                    .onFailure { error ->
                        log("[TELEGRAM_CALLBACK] success=false type=${error.javaClass.simpleName}")
                    }
            }
            true
        } catch (error: Exception) {
            log("[TELEGRAM_BOX_RESULT] queued=false reason=queue_rejected type=${error.javaClass.simpleName}")
            false
        }
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
        streamed: Boolean = false,
        readTimeoutMs: Int = 20_000,
        writeBody: (BufferedOutputStream) -> Unit,
    ): TelegramSendResult {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("https://api.telegram.org/bot${config.botToken}/$method")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            if (streamed) connection.setChunkedStreamingMode(32 * 1024)
            BufferedOutputStream(connection.outputStream, 32 * 1024).use(writeBody)
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
