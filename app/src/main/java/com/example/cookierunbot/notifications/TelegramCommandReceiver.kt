package com.example.cookierunbot.notifications

import android.content.Context
import com.example.cookierunbot.diagnostics.RobotLog as Log
import com.example.cookierunbot.CookieRunBotApplication
import com.example.cookierunbot.automation.BotRuntime
import com.example.cookierunbot.automation.BotAccessibilityService
import com.example.cookierunbot.automation.GameProgressWatchdogManager
import com.example.cookierunbot.automation.formatDuration
import com.example.cookierunbot.diagnostics.DebugLogStore
import com.example.cookierunbot.capture.LatestFrameProvider
import com.example.cookierunbot.statistics.RoundResult
import com.example.cookierunbot.statistics.RoundResultStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object TelegramCommandReceiver {
    private const val PREFS = "telegram_command_receiver"
    private const val LONG_POLL_SECONDS = 25
    private val started = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)
    @Volatile private var activeConnection: HttpURLConnection? = null
    private var appContext: Context? = null
    private var commandNotifier: TelegramNotifier? = null

    internal fun commandContext(): Context? = appContext

    fun initialize(context: Context) {
        appContext = context.applicationContext
        commandNotifier = commandNotifier ?: TelegramNotifier(context.applicationContext)
        if (!started.compareAndSet(false, true)) return
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "telegram-command-poller") }
            .execute(::pollLoop)
    }

    fun refresh(context: Context) {
        appContext = context.applicationContext
        refreshRequested.set(true)
        activeConnection?.disconnect()
        initialize(context)
    }

    private fun pollLoop() {
        while (true) {
            val context = appContext ?: return
            val config = TelegramConfigStore.load(context)
            if (!config.enabled || !config.isValid) {
                sleep(5_000L)
                continue
            }
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val signature = "${config.botToken.hashCode()}:${config.chatId}"
            if (preferences.getString("signature", null) != signature) {
                preferences.edit().putString("signature", signature).putLong("last_update_id", -1L).apply()
            }
            val offset = preferences.getLong("last_update_id", -1L) + 1L
            val retryMs = poll(config, offset) { updateId, chatId, text ->
                val lastConsumed = preferences.getLong("last_update_id", -1L)
                if (!shouldProcessTelegramUpdate(lastConsumed, updateId)) return@poll
                preferences.edit().putLong("last_update_id", updateId).apply()
                val replyTarget = telegramCommandReplyTarget(chatId, config)
                if (replyTarget == null) {
                    log("[TELEGRAM_COMMAND] ignored=true reason=unauthorized_chat")
                    return@poll
                }
                val command = parseTelegramCommand(text) ?: return@poll
                when (command) {
                    "/screen" -> commandNotifier?.sendCommandScreenshot(replyTarget)
                    "/update" -> commandNotifier?.sendCommandUpdate(replyTarget)
                    "/logs" -> commandNotifier?.sendCommandLogs(replyTarget)
                    "/all" -> {
                        val reply = telegramCommandReply(command) ?: return@poll
                        commandNotifier?.sendCommandAll(reply, replyTarget)
                    }
                    else -> {
                        val reply = telegramCommandReply(command) ?: return@poll
                        commandNotifier?.sendCommandReply(reply, replyTarget)
                    }
                }
            }
            if (refreshRequested.getAndSet(false)) continue
            if (retryMs > 0L) sleep(retryMs)
        }
    }

    private fun poll(
        config: TelegramConfig,
        offset: Long,
        consume: (Long, String, String) -> Unit,
    ): Long {
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = "https://api.telegram.org/bot${config.botToken}/getUpdates" +
                "?offset=$offset&timeout=$LONG_POLL_SECONDS&allowed_updates=%5B%22message%22%5D"
            connection = URL(endpoint).openConnection() as HttpURLConnection
            activeConnection = connection
            connection.connectTimeout = 15_000
            connection.readTimeout = 35_000
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val retry = runCatching {
                    JSONObject(body).optJSONObject("parameters")?.optLong("retry_after", 0L) ?: 0L
                }.getOrDefault(0L)
                log("[TELEGRAM_COMMAND_POLL] success=false code=$code")
                return if (code == 429 && retry > 0L) retry * 1000L else 10_000L
            }
            val root = JSONObject(body)
            val updates = root.optJSONArray("result") ?: return 0L
            for (index in 0 until updates.length()) {
                val update = updates.getJSONObject(index)
                val updateId = update.getLong("update_id")
                val message = update.optJSONObject("message")
                if (message == null) {
                    consume(updateId, "", "")
                    continue
                }
                val chatId = message.optJSONObject("chat")?.opt("id")?.toString().orEmpty()
                consume(updateId, chatId, message.optString("text", ""))
            }
            0L
        } catch (error: Exception) {
            if (!refreshRequested.get()) {
                log("[TELEGRAM_COMMAND_POLL] success=false reason=${error.javaClass.simpleName}")
            }
            10_000L
        } finally {
            activeConnection = null
            connection?.disconnect()
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) { Unit }

    private fun log(message: String) {
        Log.i(CookieRunBotApplication.TAG, message)
        DebugLogStore.log(message)
    }
}

