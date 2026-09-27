package com.example.cookierunbot.notifications

/**
 * Persistent Telegram reply keyboard. All labels map to read-only commands.
 * The All button calls the existing /all aggregator (stats -> status -> last).
 */
internal object TelegramDashboardPolicy {
    const val ALL = "📊 All"
    const val SCREEN = "📸 Screen"
    const val FRIEND = "❤️ Friend Life"
    const val HEALTH = "🩺 Health"
    const val WATCHDOG = "⏱ Watchdog"
    const val ROUNDS = "🎮 Rounds"
    const val ERRORS = "⚠️ Errors"
    const val UPDATE = "📦 Update App"
    const val LOGS = "📥 Download Logs"
    const val DASHBOARD = "🏠 Dashboard"
    const val REFRESH = "🔄 Refresh"

    const val keyboardJson = """
        {"keyboard":[
          [{"text":"📊 All"},{"text":"📸 Screen"}],
          [{"text":"❤️ Friend Life"},{"text":"🩺 Health"}],
          [{"text":"⏱ Watchdog"},{"text":"🎮 Rounds"}],
          [{"text":"⚠️ Errors"},{"text":"📥 Download Logs"}],
          [{"text":"📦 Update App"},{"text":"🏠 Dashboard"}],
          [{"text":"🔄 Refresh"}]
        ],"resize_keyboard":true,"is_persistent":true,"one_time_keyboard":false}
    """

    fun screenshotFrameIsFresh(capturedAtNanos: Long, nowNanos: Long): Boolean =
        capturedAtNanos > 0L && nowNanos >= capturedAtNanos &&
            nowNanos - capturedAtNanos <= 1_000_000_000L

    fun commandForButton(text: String): String? = when (text.trim()) {
        ALL, REFRESH -> "/all"
        SCREEN -> "/screen"
        FRIEND -> "/friend"
        HEALTH -> "/health"
        WATCHDOG -> "/watchdog"
        ROUNDS -> "/rounds"
        ERRORS -> "/errors"
        UPDATE -> "/update"
        LOGS -> "/logs"
        DASHBOARD -> "/dashboard"
        else -> null
    }
}
