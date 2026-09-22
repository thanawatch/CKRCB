package com.example.cookierunbot.notifications

import android.content.Context

object TelegramConfigStore {
    private const val PREFERENCES = "telegram_notifications"

    fun load(context: Context): TelegramConfig {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return TelegramConfig(
            enabled = preferences.getBoolean("enabled", false),
            botToken = preferences.getString("bot_token", "").orEmpty(),
            chatId = preferences.getString("chat_id", "").orEmpty(),
            sendDebugImages = preferences.getBoolean("send_debug_images", true),
            sendTextNotifications = preferences.getBoolean("send_text_notifications", true),
        )
    }

    fun save(context: Context, config: TelegramConfig) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", config.enabled)
            .putString("bot_token", config.botToken.trim())
            .putString("chat_id", config.chatId.trim())
            .putBoolean("send_debug_images", config.sendDebugImages)
            .putBoolean("send_text_notifications", config.sendTextNotifications)
            .apply()
    }
}