internal fun parseTelegramCommand(text: String): String? {
    TelegramDashboardPolicy.commandForButton(text)?.let { return it }
    val token = text.trim().substringBefore(' ').lowercase(Locale.US)
    if (!token.startsWith('/')) return null
    val command = token.substringBefore('@')
    if (command == "/start" || command == "/menu") return "/dashboard"
    return command.takeIf {
        it in setOf("/help", "/status", "/stats", "/last", "/boxes", "/all",
            "/friend", "/health", "/screen", "/watchdog", "/rounds", "/errors", "/dashboard", "/update", "/logs")
    }
}

internal fun isAuthorizedTelegramChat(incoming: String, configured: String): Boolean =
    incoming.trim().isNotEmpty() && incoming.trim() == configured.trim()

internal fun telegramCommandReplyTarget(
    incomingChatId: String,
    authorizingConfig: TelegramConfig,
): TelegramCommandReplyTarget? = if (
    isAuthorizedTelegramChat(incomingChatId, authorizingConfig.chatId)
) {
    TelegramCommandReplyTarget.from(authorizingConfig)
} else {
    null
}

internal fun shouldProcessTelegramUpdate(lastConsumedUpdateId: Long, incomingUpdateId: Long): Boolean =
    incomingUpdateId > lastConsumedUpdateId

internal fun telegramCommandReply(command: String): String? = when (command) {
    "/help" -> "RobotRun commands\n\n/all - Screenshot, session totals, status and last round\n" +
        "/friend - Friend Life schedule and progress\n/health - Capture, accessibility and worker\n" +
        "/screen - Current normalized screenshot\n/watchdog - Watchdog and recovery state\n" +
        "/rounds - Last ten rounds with box types\n/errors - Recent local error summary\n" +
        "/logs - Download private 10-hour diagnostic ZIP\n" +
        "/dashboard - Show the one-tap button dashboard\n/update - Check and send a verified newer APK\n" +
        "/status - Bot status\n/stats - Current session totals\n" +
        "/last - Last completed round\n/boxes - Mystery Box totals\n/help - Show commands"
    "/status" -> formatStatusReply()
    "/stats" -> formatStatsReply(RoundResultStore.snapshot())
    "/last" -> formatLastRoundReply(RoundResultStore.snapshot().lastRound)
    "/boxes" -> formatBoxesReply(RoundResultStore.snapshot())
    "/all" -> formatAllReply(
        RoundResultStore.snapshot(), BotRuntime.diagnostics(), GameProgressWatchdogManager.status(),
    )
    "/friend" -> formatFriendReply(BotRuntime.friendLifeStatus())
    "/health" -> formatHealthReply(
        BotRuntime.diagnostics(), LatestFrameProvider.diagnostics(),
        TelegramCommandReceiver.commandContext()?.let { BotAccessibilityService.isEnabled(it) } == true,
        BotAccessibilityService.isConnected(),
    )
    "/watchdog" -> formatWatchdogReply(
        GameProgressWatchdogManager.status(), BotRuntime.diagnostics(),
        latestRecoverySummary(DebugLogStore.snapshot().text),
    )
    "/rounds" -> formatRoundsReply(RoundResultStore.snapshot())
    "/errors" -> formatErrorsReply(DebugLogStore.snapshot().text)
    "/dashboard" -> "🤖 RobotRun Dashboard\n\nTap a button below. 📊 All shows stats, status and last round; 📥 Download Logs sends a private ZIP; 📦 Update App checks the feed and sends a verified newer APK."
    else -> null
}

