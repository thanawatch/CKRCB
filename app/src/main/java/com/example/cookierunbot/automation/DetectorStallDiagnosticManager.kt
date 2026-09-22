package com.example.cookierunbot.automation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.capture.Diagnostics
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.diagnostics.DebugLogStore
import com.example.cookierunbot.diagnostics.PersistentTrace
import com.example.cookierunbot.notifications.TelegramDiagnosticImageGate
import com.example.cookierunbot.notifications.TelegramNotifier
import com.example.cookierunbot.notifications.TelegramSendResult
import java.util.concurrent.Executors

class DetectorStallDiagnosticManager(context: Context) {
    private val watchdog = DetectorStallWatchdog()
    private val frameEncoder = NoMatchDebugFrameSaver(context.applicationContext)
    private val telegramNotifier = TelegramNotifier(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "stall-diagnostics")
    }
    private var lastHealthySuppressionLogAt = 0L

    @Synchronized
    fun observe(capture: Diagnostics, bot: BotControllerDiagnostics) {
        val now = SystemClock.elapsedRealtime()
        val observation = DetectorStallObservation(
            nowMs = now,
            botRunning = bot.running,
            workerAlive = bot.workerAlive,
            captureRunning = capture.captureRunning,
            captureFrameSequence = capture.frameSequence,
            lastProcessedFrameSequence = bot.lastProcessedFrameSequence,
            lastDetectionAttemptAgeMs = bot.lastDetectionAttemptAgeMs,
            group = bot.group,
            friendLifeActive = bot.friendLifeWorkflowActive,
            friendLifeLastProgressAgeMs = bot.friendLifeLastProgressAgeMs,
        )
        val decision = watchdog.observe(observation)
        decision.resetReason?.let { reason ->
            emit("[STALL_EPISODE_RESET] reason=$reason")
        }
        if (decision.episodeStarted) {
            emit(
                "[STALL_EPISODE] started=true group=${bot.group} " +
                    "currentHandler=${bot.currentHandler}",
            )
        }
        if (decision.suppressedByHealthyFriendLife) {
            if (now - lastHealthySuppressionLogAt >= HEALTHY_SUPPRESSION_LOG_INTERVAL_MS) {
                emit(
                    "[STALL_DIAG_SUPPRESS] reason=healthy_friend_life " +
                        "friendLifeLastProgressAgeMs=${bot.friendLifeLastProgressAgeMs ?: "none"}",
                )
                lastHealthySuppressionLogAt = now
            }
            return
        }
        if (!decision.notificationDue) return

        val reservation = watchdog.reserveNotification() ?: return
        emit(
            "[STALL_DIAG] due=true stallDurationMs=${decision.stallDurationMs} " +
                "notificationCandidate=${reservation.notificationIndex}/$TRUE_STALL_MAX_NOTIFICATIONS",
        )
        emit(
            "[STALL_DIAG] inFlight=true " +
                "notificationCandidate=${reservation.notificationIndex}/$TRUE_STALL_MAX_NOTIFICATIONS",
        )

        if (telegramNotifier.isDebugImageEnabled() && !TelegramDiagnosticImageGate.tryAcquire(now)) {
            emit("[TELEGRAM_DIAG_SUPPRESS] reason=global_image_cooldown source=DETECT_STALL")
            watchdog.releaseNotificationReservation(reservation, now)
            emit("[STALL_DIAG] inFlight=false notificationNotConsumed=true")
            return
        }
        val snapshot = LatestFrameProvider.snapshot()
        if (snapshot == null) {
            emit("[STALL_DIAG_CAPTURE] skipped=true reason=no_latest_frame")
            watchdog.releaseNotificationReservation(reservation, now)
            emit("[STALL_DIAG] inFlight=false notificationNotConsumed=true")
            return
        }
        executor.execute {
            try {
                val png = frameEncoder.encodeNormalizedPng(snapshot.mat)
                emit(
                    "[STALL_DIAG_CAPTURE] frameSequence=${snapshot.generation} " +
                        "normalizedSize=${snapshot.mat.cols()}x${snapshot.mat.rows()}",
                )
                val caption = buildCaption(bot, capture, decision)
                val queueAccepted = telegramNotifier.notifyDiagnosticImage(
                    png,
                    "detect_stall_normalized.png",
                    caption,
                ) { result ->
                    val completion = completeStallNotification(
                        watchdog,
                        reservation,
                        SystemClock.elapsedRealtime(),
                        result,
                    )
                    when (completion) {
                        StallNotificationCompletion.CONFIRMED -> emit(
                            "[STALL_DIAG_TELEGRAM] sendConfirmed=true code=${result.httpCode} " +
                                "notification=${reservation.notificationIndex}/$TRUE_STALL_MAX_NOTIFICATIONS",
                        )
                        StallNotificationCompletion.FAILED -> emit(
                            "[STALL_DIAG_TELEGRAM] sendConfirmed=false " +
                                "code=${result.httpCode ?: "none"} reason=${result.reason} " +
                                "notificationNotConsumed=true",
                        )
                        StallNotificationCompletion.STALE -> emit(
                            "[STALL_DIAG_TELEGRAM] staleCallback=true ignored=true " +
                                "candidate=${reservation.notificationIndex}/$TRUE_STALL_MAX_NOTIFICATIONS",
                        )
                    }
                    emit("[STALL_DIAG] inFlight=false completion=${completion.name}")
                }
                if (queueAccepted) {
                    emit(
                        "[STALL_DIAG_TELEGRAM] queueAccepted=true " +
                            "candidate=${reservation.notificationIndex}/$TRUE_STALL_MAX_NOTIFICATIONS",
                    )
                } else {
                    watchdog.releaseNotificationReservation(
                        reservation,
                        SystemClock.elapsedRealtime(),
                    )
                    emit("[STALL_DIAG_TELEGRAM] queueAccepted=false notificationNotConsumed=true")
                    emit("[STALL_DIAG] inFlight=false completion=QUEUE_REJECTED")
                }
            } catch (error: Exception) {
                watchdog.releaseNotificationReservation(
                    reservation,
                    SystemClock.elapsedRealtime(),
                )
                emit(
                    "[STALL_DIAG_CAPTURE] failed=true type=${error.javaClass.simpleName} " +
                        "notificationNotConsumed=true",
                )
            } finally {
                snapshot.mat.release()
            }
        }
    }

    @Synchronized
    fun reset(reason: String) {
        if (watchdog.reset()) emit("[STALL_EPISODE_RESET] reason=$reason")
    }

    private fun buildCaption(
        bot: BotControllerDiagnostics,
        capture: Diagnostics,
        decision: DetectorStallDecision,
    ): String = "RobotRun DETECT_STALL\n\n" +
        "group=${bot.group}\n" +
        "currentHandler=${bot.currentHandler}\n" +
        "workerAlive=${bot.workerAlive}\n" +
        "captureFrameSequence=${capture.frameSequence}\n" +
        "lastProcessedFrameSequence=${bot.lastProcessedFrameSequence}\n" +
        "lastDetectionAttemptAgeMs=${bot.lastDetectionAttemptAgeMs ?: "none"}\n" +
        "gestureBusy=${bot.gestureBusy}\n" +
        "pendingPopupAction=${bot.pendingPopupAction}\n" +
        "minimumFreshFrameGeneration=${bot.minimumFreshFrameGeneration}\n" +
        "friendLifeActive=${bot.friendLifeWorkflowActive}\n" +
        "friendLifeLastProgressAgeMs=${bot.friendLifeLastProgressAgeMs ?: "none"}\n" +
        "stallDurationMs=${decision.stallDurationMs}\n" +
        "notification=${decision.notificationIndex}/$TRUE_STALL_MAX_NOTIFICATIONS"

    private fun emit(message: String) {
        Log.i(CookieRunBotApplication.TAG, message)
        DebugLogStore.log(message)
        PersistentTrace.log(message)
    }

    private companion object {
        const val HEALTHY_SUPPRESSION_LOG_INTERVAL_MS = 30_000L
    }
}

internal enum class StallNotificationCompletion { CONFIRMED, FAILED, STALE }

internal fun completeStallNotification(
    watchdog: DetectorStallWatchdog,
    reservation: StallNotificationReservation,
    nowMs: Long,
    result: TelegramSendResult,
): StallNotificationCompletion {
    val confirmed = result.success && result.httpCode?.let { it in 200..299 } == true
    return if (confirmed) {
        if (watchdog.commitNotificationConfirmed(reservation, nowMs)) {
            StallNotificationCompletion.CONFIRMED
        } else {
            StallNotificationCompletion.STALE
        }
    } else {
        if (watchdog.releaseNotificationReservation(reservation, nowMs)) {
            StallNotificationCompletion.FAILED
        } else {
            StallNotificationCompletion.STALE
        }
    }
}
