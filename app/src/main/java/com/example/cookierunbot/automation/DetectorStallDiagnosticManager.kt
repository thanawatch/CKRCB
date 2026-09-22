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

        if (telegramNotifier.isDebugImageEnabled() && !TelegramDiagnosticImageGate.tryAcquire(now)) {
            emit("[TELEGRAM_DIAG_SUPPRESS] reason=global_image_cooldown source=DETECT_STALL")
            return
        }
        val snapshot = LatestFrameProvider.snapshot()
        if (snapshot == null) {
            emit("[STALL_DIAG_CAPTURE] skipped=true reason=no_latest_frame")
            return
        }
        watchdog.markNotificationQueued(now)
        emit(
            "[STALL_DIAG] due=true stallDurationMs=${decision.stallDurationMs} " +
                "notification=${decision.notificationIndex}/$TRUE_STALL_MAX_NOTIFICATIONS",
        )
        executor.execute {
            try {
                val png = frameEncoder.encodeNormalizedPng(snapshot.mat)
                emit(
                    "[STALL_DIAG_CAPTURE] frameSequence=${snapshot.generation} " +
                        "normalizedSize=${snapshot.mat.cols()}x${snapshot.mat.rows()}",
                )
                val caption = buildCaption(bot, capture, decision)
                val queued = telegramNotifier.notifyDiagnosticImage(
                    png,
                    "detect_stall_normalized.png",
                    caption,
                )
                emit(
                    "[STALL_DIAG_TELEGRAM] queued=$queued " +
                        "notification=${decision.notificationIndex}/$TRUE_STALL_MAX_NOTIFICATIONS",
                )
            } catch (error: Exception) {
                emit("[STALL_DIAG_CAPTURE] failed=true type=${error.javaClass.simpleName}")
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