/** One command response, reusing the existing formatters and a single session snapshot. */
internal fun formatAllReply(
    stats: com.example.cookierunbot.statistics.SessionStatistics,
    bot: com.example.cookierunbot.automation.BotControllerDiagnostics,
    progress: com.example.cookierunbot.automation.GameProgressStatus,
): String = listOf(
    formatStatsReply(stats),
    formatStatusReply(bot, progress),
    formatLastRoundReply(stats.lastRound),
).joinToString("\n\n──────────\n\n")

private fun formatStatusReply(): String = formatStatusReply(
    BotRuntime.diagnostics(),
    GameProgressWatchdogManager.status(),
)

internal fun formatStatusReply(
    bot: com.example.cookierunbot.automation.BotControllerDiagnostics,
    progress: com.example.cookierunbot.automation.GameProgressStatus,
): String {
    val watchdog = when {
        !bot.running -> "Stopped"
        progress.overdue -> "OVERDUE\nAlert: ${if (progress.alertDelivered) "sent" else "pending"}"
        else -> "${formatDuration(progress.remainingMs)} remaining"
    }
    val lastProgress = if (progress.hasStarted) {
        "${progress.lastProgressReason}\n${formatDuration(progress.ageMs)} ago"
    } else {
        "none"
    }
    return "🤖 RobotRun Status\n\nBot: ${if (bot.running) "RUNNING" else "STOPPED"}\n" +
        "Group: ${bot.group}\nHandler: ${bot.currentHandler}\nRound: ${bot.roundNumber}\n\n" +
        "Last progress: $lastProgress\n\n" +
        "Watchdog:\n$watchdog"
}

internal fun formatStatsReply(stats: com.example.cookierunbot.statistics.SessionStatistics): String {
    return "📊 RobotRun Session\n\nRuntime: ${formatDuration(stats.sessionRuntimeMs)}\nRounds: ${stats.rounds}\n\n" +
        "Coins: ${stats.totalCoins.formatNumber()}\nXP: ${stats.totalXp.formatNumber()}\n\n" +
        "Mystery Boxes: ${stats.totalBoxes}\nWood: ${stats.woodBoxes}\nSilver: ${stats.silverBoxes}\n" +
        "Gold: ${stats.goldBoxes}\nRainbow: ${stats.rainbowBoxes}\n\n" +
        "Anti-bot detected: ${stats.antiBotDetectedCount}\n" +
        "Anti-bot successful taps: ${stats.antiBotSuccessfulTapCount}"
}

internal fun formatLastRoundReply(result: RoundResult?): String {
    if (result == null) return "No completed rounds in this session yet."
    return "🎮 Last Round\n\nRound: ${result.round}\nCoins: ${result.coins?.formatNumber() ?: "unknown"}\n" +
        "XP: ${result.xp?.formatNumber() ?: "unknown"}\nBoxes: ${result.boxCount}\n" +
        "Box types: ${result.boxes.takeIf { it.isNotEmpty() }?.joinToString() ?: "none"}\n" +
        "Duration: ${result.durationMs?.let(::formatDuration) ?: "unknown"}"
}

internal fun formatBoxesReply(stats: com.example.cookierunbot.statistics.SessionStatistics): String {
    return "📦 Mystery Boxes\n\nTotal: ${stats.totalBoxes}\nWood: ${stats.woodBoxes}\n" +
        "Silver: ${stats.silverBoxes}\nGold: ${stats.goldBoxes}\nRainbow: ${stats.rainbowBoxes}"
}

private fun Int.formatNumber(): String = String.format(Locale.US, "%,d", this)
