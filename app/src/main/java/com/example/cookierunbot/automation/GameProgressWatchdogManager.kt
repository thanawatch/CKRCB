package com.example.cookierunbot.automation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.diagnostics.DebugLogStore
import com.example.cookierunbot.notifications.TelegramDiagnosticImageGate
import com.example.cookierunbot.notifications.TelegramNotifier
import com.example.cookierunbot.notifications.secondsToMillisSafely
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

enum class WatchdogRecoveryTestRequest {
    STARTED,
    BOT_STOPPED,
    CAPTURE_STOPPED,
    ALREADY_RUNNING,
    WATCHDOG_OVERDUE,
    UNAVAILABLE,
}

object GameProgressWatchdogManager {
    private val lock = Any()
    private val watchdog = GameProgressWatchdog()
    private var scheduler: ScheduledExecutorService? = null
    private var appContext: Context? = null
    private var notifier: TelegramNotifier? = null
    private var encoder: NoMatchDebugFrameSaver? = null
    private var recoveryRequester: ((GameProgressRecoveryReservation) -> Unit)? = null
    private var manualRecoverySequence = 0L
    private var manualRecoveryReservation: GameProgressRecoveryReservation? = null
    private var manualRecoveryEpisodeGeneration: Long? = null

    fun start(context: Context, requestRecovery: (GameProgressRecoveryReservation) -> Unit) = synchronized(lock) {
        appContext = context.applicationContext
        notifier = notifier ?: TelegramNotifier(context.applicationContext)
        encoder = encoder ?: NoMatchDebugFrameSaver(context.applicationContext)
        recoveryRequester = requestRecovery
        manualRecoveryReservation = null
        manualRecoveryEpisodeGeneration = null
        watchdog.start(SystemClock.elapsedRealtime())
        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "game-progress-watchdog")
        }.also { executor ->
            executor.scheduleWithFixedDelay(::check, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
        log("[GAME_PROGRESS_WATCHDOG] started=true timeoutMs=$GAME_PROGRESS_TIMEOUT_MS")
    }

    fun stop() = synchronized(lock) {
        watchdog.stop()
        scheduler?.shutdownNow()
        scheduler = null
        recoveryRequester = null
        manualRecoveryReservation = null
        manualRecoveryEpisodeGeneration = null
        log("[GAME_PROGRESS_WATCHDOG] stopped=true")
    }

    fun recordProgress(reason: GameProgressReason) {
        val previousAge = watchdog.recordProgress(reason, SystemClock.elapsedRealtime()) ?: run {
            log(
                "[GAME_PROGRESS_WATCHDOG_PROGRESS] ignored=true " +
                    "reason=watchdog_stopped progress=${reason.name}",
            )
            return
        }
        log("[GAME_PROGRESS_WATCHDOG_RESET] reason=${reason.name} previousAgeMs=$previousAge")
    }

    fun status(): GameProgressStatus = watchdog.status(SystemClock.elapsedRealtime())

    fun isCurrentRecovery(reservation: GameProgressRecoveryReservation): Boolean {
        if (reservation.generation >= 0L) return watchdog.isCurrentRecovery(reservation)
        return synchronized(lock) {
            manualRecoveryReservation == reservation &&
                manualRecoveryEpisodeGeneration == watchdog.currentGeneration() &&
                watchdog.status(SystemClock.elapsedRealtime()).running
        }
    }

    fun requestRecoveryTest(): WatchdogRecoveryTestRequest {
        val requester: (GameProgressRecoveryReservation) -> Unit
        val reservation: GameProgressRecoveryReservation
        synchronized(lock) {
            val status = watchdog.status(SystemClock.elapsedRealtime())
            if (!status.running) return WatchdogRecoveryTestRequest.BOT_STOPPED
            if (status.overdue) return WatchdogRecoveryTestRequest.WATCHDOG_OVERDUE
            if (!LatestFrameProvider.diagnostics().captureRunning) {
                return WatchdogRecoveryTestRequest.CAPTURE_STOPPED
            }
            if (manualRecoveryReservation != null) {
                return WatchdogRecoveryTestRequest.ALREADY_RUNNING
            }
            requester = recoveryRequester ?: return WatchdogRecoveryTestRequest.UNAVAILABLE
            manualRecoverySequence = if (manualRecoverySequence == Long.MAX_VALUE) 1L else manualRecoverySequence + 1L
            reservation = GameProgressRecoveryReservation(-manualRecoverySequence)
            manualRecoveryReservation = reservation
            manualRecoveryEpisodeGeneration = watchdog.currentGeneration()
        }

        return try {
            log(
                "[GAME_PROGRESS_WATCHDOG_TEST] requested=true timerUnaffected=true " +
                    "reservation=${reservation.generation}",
            )
            requester.invoke(reservation)
            WatchdogRecoveryTestRequest.STARTED
        } catch (error: Exception) {
            finishRecovery(reservation)
            log("[GAME_PROGRESS_WATCHDOG_TEST] requested=false reason=${error.javaClass.simpleName}")
            WatchdogRecoveryTestRequest.UNAVAILABLE
        }
    }

    fun finishRecovery(reservation: GameProgressRecoveryReservation) {
        if (reservation.generation >= 0L) return
        val cleared = synchronized(lock) {
            if (manualRecoveryReservation != reservation) {
                false
            } else {
                manualRecoveryReservation = null
                manualRecoveryEpisodeGeneration = null
                true
            }
        }
        if (cleared) {
            log("[GAME_PROGRESS_WATCHDOG_TEST] completed=true timerUnaffected=true")
        }
    }

    private fun check() {        val now = SystemClock.elapsedRealtime()
        val recovery = watchdog.reserveRecoveryIfDue(now)
        try {
            sendAlertIfDue(now)
        } finally {
            if (recovery != null && watchdog.isCurrentRecovery(recovery)) {
                val requester = synchronized(lock) { recoveryRequester }
                try {
                    requester?.invoke(recovery)
                } catch (error: Exception) {
                    log("[GAME_PROGRESS_RECOVERY] requestFailed=${error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun sendAlertIfDue(now: Long) {
        val reservation = watchdog.reserveIfDue(now) ?: return
        val status = watchdog.status(now)
        log("[GAME_PROGRESS_WATCHDOG_DUE] elapsedMs=${status.ageMs} lastReason=${status.lastProgressReason}")
        val telegram = synchronized(lock) { notifier } ?: run {
            watchdog.failAttempt(reservation, now)
            return
        }
        val diagnostics = BotRuntime.diagnostics()
        val capture = LatestFrameProvider.diagnostics()
        val caption = buildCaption(status, diagnostics, capture.frameSequence)
        val debugImageEnabled = telegram.isDebugImageEnabled()
        if (debugImageEnabled && !TelegramDiagnosticImageGate.tryAcquire(now)) {
            watchdog.failAttempt(reservation, now)
            log("[TELEGRAM_DIAG_SUPPRESS] reason=global_image_cooldown source=GAME_PROGRESS")
            return
        }
        if (!debugImageEnabled) {
            submitTelegram(telegram, reservation, ByteArray(0), caption)
            return
        }
        val snapshot = LatestFrameProvider.snapshot()
        if (snapshot == null) {
            watchdog.failAttempt(reservation, now)
            log("[GAME_PROGRESS_WATCHDOG_CAPTURE] skipped=true reason=no_latest_frame")
            return
        }
        try {
            val png = requireNotNull(synchronized(lock) { encoder }).encodeNormalizedPng(snapshot.mat)
            log("[GAME_PROGRESS_WATCHDOG_CAPTURE] frameSequence=${snapshot.generation} normalizedSize=${snapshot.mat.cols()}x${snapshot.mat.rows()}")
            submitTelegram(telegram, reservation, png, caption)
        } catch (error: Exception) {
            watchdog.failAttempt(reservation, SystemClock.elapsedRealtime())
            log("[GAME_PROGRESS_WATCHDOG_CAPTURE] failed=true type=${error.javaClass.simpleName}")
        } finally { snapshot.mat.release() }
    }

    private fun submitTelegram(
        telegram: TelegramNotifier,
        reservation: GameProgressAlertReservation,
        png: ByteArray,
        caption: String,
    ) {
        val accepted = telegram.notifyDiagnosticImage(png, "game_progress_timeout.png", caption) { result ->
            val httpSuccess = result.success && result.httpCode?.let { it in 200..299 } == true
            val now = SystemClock.elapsedRealtime()
            val committed: Boolean
            val stale: Boolean
            if (httpSuccess) {
                committed = watchdog.confirmDelivered(reservation)
                stale = !committed
            } else {
                val telegramDelayMs = result.retryAfterSeconds?.let(::secondsToMillisSafely) ?: 0L
                val released = watchdog.failAttempt(
                    reservation,
                    now,
                    maxOf(GAME_PROGRESS_FAILED_RETRY_MS, telegramDelayMs),
                )
                committed = false
                stale = !released
            }
            log(
                "[GAME_PROGRESS_WATCHDOG_TELEGRAM] httpSuccess=$httpSuccess committed=$committed " +
                    "stale=$stale code=${result.httpCode ?: "none"} reason=${result.reason} " +
                    "retryAfterSeconds=${result.retryAfterSeconds ?: "none"}",
            )
        }
        if (!accepted) {
            watchdog.failAttempt(reservation, SystemClock.elapsedRealtime())
            log("[GAME_PROGRESS_WATCHDOG_TELEGRAM] success=false reason=queue_rejected")
        }
    }

    private fun buildCaption(status: GameProgressStatus, bot: BotControllerDiagnostics, frameSequence: Long): String =
        "⚠️ RobotRun Watchdog\n\n" +
            "No GAME_COMPLETE or Friend Life Send tap for 15 minutes.\n\n" +
            "Bot: ${if (bot.running) "RUNNING" else "STOPPED"}\n" +
            "Group: ${bot.group}\nHandler: ${bot.currentHandler}\nRound: ${bot.roundNumber}\n\n" +
            "Last progress: ${status.lastProgressReason}\n" +
            "Time since progress: ${formatDuration(status.ageMs)}\n" +
            "Frame sequence: $frameSequence\nFriend Life active: ${bot.friendLifeWorkflowActive}\n\n" +
            "Possible bot/game stall."

    private fun log(message: String) {
        Log.i(CookieRunBotApplication.TAG, message)
        DebugLogStore.log(message)
    }

    private const val CHECK_INTERVAL_MS = 30_000L
}

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
