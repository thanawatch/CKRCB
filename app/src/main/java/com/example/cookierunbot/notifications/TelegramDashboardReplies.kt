package com.example.cookierunbot.notifications

import com.example.cookierunbot.automation.BotControllerDiagnostics
import com.example.cookierunbot.automation.FriendLifeDashboardStatus
import com.example.cookierunbot.automation.GameProgressStatus
import com.example.cookierunbot.automation.formatDuration
import com.example.cookierunbot.capture.Diagnostics
import com.example.cookierunbot.statistics.SessionStatistics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatFriendReply(status: FriendLifeDashboardStatus?): String {
    if (status == null) return "❤️ Friend Life\n\nBot: STOPPED\nSchedule: unavailable"
    val phase = when {
        !status.botRunning -> "BOT_STOPPED"
        status.inProgress -> "SENDING"
        status.retryInMs > 0L -> "RETRY_WAIT"
        status.scheduledPending -> "WAITING_FOR_MAINMENU"
        status.testPending -> "TEST_QUEUED"
        !status.enabled -> "DISABLED"
        else -> "SCHEDULED"
    }
    val nextDue = when {
        !status.enabled -> "disabled"
        status.scheduledPending -> "due; waiting for MAINMENU"
        status.nextDueInMs == null -> "unavailable"
        else -> formatDuration(status.nextDueInMs)
    }
    val retry = if (status.retryInMs > 0L) formatDuration(status.retryInMs) else "none"
    val lastProgress = status.lastProgressAgeMs?.let { formatDuration(it) + " ago" } ?: "none"
    return "❤️ Friend Life\n\nBot: ${if (status.botRunning) "RUNNING" else "STOPPED"}\n" +
        "Enabled: ${status.enabled}\nInterval: ${status.intervalMinutes} minutes\n" +
        "Phase: $phase\nNext scheduled due: $nextDue\nRetry delay: $retry\n" +
        "Manual test pending: ${status.testPending}\n" +
        "Sent this run: ${status.currentSentCount}\nLast run: ${status.lastOutcome} " +
        "(sent ${status.lastSentCount})\nLast Friend Life progress: $lastProgress"
}

internal fun formatHealthReply(
    bot: BotControllerDiagnostics,
    capture: Diagnostics,
    accessibilityEnabled: Boolean,
    accessibilityConnected: Boolean,
): String {
    val age = capture.frameAgeMs?.let { "${it.toLong()}ms" } ?: "none"
    val fps = String.format(Locale.US, "%.1f", capture.captureFps)
    val detectAge = bot.lastDetectionAttemptAgeMs?.let { formatDuration(it) } ?: "none"
    return "🩺 RobotRun Health\n\n" +
        "Bot: ${if (bot.running) "RUNNING" else "STOPPED"}\n" +
        "Capture: ${if (capture.captureRunning) "RUNNING" else "STOPPED"}\n" +
        "Frame age: $age\nFrame: ${capture.normalizedWidth}x${capture.normalizedHeight}\n" +
        "Frame sequence: ${capture.frameSequence}\nCapture FPS: $fps\n" +
        "Accessibility enabled: $accessibilityEnabled\n" +
        "Accessibility connected: $accessibilityConnected\n" +
        "Worker alive: ${bot.workerAlive}\nJob active: ${bot.jobActive}\n" +
        "Gesture busy: ${bot.gestureBusy}\n" +
        "Detection: ${bot.group} / ${bot.currentHandler}\n" +
        "Last detection age: $detectAge\n" +
        "Capture error: ${if (capture.error == null) "none" else "reported in local logs"}"
}

internal fun formatWatchdogReply(
    status: GameProgressStatus,
    bot: BotControllerDiagnostics,
    latestRecoveryLog: String?,
): String {
    val progress = if (status.hasStarted) {
        "${status.lastProgressReason}, ${formatDuration(status.ageMs)} ago"
    } else "none"
    val timer = when {
        !status.running -> "STOPPED"
        status.overdue -> "OVERDUE"
        else -> "${formatDuration(status.remainingMs)} remaining"
    }
    return "⏱ RobotRun Watchdog\n\n" +
        "Timer: $timer\nLast progress: $progress\n" +
        "Alert: ${if (status.alertDelivered) "sent" else if (status.alertInFlight) "in flight" else "not delivered"}\n" +
        "Recovery active: ${bot.progressRecoveryActive}\n" +
        "Latest recovery log: ${latestRecoveryLog ?: "none in recent logs"}"
}

internal fun latestRecoverySummary(logText: String): String? =
    logText.lineSequence()
        .lastOrNull { "[GAME_PROGRESS_RECOVERY]" in it }
        ?.substringAfter("[GAME_PROGRESS_RECOVERY]")
        ?.trim()
        ?.take(180)

internal fun formatAntiBotReply(
    stats: SessionStatistics,
    imageDeliveryEnabled: Boolean,
    latestEvidenceStatus: String? = null,
): String {
    val selected = stats.antiBotSelectedTapCount
    val successful = stats.antiBotSuccessfulTapCount
    val unresolved = (selected - successful).coerceAtLeast(0)
    val successRate = if (selected == 0) {
        "n/a"
    } else {
        String.format(Locale.US, "%.1f%%", successful * 100.0 / selected)
    }
    val recent = stats.antiBotHistory.take(10)
    val history = if (recent.isEmpty()) {
        "No Anti-bot detections in this session yet."
    } else {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        recent.joinToString("\n\n") { event ->
            val cards = event.selectedCards.joinToString(", ").ifBlank { "none" }
            val successfulCards = event.successfulCards.sorted()
            val successfulText = successfulCards.joinToString(", ").ifBlank { "none" }
            "#${event.detectionIndex} • Round ${event.round} • " +
                timeFormat.format(Date(event.detectedAt)) + "\n" +
                "Cards: $cards | Successful: ${successfulCards.size}/${event.selectedCards.size} " +
                "($successfulText)"
        }
    }
    return "🤖 Anti Bot\n\n" +
        "Detected screens: ${stats.antiBotDetectedCount}\n" +
        "Selected card taps: $selected\n" +
        "Successful taps: $successful/$selected\n" +
        "Failed/pending taps: $unresolved\n" +
        "Success rate: $successRate\n" +
        "Telegram image delivery: ${if (imageDeliveryEnabled) "ON" else "OFF"}\n" +
        "Latest evidence: ${latestEvidenceStatus ?: "none yet"}\n\n" +
        "Recent history (latest ${recent.size}, up to 30 retained):\n$history"
}

internal fun latestAntiBotEvidenceStatus(logText: String): String? =
    logText.lineSequence()
        .lastOrNull { "[TELEGRAM_ANTI_BOT]" in it }
        ?.let { line ->
            when {
                "success=true" in line -> "Telegram sent"
                "success=false" in line -> "Telegram FAILED"
                "queued=false" in line -> "Telegram not queued"
                else -> null
            }
        }

internal fun formatRoundsReply(stats: SessionStatistics): String {
    val recent = stats.history.take(10)
    if (recent.isEmpty()) return "🎮 Recent Rounds\n\nNo completed rounds in this session yet."
    return "🎮 Recent Rounds (latest ${recent.size})\n\n" +
        recent.joinToString("\n\n") { round ->
            val boxTypes = when {
                round.boxes.isNotEmpty() -> round.boxes.joinToString()
                round.boxCount > 0 -> "unknown"
                else -> "none"
            }
            "#${round.round}: Coins ${round.coins?.toString() ?: "unknown"}, " +
                "XP ${round.xp?.toString() ?: "unknown"}\n" +
                "Boxes: ${round.boxCount} | Types: $boxTypes | Duration: " +
                (round.durationMs?.let(::formatDuration) ?: "unknown")
        }
}

/** Manual read-only query of a small, explicitly allowlisted subset of local failure logs. */
internal fun formatErrorsReply(logText: String): String {
    val recent = logText.lineSequence().filter { line ->
        "[FRIEND_LIFE_ABORT]" in line ||
            "[GAME_PROGRESS_RECOVERY] failed=true" in line ||
            "[TELEGRAM_COMMAND_POLL] success=false" in line ||
            "[TELEGRAM_SEND] success=false" in line ||
            "[GAME_PROGRESS_WATCHDOG_TELEGRAM] httpSuccess=false" in line ||
            "[DETECT_STALL]" in line ||
            "[BOT] Waiting:" in line
    }.toList().takeLast(5)
    return if (recent.isEmpty()) {
        "⚠️ RobotRun Errors\n\nNo recent matching events in the in-memory log."
    } else {
        "⚠️ RobotRun Errors (latest ${recent.size})\n\n" +
            recent.joinToString("\n") { it.take(230) }
    }
}
